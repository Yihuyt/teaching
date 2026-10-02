import { ref } from 'vue'

import { api, errorMessage } from '@/api/client'
import type { InsertScript } from '@/features/blockcoding/chatStream'
import type { MessageView } from '@/api/generated'
import type { InsertVariable, ScratchBridge } from '@/features/blockcoding/scratchBridge'
import type { useBlockCodingStore } from '@/features/blockcoding/store'

function insertVariables(script: InsertScript): InsertVariable[] {
  return [
    ...script.variables.map((name) => ({ name, type: 'var' as const, scope: 'global' as const })),
    ...script.localVariables.map((name) => ({ name, type: 'var' as const, scope: 'local' as const })),
    ...script.lists.map((name) => ({ name, type: 'list' as const, scope: 'global' as const })),
    ...script.localLists.map((name) => ({ name, type: 'list' as const, scope: 'local' as const })),
    ...script.broadcasts.map((name) => ({ name, type: 'broadcast' as const, scope: 'global' as const })),
  ]
}

/** 回退的结果:done 时 skipped 是原积木所在角色已经不在、没能放回的段数,unrestorable 是被删的原有角色(造型、声音回不来) */
type RevertOutcome =
  | { status: 'done'; skipped: number; unrestorable: string[] }
  | { status: 'busy' }
  | { status: 'failed'; reason: string }

/**
 * 助手对作品的改动在真实编辑器里落地与回退:服务端在 final_answer 时按顺序发 browser_tool(建角色、插脚本、删脚本),
 * 这里逐个执行并把结果回传;用户点"回退这轮改动"时倒着恢复(新写的删掉、改写的删新插旧、删掉的插回去)。
 */
export function useEditorChanges(bridge: ScratchBridge, store: ReturnType<typeof useBlockCodingStore>) {
  const reverting = ref(false)

  async function runBrowserTool(sessionId: number, callId: string, name: string, args: Record<string, unknown>): Promise<void> {
    let ok = false
    let result: Record<string, unknown> | null = null
    let error: string | null = null
    try {
      if (name === 'create_sprite') {
        result = { ...(await bridge.createSprite(String(args.name ?? ''))) }
        store.markDirty()
      } else if (name === 'insert_script') {
        const script = args.script as InsertScript
        const harvest = await bridge.harvest()
        const existingProcedures = new Set(Object.values(harvest.procedures).flat().map((procedure) => procedure.proccode))
        const inserted = await bridge.insertBlocks({
          xml: script.xml,
          variables: insertVariables(script),
          replaceProcedures: script.definedProcedures.filter((procedure) => existingProcedures.has(procedure)),
          targetSprite: script.sprite,
        })
        result = { blockId: inserted.topBlockIds[0] ?? null }
        store.markDirty()
      } else if (name === 'remove_script') {
        result = { ...(await bridge.removeBlocks({ targetSprite: String(args.sprite), blockIds: [String(args.blockId)] })) }
        store.markDirty()
      } else if (name === 'delete_variable') {
        result = { ...(await bridge.deleteVariable({ sprite: args.sprite == null ? null : String(args.sprite), name: String(args.name ?? ''), list: args.list === true })) }
        store.markDirty()
      } else if (name === 'delete_sprite') {
        result = { ...(await bridge.deleteSprite(String(args.name ?? ''))) }
        store.markDirty()
      } else {
        throw new Error(`不支持的动作 ${name}`)
      }
      ok = true
    } catch (caught) {
      error = errorMessage(caught)
    }
    await api.blockCodingChatBrowserToolResult(sessionId, { callId, ok, result, error })
  }

  async function revertChanges(message: MessageView): Promise<RevertOutcome> {
    if (reverting.value || !store.session) return { status: 'busy' }
    reverting.value = true
    try {
      const harvest = await bridge.harvest()
      const spriteNames = new Set(harvest.sprites.map((sprite) => sprite.name))
      let skipped = 0
      const undoOne = async (script: MessageView['scripts'][number]): Promise<void> => {
        if (script.blockId) {
          await bridge.removeBlocks({ targetSprite: script.sprite, blockIds: [script.blockId] })
        }
        if (!script.previous) return
        if (!spriteNames.has(script.previous.sprite)) {
          skipped += 1
          return
        }
        await bridge.insertBlocks({ xml: script.previous.xml, variables: [], replaceProcedures: [], targetSprite: script.previous.sprite })
      }
      // 逐段顺序恢复:并发操作会在切换角色时把积木放错地方
      await message.scripts.toReversed().reduce((chain, script) => chain.then(() => undoOne(script)), Promise.resolve())
      const unrestorable: string[] = []
      // 角色与变量同样逐个顺序恢复:桥按队列串行执行,并发只会互相等
      await message.sprites.toReversed().reduce((chain, sprite) => chain.then(async () => {
        if (sprite.kind === 'created') await bridge.deleteSprite(sprite.name)
        else if (sprite.preexisting) unrestorable.push(sprite.name)
      }), Promise.resolve())
      await message.variables.toReversed().reduce((chain, variable) => chain.then(async () => {
        const sprite = variable.sprite ?? null
        if (sprite !== null && !spriteNames.has(sprite)) return
        const request = { sprite, name: variable.name, list: variable.list }
        if (variable.kind === 'deleted') await bridge.createVariable(request)
        else await bridge.deleteVariable(request)
      }), Promise.resolve())
      store.markDirty()
      const response = await api.blockCodingChatRevert(store.session.id, message.id)
      store.messages = store.messages.map((item) => (item.id === message.id ? response.data : item))
      return { status: 'done', skipped, unrestorable }
    } catch (error) {
      return { status: 'failed', reason: errorMessage(error) }
    } finally {
      reverting.value = false
    }
  }

  return { reverting, runBrowserTool, revertChanges }
}

import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'

import { api, errorMessage } from '@/api/client'
import type ChatComposer from '@/features/blockcoding/components/ChatComposer.vue'
import type { DraggedStack, ScratchBridge } from '@/features/blockcoding/scratchBridge'

export interface Quote {
  label: string
  sprite: string
  blockId: string
  xml: string
  code: string
  blockCount: number
}

const MAX_QUOTES = 5

/**
 * 把编辑器里的积木拖进对话框:scratch-blocks 把积木拖出工作区松手时会自己弹回原位并发事件,这里判断落点在面板上就
 * 把那段积木转成文本、在光标处插进一个整块的标签;发送时标签的位置变成【积木N】标记,对应的积木随消息一起发。
 * 面板盖在积木区上时,编辑器靠创作台报告的面板区域(host/overlay)把"拖到面板上"也算作拖出工作区。
 */
export function useQuotes(options: {
  bridge: ScratchBridge
  composer: Ref<InstanceType<typeof ChatComposer> | null>
  panel: Ref<HTMLElement | null>
  accepting: () => boolean
}) {
  /** 这条草稿里拖进来过的积木,标签就是它在这里的序号;块被删了也留着,再拖同一段就还用原标签 */
  const quotes = ref<Quote[]>([])
  const chipLabels = ref<string[]>([])
  const dragOver = ref(false)
  const referencedQuotes = computed(() => quotes.value.filter((quote) => chipLabels.value.includes(quote.label)))

  function droppedOnPanel(x: number, y: number): boolean {
    const frame = document.querySelector<HTMLIFrameElement>('iframe.editor-frame')
    const rect = options.panel.value?.getBoundingClientRect()
    if (!frame || !rect) return false
    const frameRect = frame.getBoundingClientRect()
    const px = frameRect.left + x
    const py = frameRect.top + y
    return px >= rect.left && px <= rect.right && py >= rect.top && py <= rect.bottom
  }

  async function quoteDraggedStack(stack: DraggedStack): Promise<void> {
    dragOver.value = false
    if (!options.accepting() || !droppedOnPanel(stack.x, stack.y)) return
    const already = quotes.value.find((quote) => quote.blockId === stack.blockId)
    if (already) {
      options.composer.value?.insertChip(already)
      return
    }
    if (referencedQuotes.value.length >= MAX_QUOTES) {
      ElMessage.warning(`一次最多带 ${MAX_QUOTES} 段积木`)
      return
    }
    try {
      const response = await api.blockCodingChatScriptText({ sprite: stack.sprite, xml: stack.xml })
      const label = `积木${quotes.value.length + 1}`
      const quote = { ...stack, label, code: response.data.code, blockCount: response.data.blockCount }
      quotes.value = [...quotes.value, quote]
      options.composer.value?.insertChip(quote)
    } catch (error) {
      ElMessage.error(errorMessage(error))
    }
  }

  /** 发送时取走草稿:随消息发的积木 + 整份草稿(发送失败时原样放回) */
  function takeDraft(): { attached: Quote[]; draft: Quote[] } {
    const taken = { attached: referencedQuotes.value, draft: quotes.value }
    quotes.value = []
    return taken
  }

  function restoreDraft(draft: Quote[]): void {
    quotes.value = draft
  }

  onMounted(() => {
    options.bridge.onBlockDrag({
      // 积木一离开工作区就把面板亮成落点(编辑器只在进出工作区时通知一次,松手时再按落点判断)
      // 生成中不收积木(松手也不会有反应),那就别把面板亮成落点
      outside: (outside) => {
        dragOver.value = outside && options.accepting()
      },
      draggedOut: (stack) => void quoteDraggedStack(stack),
    })
  })

  onBeforeUnmount(() => {
    options.bridge.onBlockDrag(null)
  })

  return { chipLabels, dragOver, takeDraft, restoreDraft }
}

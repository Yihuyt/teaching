<script setup lang="ts">
import { computed } from 'vue'

import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'
import ScratchblocksFence from '@/features/blockcoding/components/ScratchblocksFence.vue'
import type { MessageView, ScriptView } from '@/api/generated'
import { spriteLabel } from '@/features/blockcoding/spriteLabel'

const props = defineProps<{
  message: MessageView
  answerable: boolean
  reverting: boolean
}>()

const emit = defineEmits<{
  answer: [option: string]
  revert: []
}>()

/**
 * 助手正文里的 ``` 围栏是说明用的积木片段(比如"碰到边缘反弹用这个积木"),渲染成积木图;
 * 用户正文里的【积木N】标记渲染成拖进来的那段积木(按 label 对上);其余段落照常走 Markdown。
 */
const segments = computed(() => {
  const content = props.message.content ?? ''
  const out: { kind: 'text' | 'blocks' | 'quote'; text: string; script?: ScriptView }[] = []
  if (props.message.role === 'user') {
    const byLabel = new Map(props.message.scripts.filter((s) => s.label).map((s) => [s.label as string, s]))
    const markerPattern = /【([^【】]{1,20})】/g
    let last = 0
    for (const match of content.matchAll(markerPattern)) {
      const script = byLabel.get(match[1] ?? '')
      if (!script) continue
      const start = match.index ?? 0
      if (start > last) out.push({ kind: 'text', text: content.slice(last, start) })
      out.push({ kind: 'quote', text: match[0], script })
      last = start + match[0].length
    }
    if (last < content.length) out.push({ kind: 'text', text: content.slice(last) })
    return out
  }
  const fence = /```[^\n]*\n([\s\S]*?)```/g
  let last = 0
  for (const match of content.matchAll(fence)) {
    const start = match.index ?? 0
    if (start > last) out.push({ kind: 'text', text: content.slice(last, start) })
    out.push({ kind: 'blocks', text: (match[1] ?? '').trim() })
    last = start + match[0].length
  }
  if (last < content.length) out.push({ kind: 'text', text: content.slice(last) })
  return out
})

const objectChanges = computed(() => {
  const lines: string[] = []
  for (const sprite of props.message.sprites) {
    lines.push(sprite.kind === 'created' ? `新建了角色 ${sprite.name}` : `删掉了角色 ${sprite.name}${sprite.preexisting ? '（作品里原有的，造型和声音回退时恢复不了）' : ''}`)
  }
  for (const variable of props.message.variables) {
    if (variable.kind !== 'deleted') continue
    lines.push(`删掉了${variable.list ? '列表' : '变量'} ${variable.name}${variable.sprite ? `（${spriteLabel(variable.sprite)} 的）` : ''}`)
  }
  return lines
})

/** 正文里没有对应标记的引用积木(标记被删了又发出来的历史记录),兜底列在下面 */
const orphanQuotes = computed(() => {
  const content = props.message.content ?? ''
  return props.message.role === 'user' ? props.message.scripts.filter((s) => !s.label || !content.includes(`【${s.label}】`)) : []
})

const orphanGroups = computed(() => {
  const bySprite = new Map<string, ScriptView[]>()
  for (const script of orphanQuotes.value) {
    const list = bySprite.get(script.sprite) ?? []
    list.push(script)
    bySprite.set(script.sprite, list)
  }
  return [...bySprite.entries()].map(([sprite, scripts]) => ({ sprite, scripts }))
})

const groups = computed(() => {
  const bySprite = new Map<string, ScriptView[]>()
  for (const script of props.message.scripts) {
    const list = bySprite.get(script.sprite) ?? []
    list.push(script)
    bySprite.set(script.sprite, list)
  }
  return [...bySprite.entries()].map(([sprite, scripts]) => ({ sprite, scripts }))
})
</script>

<template>
  <div class="chat-message" :class="`chat-message--${message.role}`">
    <div class="chat-message__bubble">
      <template v-for="(segment, index) in segments" :key="index">
        <ScratchblocksFence v-if="segment.kind === 'blocks'" :code="segment.text" bare />
        <div v-else-if="segment.kind === 'quote' && segment.script" class="quote">
          <span class="quote__label">{{ segment.text }} · {{ spriteLabel(segment.script.sprite) }}</span>
          <ScratchblocksFence :code="segment.script.code" bare />
        </div>
        <span v-else-if="message.role === 'user'" class="user-text">{{ segment.text }}</span>
        <MarkdownRenderer v-else-if="segment.text.trim()" :source="segment.text" />
      </template>

      <div v-if="message.role === 'assistant' ? message.scripts.length + objectChanges.length > 0 : orphanQuotes.length > 0" class="changes">
        <div class="changes__header">
          <span>{{ message.role === 'user' ? '指着的积木' : `助手改了作品里的 ${message.scripts.length + objectChanges.length} 处` }}</span>
          <span class="changes__spacer" />
          <el-tag v-if="message.reverted" size="small" type="info" effect="plain">已回退</el-tag>
          <el-button v-else-if="message.role === 'assistant'" size="small" text type="danger" :loading="reverting" @click="emit('revert')">
            回退这轮改动
          </el-button>
        </div>
        <div v-if="objectChanges.length > 0" class="changes__objects">
          <div v-for="line in objectChanges" :key="line" class="changes__deletion">
            <span class="changes__deletion-label">{{ line }}</span>
          </div>
        </div>
        <div v-for="group in (message.role === 'user' ? orphanGroups : groups)" :key="group.sprite" class="changes__group">
          <template v-for="script in group.scripts" :key="script.id">
            <div v-if="script.kind === 'deleted'" class="changes__deletion">
              <span class="changes__deletion-label">删掉了 {{ spriteLabel(script.sprite) }} 里的这段:</span>
              <code class="changes__deletion-code">{{ script.code.split('\n')[0] }}</code>
            </div>
            <ScratchblocksFence v-else :code="script.code" :sprite="script.sprite" :block-count="script.blockCount">
              <template v-if="script.kind !== 'quoted'" #actions>
                <el-tag size="small" :type="script.kind === 'replaced' ? 'warning' : 'success'" effect="plain">
                  {{ script.kind === 'replaced' ? '改写了原有脚本' : '已写进作品' }}
                </el-tag>
              </template>
            </ScratchblocksFence>
          </template>
        </div>
      </div>

      <div v-if="message.question && answerable && message.question.options.length > 0" class="chat-message__options">
        <el-button
          v-for="option in message.question.options"
          :key="option"
          size="small"
          @click="emit('answer', option)"
        >
          {{ option }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-message {
  display: flex;
  margin: 6px 0;
}

.chat-message--user {
  justify-content: flex-end;
}

.chat-message__bubble {
  max-width: 92%;
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
  font-size: 14px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.chat-message--user .chat-message__bubble {
  background: var(--el-color-primary-light-9);
}

.changes {
  margin-top: 8px;
}

.user-text {
  white-space: pre-wrap;
}

.quote {
  margin: 4px 0;
}

.quote__label {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.changes__header {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.changes__spacer {
  flex: 1;
}

.changes__deletion {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 8px 0;
  padding: 6px 10px;
  border: 1px dashed var(--el-color-danger-light-5);
  border-radius: 6px;
  font-size: 12px;
  background: var(--el-color-danger-light-9);
}

.changes__deletion-code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
}

.chat-message__options {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
</style>

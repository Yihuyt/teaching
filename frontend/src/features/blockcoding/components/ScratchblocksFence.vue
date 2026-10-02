<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import scratchblocks from 'scratchblocks/browser.es.js'

import { parseChinese } from '@/features/blockcoding/scratchblocksZh'
import { spriteLabel } from '@/features/blockcoding/spriteLabel'

const props = defineProps<{
  code: string
  sprite?: string
  blockCount?: number
  bare?: boolean
}>()

const host = ref<HTMLDivElement | null>(null)

/**
 * 单独成行的 // 注释在 scratchblocks 里会渲染成夹在积木中间的一块便签,打断脚本;
 * 把它挂到紧接着的那个积木后面(scratchblocks 的行尾注释显示在积木右侧)。空行会被当成脚本分隔,也去掉。
 */
function attachComments(code: string): string {
  const out: string[] = []
  let pending: string[] = []
  for (const raw of code.split('\n')) {
    const line = raw.trimEnd()
    if (!line.trim()) continue
    const comment = /^\s*\/\/\s*(.*)$/.exec(line)
    if (comment) {
      if (comment[1]) pending.push(comment[1])
      continue
    }
    const stripped = line.replace(/\s*\/\/\s*(.*)$/, (_, trailing: string) => {
      if (trailing) pending.push(trailing)
      return ''
    })
    out.push(pending.length > 0 ? `${stripped} // ${pending.join(';')}` : stripped)
    pending = []
  }
  if (pending.length > 0 && out.length > 0) out[out.length - 1] += ` // ${pending.join(';')}`
  return out.join('\n')
}

function renderBlocks(): void {
  if (!host.value) {
    return
  }
  host.value.replaceChildren()
  try {
    const doc = parseChinese(attachComments(props.code))
    // render 必须显式给 style，缺省会在读取样式表时抛错
    host.value.appendChild(scratchblocks.render(doc, { style: 'scratch3' }))
  } catch {
    const fallback = document.createElement('pre')
    fallback.textContent = props.code
    host.value.appendChild(fallback)
  }
}

onMounted(renderBlocks)
watch(() => props.code, renderBlocks)
</script>

<template>
  <div class="fence" :class="{ 'fence--bare': bare }">
    <div v-if="!bare" class="fence__header">
      <span v-if="sprite" class="fence__sprite">{{ spriteLabel(sprite) }}</span>
      <span class="fence__count">{{ blockCount }} 个积木</span>
      <span class="fence__spacer" />
      <slot name="actions" />
    </div>
    <div ref="host" class="fence__blocks"></div>
  </div>
</template>

<style scoped>
.fence {
  margin: 8px 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: #fff;
  overflow: hidden;
}

.fence--bare {
  display: inline-block;
  max-width: 100%;
  margin: 4px 0;
}

.fence__header {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 4px 10px;
  font-size: 12px;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
}

.fence__sprite {
  font-weight: 600;
  color: var(--el-text-color-regular);
}

.fence__spacer {
  flex: 1;
}

.fence__blocks {
  padding: 8px;
  overflow-x: auto;
}

.fence__blocks :deep(pre) {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
}
</style>

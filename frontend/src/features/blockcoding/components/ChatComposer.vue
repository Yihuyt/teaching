<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { spriteLabel } from '@/features/blockcoding/spriteLabel'

/**
 * 聊天输入区。普通文字随便打;拖进来的积木是一个**整块**(不可编辑的小标签):光标只能停在它前后,
 * 退格一下整块消失,块没了这段积木就不再随消息发。发送时块的位置变成 【标签】 标记,服务端按标记对上积木。
 */
export interface ComposerChip {
  label: string
  sprite: string
  code: string
}

defineProps<{
  placeholder: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  submit: []
  change: [labels: string[]]
}>()

const host = ref<HTMLDivElement | null>(null)
/** 光标不在编辑区时记着上次的位置,拖积木进来仍能落到原地 */
let savedRange: Range | null = null

function chipsIn(): HTMLElement[] {
  return host.value ? Array.from(host.value.querySelectorAll<HTMLElement>('.chip')) : []
}

function isChip(node: Node | null | undefined): node is HTMLElement {
  return node instanceof HTMLElement && node.classList.contains('chip')
}

/** 只有零宽空格(积木块后面垫的那个)的文字节点 */
function isBlank(node: Node | null | undefined): node is Text {
  return node?.nodeType === Node.TEXT_NODE && (node.textContent ?? '').replace(/\u200B/g, '') === ''
}

/** 光标紧挨着的积木块:退格看前面,删除键看后面;中间隔着零宽空格也算挨着 */
function chipBeside(side: 'before' | 'after'): HTMLElement | null {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0 || !selection.isCollapsed || !host.value) return null
  const { startContainer, startOffset } = selection.getRangeAt(0)
  if (!host.value.contains(startContainer)) return null
  let node: Node | null
  if (startContainer.nodeType === Node.TEXT_NODE) {
    const text = startContainer.textContent ?? ''
    const between = side === 'before' ? text.slice(0, startOffset) : text.slice(startOffset)
    if (between.replace(/\u200B/g, '') !== '') return null
    node = side === 'before' ? startContainer.previousSibling : startContainer.nextSibling
  } else {
    node = startContainer.childNodes[side === 'before' ? startOffset - 1 : startOffset] ?? null
  }
  while (isBlank(node)) node = side === 'before' ? node.previousSibling : node.nextSibling
  return isChip(node) ? node : null
}

function removeChip(el: HTMLElement): void {
  const caret = document.createRange()
  caret.setStartBefore(el)
  caret.collapse(true)
  if (isBlank(el.nextSibling)) el.nextSibling.remove()
  el.remove()
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(caret)
  savedRange = caret.cloneRange()
  notify()
}

function notify(): void {
  emit('change', chipsIn().map((chip) => chip.dataset.label ?? ''))
}

function rememberSelection(): void {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0 || !host.value) return
  const range = selection.getRangeAt(0)
  if (host.value.contains(range.commonAncestorContainer)) savedRange = range.cloneRange()
}

function read(): string {
  if (!host.value) return ''
  const parts: string[] = []
  const walk = (node: Node): void => {
    if (node.nodeType === Node.TEXT_NODE) {
      parts.push((node.textContent ?? '').replace(/\u200B/g, ''))
      return
    }
    if (!(node instanceof HTMLElement)) return
    if (node.classList.contains('chip')) {
      parts.push(`【${node.dataset.label ?? ''}】`)
      return
    }
    if (node.tagName === 'BR') {
      parts.push('\n')
      return
    }
    const block = node.tagName === 'DIV' || node.tagName === 'P'
    if (block && parts.length > 0 && !parts[parts.length - 1]?.endsWith('\n')) parts.push('\n')
    node.childNodes.forEach(walk)
  }
  host.value.childNodes.forEach(walk)
  return parts.join('').replace(/\n+$/, '')
}

function isEmpty(): boolean {
  return read().trim() === ''
}

function chipElement(chip: ComposerChip): HTMLElement {
  const el = document.createElement('span')
  el.className = 'chip'
  el.contentEditable = 'false'
  el.dataset.label = chip.label
  el.title = chip.code
  const name = document.createElement('span')
  name.className = 'chip__name'
  name.textContent = `${chip.label} · ${spriteLabel(chip.sprite)}`
  const remove = document.createElement('button')
  remove.type = 'button'
  remove.className = 'chip__remove'
  remove.setAttribute('aria-label', `去掉${chip.label}`)
  remove.textContent = '×'
  remove.addEventListener('mousedown', (event) => event.preventDefault())
  remove.addEventListener('click', () => removeChip(el))
  el.append(name, remove)
  return el
}

function insertChip(chip: ComposerChip): void {
  const root = host.value
  if (!root) return
  const el = chipElement(chip)
  const tail = document.createTextNode('\u200B')
  root.focus()
  const selection = window.getSelection()
  let range: Range | null = null
  if (savedRange && root.contains(savedRange.commonAncestorContainer)) range = savedRange
  else if (selection && selection.rangeCount > 0 && root.contains(selection.getRangeAt(0).commonAncestorContainer)) range = selection.getRangeAt(0)
  if (range) {
    range.deleteContents()
    range.insertNode(tail)
    range.insertNode(el)
  } else {
    root.append(el, tail)
  }
  const after = document.createRange()
  after.setStartAfter(tail)
  after.collapse(true)
  selection?.removeAllRanges()
  selection?.addRange(after)
  savedRange = after.cloneRange()
  notify()
}

function clear(): void {
  if (host.value) host.value.innerHTML = ''
  savedRange = null
  notify()
}

function restore(text: string, chips: ComposerChip[]): void {
  const root = host.value
  if (!root) return
  root.innerHTML = ''
  const byLabel = new Map(chips.map((chip) => [chip.label, chip]))
  const pattern = /【([^【】]{1,20})】/g
  let last = 0
  for (const match of text.matchAll(pattern)) {
    const chip = byLabel.get(match[1] ?? '')
    if (!chip) continue
    const start = match.index ?? 0
    if (start > last) root.append(document.createTextNode(text.slice(last, start)))
    root.append(chipElement(chip), document.createTextNode('\u200B'))
    last = start + match[0].length
  }
  if (last < text.length) root.append(document.createTextNode(text.slice(last)))
  notify()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    emit('submit')
    return
  }
  if (event.key === 'Enter' && event.shiftKey) {
    event.preventDefault()
    document.execCommand('insertText', false, '\n')
    return
  }
  if (event.key === 'Backspace' || event.key === 'Delete') {
    const chip = chipBeside(event.key === 'Backspace' ? 'before' : 'after')
    if (chip) {
      event.preventDefault()
      removeChip(chip)
    }
  }
}

function onPaste(event: ClipboardEvent): void {
  // 只收纯文字,不让别处的格式混进来
  event.preventDefault()
  const text = event.clipboardData?.getData('text/plain') ?? ''
  document.execCommand('insertText', false, text)
}

function focus(): void {
  host.value?.focus()
}

onMounted(() => {
  document.addEventListener('selectionchange', rememberSelection)
})

onBeforeUnmount(() => {
  document.removeEventListener('selectionchange', rememberSelection)
})

defineExpose({ read, isEmpty, insertChip, clear, restore, focus })
</script>

<template>
  <div
    ref="host"
    class="chat-composer"
    :class="{ 'chat-composer--disabled': disabled }"
    :contenteditable="!disabled"
    role="textbox"
    aria-multiline="true"
    :aria-placeholder="placeholder"
    :data-placeholder="placeholder"
    @keydown="onKeydown"
    @input="notify"
    @paste="onPaste"
    @blur="rememberSelection"
  ></div>
</template>

<style scoped>
.chat-composer {
  flex: 1;
  min-height: 56px;
  max-height: 160px;
  padding: 6px 10px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: #fff;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  overflow-y: auto;
  outline: none;
}

.chat-composer:focus {
  border-color: var(--el-color-primary);
}

.chat-composer--disabled {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-placeholder);
}

.chat-composer:empty::before {
  content: attr(data-placeholder);
  color: var(--el-text-color-placeholder);
  pointer-events: none;
}

.chat-composer :deep(.chip) {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin: 0 2px;
  padding: 0 4px 0 8px;
  border-radius: 10px;
  background: #4c97ff;
  color: #fff;
  font-size: 12px;
  line-height: 20px;
  vertical-align: baseline;
  user-select: none;
  cursor: default;
}

.chat-composer :deep(.chip__remove) {
  border: 0;
  padding: 0 4px;
  background: transparent;
  color: rgb(255 255 255 / 80%);
  font-size: 13px;
  line-height: 20px;
  cursor: pointer;
}

.chat-composer :deep(.chip__remove:hover) {
  color: #fff;
}
</style>

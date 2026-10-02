<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps<{
  segments: readonly string[]
  /** 正在念第几段;-1 表示还没开始 */
  currentIndex: number
  /** 当前段开始的时间戳与预计时长(ms),用来按进度逐字显现 */
  segmentStartedAt: number
  segmentDurationMs: number
  playing: boolean
  askActive: boolean
  askThinking: boolean
  askText: string
  askFinal: boolean
}>()

const emit = defineEmits<{ askAgain: []; closeAsk: [] }>()

const listEl = ref<HTMLElement | null>(null)
const revealed = ref(0)
let frame = 0

function tick(): void {
  const text = props.segments[props.currentIndex] ?? ''
  if (!text) {
    revealed.value = 0
    return
  }
  const elapsed = Date.now() - props.segmentStartedAt
  const duration = Math.max(300, props.segmentDurationMs)
  // 字比声音略快一点,读到句尾时字已经全出来了
  const target = Math.min(text.length, Math.ceil((text.length * elapsed) / (duration * 0.9)))
  if (target > revealed.value) revealed.value = target
  if (revealed.value < text.length && props.playing) frame = requestAnimationFrame(tick)
}

function restart(): void {
  cancelAnimationFrame(frame)
  revealed.value = 0
  if (props.currentIndex >= 0) frame = requestAnimationFrame(tick)
}

watch(() => [props.currentIndex, props.segmentStartedAt], restart)
watch(
  () => props.playing,
  (playing) => {
    cancelAnimationFrame(frame)
    if (playing) frame = requestAnimationFrame(tick)
  },
)
watch(
  () => props.segmentDurationMs,
  () => {
    if (props.playing) {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(tick)
    }
  },
)
watch(
  () => [props.currentIndex, revealed.value, props.askText],
  () => {
    const el = listEl.value
    if (el) el.scrollTop = el.scrollHeight
  },
  { flush: 'post' },
)

onBeforeUnmount(() => cancelAnimationFrame(frame))

const spoken = computed(() =>
  props.segments.slice(0, props.currentIndex + 1).map((text, i) => ({
    key: i,
    text: i === props.currentIndex ? text.slice(0, revealed.value) : text,
    current: i === props.currentIndex,
    done: i === props.currentIndex && revealed.value >= text.length,
  })),
)
</script>

<template>
  <aside class="lecture-panel">
    <div class="lecture-head">
      <span class="teacher-avatar" aria-hidden="true">师</span>
      <span class="teacher-name">AI 老师</span>
      <span v-if="playing" class="teacher-state">正在讲</span>
    </div>

    <div ref="listEl" class="lecture-list">
      <p v-if="segments.length === 0 && !askActive" class="lecture-empty">这一页还没有讲稿</p>

      <div
        v-for="item in spoken"
        :key="item.key"
        class="say"
        :class="{ current: item.current, past: !item.current }"
      >
        {{ item.text }}<span v-if="item.current && !item.done && playing" class="caret" />
      </div>

      <div v-if="askActive" class="say answer">
        <span class="answer-tag">答疑</span>
        <span v-if="askThinking" class="thinking">老师想了想…</span>
        <template v-else>{{ askText }}<span v-if="!askFinal" class="caret" /></template>
        <div v-if="askFinal" class="answer-actions">
          <button type="button" class="link-btn" @click="emit('askAgain')">再问一个</button>
          <button type="button" class="primary-btn" @click="emit('closeAsk')">继续上课</button>
        </div>
        <div v-else-if="!askThinking" class="answer-actions">
          <button type="button" class="link-btn" @click="emit('closeAsk')">取消</button>
        </div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.lecture-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  overflow: hidden;
}

.lecture-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
}

.teacher-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.teacher-name {
  font-weight: 600;
  font-size: 14px;
}

.teacher-state {
  margin-left: auto;
  font-size: 12px;
  color: var(--brand);
}

.lecture-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.lecture-empty {
  margin: auto 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-secondary, #6b7280);
}

.say {
  padding: 10px 12px;
  border-radius: 12px;
  border-top-left-radius: 4px;
  background: var(--brand-soft, #eef2ff);
  font-size: 14.5px;
  line-height: 1.6;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
}

.say.past {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-secondary, #6b7280);
  font-size: 13.5px;
}

.say.answer {
  background: #fff7ed;
  border-top-left-radius: 12px;
}

.answer-tag {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: #fb923c;
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  vertical-align: 1px;
}

.thinking {
  color: var(--text-secondary, #6b7280);
}

.caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--brand);
  animation: caret-blink 1s steps(2, start) infinite;
}

@keyframes caret-blink {
  to {
    visibility: hidden;
  }
}

.answer-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 8px;
}

.link-btn {
  border: none;
  background: none;
  padding: 4px 6px;
  font-size: 13px;
  color: var(--brand);
  cursor: pointer;
}

.primary-btn {
  border: none;
  border-radius: 6px;
  padding: 4px 12px;
  font-size: 13px;
  color: #fff;
  background: var(--brand);
  cursor: pointer;
}
</style>

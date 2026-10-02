<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { api, errorMessage } from '@/api/client'
import { streamAskQuestion, type QaAnswer, type QaAnswerAction } from '@/features/courseware/coursewareStream'
import type { Action, Stage } from '@/features/courseware/dsl'
import { isBlocklessScene } from '@/features/courseware/dsl'
import { DEFAULT_THEME, stripInline } from '@/features/courseware/layout'
import { createPlayerEngine } from '@/features/courseware/playerEngine'
import LecturePanel from '@/features/courseware/components/LecturePanel.vue'
import SceneCanvas from '@/features/courseware/render/SceneCanvas.vue'
import InteractiveHost from '@/features/courseware/render/InteractiveHost.vue'
import VideoHost from '@/features/courseware/render/VideoHost.vue'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'
import type { QuizVerdict } from '@/features/courseware/render/types'

const props = defineProps<{
  courseId: number
  coursewareId: number
  title: string
  stage: Stage
  /** 对象键 → 短期播放地址(图片与讲稿音频) */
  assetUrls: Record<string, string>
  mode: 'learn' | 'preview'
}>()

const router = useRouter()

function exitPlayer(): void {
  void router.push(
    props.mode === 'learn'
      ? `/courses/${props.courseId}?tab=courseware`
      : `/admin/courses/${props.courseId}?tab=courseware`,
  )
}

const player = createPlayerEngine()
const canvasWidth = ref(1080)

/** 判分结果按页缓存(裁决 + 当时的选择):翻回已答页完整还原标注 */
const quizVerdict = ref<QuizVerdict | null>(null)
const quizChosen = ref<string[] | null>(null)
const answeredByScene = new Map<string, { verdict: QuizVerdict; chosen: string[] }>()

function findQuizBlock(sceneId: string, blockId: string) {
  const scene = props.stage.scenes.find((p) => p.id === sceneId)
  const block = scene?.blocks.find((b) => b.id === blockId)
  return block && block.type === 'quiz_choice' ? block : null
}

async function submitQuiz(blockId: string, chosen: string[]): Promise<void> {
  const scene = player.currentScene.value
  if (!scene) return
  try {
    let verdict: QuizVerdict
    if (props.mode === 'learn') {
      const response = await api.coursewareLearningSubmitQuizAttempt(props.courseId, props.coursewareId, {
        sceneId: scene.id,
        blockId,
        chosen,
      })
      verdict = response.data
    } else {
      // 预览:管理面课件带答案,本地比对(不落任何记录)
      const block = findQuizBlock(scene.id, blockId)
      if (!block) return
      const expected = block.answer.toSorted()
      const actual = chosen.toSorted()
      verdict = {
        correct: expected.length === actual.length && expected.every((v, i) => v === actual[i]),
        answer: block.answer,
        explanation: block.explanation,
      }
    }
    quizVerdict.value = verdict
    quizChosen.value = chosen
    answeredByScene.set(scene.id, { verdict, chosen })
    player.submitQuiz(verdict.correct)
  } catch (error: unknown) {
    quizVerdict.value = null // 触发组件恢复可提交
    ElMessage.error(errorMessage(error))
  }
}

const askInputEl = ref<HTMLInputElement | null>(null)
const askQuestion = ref('')
const askLoading = ref(false)
const qaAnswer = ref<QaAnswer | null>(null)
const qaStreamText = ref('')
const askActive = computed(() => askLoading.value || !!qaStreamText.value || !!qaAnswer.value)
let qaAudio: HTMLAudioElement | null = null
let askAbort: AbortController | null = null

function onAskFocus(): void {
  player.beginAsk()
}

/** 失焦且没有进行中的问答:视为放弃提问,从断点续播(输入的草稿保留) */
function onAskBlur(): void {
  if (!askActive.value) {
    player.endAsk()
  }
}

/** 服务端只会返回 highlight(其余已过滤),这里按类型收窄成播放动作 */
function toPlayerActions(actions: QaAnswerAction[]): Action[] {
  const result: Action[] = []
  for (const action of actions) {
    if (action.type === 'highlight') {
      result.push({ type: 'highlight', target: action.target })
    }
  }
  return result
}

async function submitAsk(): Promise<void> {
  const scene = player.currentScene.value
  const question = askQuestion.value.trim()
  if (!scene || !question || askLoading.value) return
  player.beginAsk() // 键入过程中状态被外力改变(如换页后回来)时兜底再停一次
  askLoading.value = true
  qaStreamText.value = ''
  const controller = new AbortController()
  askAbort = controller
  try {
    await streamAskQuestion(
      props.courseId,
      props.coursewareId,
      { sceneId: scene.id, question },
      (event) => {
        if (controller.signal.aborted) return // 已被关闭/换页中止,丢弃后续事件
        if (event.type === 'answer_delta') {
          qaStreamText.value += event.text
        } else if (event.type === 'retry') {
          // 服务端校验打回,新一轮从头流出
          qaStreamText.value = ''
        } else if (event.type === 'done') {
          qaAnswer.value = event.answer
          qaStreamText.value = event.answer.text
          askQuestion.value = ''
          player.applyAnswerActions(toPlayerActions(event.answer.actions))
          playQaAudio(event.answer)
        } else {
          throw new Error(event.message)
        }
      },
      controller.signal,
    )
  } catch (error: unknown) {
    if (!(error instanceof DOMException && error.name === 'AbortError')) {
      ElMessage.error(errorMessage(error))
      qaStreamText.value = '' // 面板收起,输入框里的问题保留可重发
    }
  } finally {
    askLoading.value = false
    askAbort = null
  }
}

function playQaAudio(answer: QaAnswer): void {
  stopQaAudio()
  if (!answer.audio) return
  const mime = answer.audio.format === 'mp3' ? 'audio/mpeg' : `audio/${answer.audio.format}`
  qaAudio = new Audio(`data:${mime};base64,${answer.audio.base64}`)
  // 播放失败(自动播放策略等)不影响文本呈现
  void qaAudio.play().catch(() => undefined)
}

function stopQaAudio(): void {
  if (qaAudio) {
    qaAudio.pause()
    qaAudio = null
  }
}

function closeAsk(): void {
  askAbort?.abort()
  stopQaAudio()
  qaAnswer.value = null
  qaStreamText.value = ''
  askQuestion.value = ''
  player.endAsk()
}

function askAgain(): void {
  stopQaAudio()
  qaAnswer.value = null
  qaStreamText.value = ''
  askQuestion.value = ''
  player.applyAnswerActions([])
  askInputEl.value?.focus()
}

watch(
  () => player.sceneIndex.value,
  () => {
    // 问答面板跨页无意义(回答绑定提问时的页),换页即收起
    if (askActive.value) {
      askAbort?.abort()
      stopQaAudio()
      qaAnswer.value = null
      qaStreamText.value = ''
    }
    const scene = player.currentScene.value
    const cached = scene ? answeredByScene.get(scene.id) : undefined
    quizVerdict.value = cached?.verdict ?? null
    quizChosen.value = cached?.chosen ?? null
    if (cached) {
      player.submitQuiz(cached.verdict.correct) // 恢复"已作答",讲稿放完不再卡在等待作答
    }
    if (props.mode === 'learn' && scene) {
      // 上报失败不打断学习,但要让人知道这一页没记上
      api
        .coursewareLearningRecordSceneView(props.courseId, props.coursewareId, { sceneId: scene.id })
        .catch((cause: unknown) => ElMessage.error(errorMessage(cause)))
    }
  },
)

const segmentTexts = computed(() => (player.currentScene.value?.speech ?? []).map((segment) => segment.text))

const hasAudio = computed(() =>
  (player.stage.value?.scenes ?? []).some((p) => p.speech.some((s) => s.audioPath)),
)

// 讲解来自判分结果(学习模式的播放视图里没有答案与讲解)
const quizExplanation = computed(() =>
  quizVerdict.value?.explanation ? quizVerdict.value.explanation : null,
)

/** 空闲播放钮:未开播且当前页不是仿真页 / 视频页时展示(那两种页整页是学生自己操作的内容,不盖按钮) */
const showIdlePlay = computed(
  () => player.state.value === 'idle' && !isBlocklessScene(player.currentScene.value?.type ?? 'content'),
)

/** 讲台宽度与它左边的间距;窗口窄时讲台落到画布下面,不再占宽 */
const LECTURE_PANEL_WIDTH = 320
const LECTURE_PANEL_GAP = 16
const lecturePanelBeside = ref(true)

function fitCanvas(): void {
  const t = DEFAULT_THEME
  lecturePanelBeside.value = window.innerWidth >= 1100
  // 沉浸布局头 64 + 播放器标题行 ~56 + 底部控制条 ~88 + 呼吸留白
  const availW = window.innerWidth - 96 - (lecturePanelBeside.value ? LECTURE_PANEL_WIDTH + LECTURE_PANEL_GAP : 0)
  const availH = window.innerHeight - 230 - (lecturePanelBeside.value ? 0 : 200)
  canvasWidth.value = Math.floor(Math.min(availW, (availH * t.canvas.width) / t.canvas.height))
}

/** 学习模式:重访恢复本人历史作答(每题取最近一次;讲解不可重构,不显示讲解卡) */
async function restoreMyAttempts(): Promise<void> {
  const response = await api.coursewareLearningMyQuizAttempts(props.courseId, props.coursewareId)
  const latest = new Map<string, (typeof response.data)[number]>()
  for (const attempt of response.data.toSorted((a, b) => a.attemptedAt - b.attemptedAt)) {
    latest.set(attempt.sceneId, attempt)
  }
  for (const [sceneId, attempt] of latest) {
    answeredByScene.set(sceneId, {
      verdict: {
        correct: attempt.correct,
        answer: attempt.correct ? attempt.chosen : [],
        explanation: '',
      },
      chosen: attempt.chosen,
    })
  }
  const first = player.currentScene.value
  const cached = first ? answeredByScene.get(first.id) : undefined
  if (cached) {
    quizVerdict.value = cached.verdict
    quizChosen.value = cached.chosen
    player.submitQuiz(cached.verdict.correct)
  }
}

onMounted(() => {
  fitCanvas()
  window.addEventListener('resize', fitCanvas)
  player.load(props.stage, props.assetUrls)
  if (props.mode === 'learn') {
    const first = props.stage.scenes[0]
    if (first) {
      api
        .coursewareLearningRecordSceneView(props.courseId, props.coursewareId, { sceneId: first.id })
        .catch((cause: unknown) => ElMessage.error(errorMessage(cause)))
    }
    restoreMyAttempts().catch((cause: unknown) => ElMessage.error(errorMessage(cause)))
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', fitCanvas)
  stopQaAudio()
  player.stop()
})
</script>

<template>
  <div class="courseware-player">
    <header class="player-head">
      <el-button text class="head-back" @click="exitPlayer">返回</el-button>
      <div class="head-title">
        <strong>{{ stripInline(title) }}</strong>
        <span v-if="mode === 'preview'" class="preview-chip">教师预览</span>
      </div>
      <div class="scene-dots">
        <button
          v-for="(scene, i) in player.stage.value?.scenes ?? []"
          :key="scene.id"
          type="button"
          class="dot"
          :class="{ active: i === player.sceneIndex.value }"
          :title="`${i + 1}. ${stripInline(scene.title)}`"
          @click="player.gotoScene(i, false)"
        />
      </div>
    </header>

    <div class="stage" :class="{ stacked: !lecturePanelBeside }">
      <div
        :key="player.currentScene.value?.id ?? 'none'"
        class="canvas-panel"
        :style="{ width: `${canvasWidth}px`, height: `${(canvasWidth * 720) / 1280}px` }"
      >
        <InteractiveHost
          v-if="player.currentScene.value?.type === 'interactive'"
          :scene="player.currentScene.value"
        />
        <VideoHost
          v-else-if="player.currentScene.value?.type === 'video'"
          :scene="player.currentScene.value"
          :asset-urls="assetUrls"
        />
        <SceneCanvas
          v-else-if="player.currentScene.value"
          :scene="player.currentScene.value"
          :width="canvasWidth"
          :asset-urls="assetUrls"
          :hidden-ids="player.hiddenIds.value"
          :highlight-id="player.highlightId.value"
          :quiz-interactive="player.currentScene.value.type === 'quiz'"
          :quiz-verdict="quizVerdict"
          :quiz-chosen="quizChosen"
          @quiz-submit="submitQuiz"
        />

        <button v-if="showIdlePlay" type="button" class="idle-play" title="开始上课" @click="player.play()">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor">
            <path
              d="M8 5.14v13.72c0 .8.87 1.3 1.56.88l10.98-6.86a1.03 1.03 0 0 0 0-1.76L9.56 4.26A1.03 1.03 0 0 0 8 5.14Z"
            />
          </svg>
        </button>

        <div
          v-if="quizExplanation"
          class="explanation-card"
          :class="player.quizCorrect.value ? 'ok' : 'bad'"
        >
          <strong>{{ player.quizCorrect.value ? '回答正确!' : '再想想——' }}</strong>
          <InlineText :text="quizExplanation" />
        </div>
      </div>

      <LecturePanel
        :style="
          lecturePanelBeside
            ? { width: `${LECTURE_PANEL_WIDTH}px`, height: `${(canvasWidth * 720) / 1280}px` }
            : { width: `${canvasWidth}px`, height: '180px' }
        "
        :segments="segmentTexts"
        :current-index="player.segmentIndex.value"
        :segment-started-at="player.segmentStartedAt.value"
        :segment-duration-ms="player.segmentDurationMs.value"
        :playing="player.state.value === 'playing'"
        :ask-active="askActive"
        :ask-thinking="askLoading && !qaStreamText && !qaAnswer"
        :ask-text="qaAnswer ? qaAnswer.text : qaStreamText"
        :ask-final="!!qaAnswer"
        @ask-again="askAgain"
        @close-ask="closeAsk"
      />
    </div>

    <footer class="control-bar">
      <button
        type="button"
        class="icon-btn"
        title="上一页"
        :disabled="player.sceneIndex.value === 0"
        @click="player.prev()"
      >
        <svg
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          stroke-width="2.2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="m15 18-6-6 6-6" />
        </svg>
      </button>
      <button
        v-if="player.state.value !== 'playing'"
        type="button"
        class="play-btn"
        :title="player.state.value === 'idle' ? '开始上课' : '播放本页'"
        :disabled="player.state.value === 'waiting_quiz' || player.state.value === 'answering'"
        @click="player.play()"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
          <path
            d="M8 5.14v13.72c0 .8.87 1.3 1.56.88l10.98-6.86a1.03 1.03 0 0 0 0-1.76L9.56 4.26A1.03 1.03 0 0 0 8 5.14Z"
          />
        </svg>
      </button>
      <button v-else type="button" class="play-btn" title="暂停" @click="player.pause()">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
          <rect x="6" y="5" width="4" height="14" rx="1.2" />
          <rect x="14" y="5" width="4" height="14" rx="1.2" />
        </svg>
      </button>
      <button
        type="button"
        class="icon-btn"
        title="下一页"
        :disabled="player.sceneIndex.value + 1 >= (player.stage.value?.scenes.length ?? 0)"
        @click="player.next()"
      >
        <svg
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          stroke-width="2.2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="m9 18 6-6-6-6" />
        </svg>
      </button>
      <span class="scene-indicator">
        {{ player.sceneIndex.value + 1 }} / {{ player.stage.value?.scenes.length ?? 0 }}
      </span>
      <template v-if="mode === 'learn'">
        <span class="bar-divider" />
        <input
          ref="askInputEl"
          v-model="askQuestion"
          class="bar-ask-input"
          :disabled="askLoading"
          maxlength="500"
          :placeholder="
            player.state.value === 'answering' ? '输入问题,回车提问…' : '有疑问?直接输入,老师会停下来…'
          "
          @focus="onAskFocus"
          @blur="onAskBlur"
          @keyup.enter="submitAsk"
        />
      </template>

      <template
        v-if="!hasAudio || player.state.value === 'waiting_quiz' || player.state.value === 'finished'"
      >
        <span class="bar-divider" />
        <span v-if="!hasAudio" class="status-note">无声预览(尚未合成语音)</span>
        <span v-if="player.state.value === 'waiting_quiz'" class="status-note amber">请作答后继续</span>
        <span v-if="player.state.value === 'finished'" class="status-note green">本课结束</span>
      </template>
    </footer>
  </div>
</template>

<style scoped>
.courseware-player {
  height: calc(100vh - 64px);
  display: flex;
  flex-direction: column;
  background: #f5f6f8;
}

.player-head {
  height: 56px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex: none;
}

.head-back {
  flex: 0 0 auto;
  margin-right: 12px;
  color: inherit;
}

.head-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.head-title strong {
  font-size: 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-chip {
  flex: none;
  font-size: 12px;
  color: var(--warning);
  border: 1px solid currentcolor;
  border-radius: var(--radius-control);
  padding: 1px 8px;
}

.scene-dots {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: none;
  padding: 0;
  background: var(--border);
  cursor: pointer;
}

.dot:hover {
  background: var(--text-muted);
}

.dot.active {
  background: var(--brand);
}

.stage {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  min-height: 0;
  padding: 4px 24px 12px;
}

.stage.stacked {
  flex-direction: column;
  align-items: center;
}

.canvas-panel {
  position: relative;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  overflow: hidden;
}

.canvas-panel :deep(.scene-canvas-viewport) {
  border-radius: 0;
  box-shadow: none;
}

.idle-play {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 64px;
  height: 64px;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: #fff;
  color: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
  padding-left: 4px;
  cursor: pointer;
}

.idle-play:hover {
  border-color: var(--brand);
}

.bar-ask-input {
  width: 240px;
  border: 1px solid var(--border);
  outline: none;
  background: #fff;
  border-radius: var(--radius-control);
  padding: 6px 12px;
  font-size: 13px;
  color: var(--text);
  transition:
    width 0.2s ease,
    border-color 0.15s ease;
}

.bar-ask-input:focus {
  width: 320px;
  border-color: var(--brand);
}

.bar-ask-input::placeholder {
  color: var(--text-muted);
}

.bar-ask-input:disabled {
  opacity: 0.6;
}

.explanation-card {
  position: absolute;
  left: 50%;
  bottom: 14px;
  transform: translateX(-50%);
  max-width: min(720px, 86%);
  padding: 12px 18px;
  border-radius: var(--radius-panel);
  background: #fff;
  border: 1px solid var(--border);
  font-size: 15px;
  line-height: 1.6;
}

.explanation-card.ok {
  border-left: 4px solid var(--success);
}

.explanation-card.bad {
  border-left: 4px solid var(--warning);
}

.control-bar {
  flex: none;
  align-self: center;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  padding: 8px 16px;
  border-radius: var(--radius-panel);
  background: var(--surface);
  border: 1px solid var(--border);
}

.icon-btn {
  width: 30px;
  height: 30px;
  border-radius: var(--radius-control);
  border: 1px solid var(--border);
  background: #fff;
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.icon-btn:hover:not(:disabled) {
  border-color: var(--brand);
  color: var(--brand);
}

.icon-btn:disabled {
  opacity: 0.4;
  cursor: default;
}

.play-btn {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-control);
  border: none;
  background: var(--brand);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.play-btn:hover:not(:disabled) {
  filter: brightness(1.08);
}

.play-btn:disabled {
  opacity: 0.4;
  cursor: default;
}

.bar-divider {
  width: 1px;
  height: 18px;
  background: var(--border);
  margin: 0 4px;
}

.scene-indicator {
  color: var(--text-secondary);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  margin: 0 4px;
}

.status-note {
  font-size: 13px;
  color: var(--text-muted);
}

.status-note.amber {
  color: var(--warning);
}

.status-note.green {
  color: var(--success);
}
</style>

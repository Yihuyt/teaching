/**
 * 课件播放引擎 —— 页面级组合式函数(不进全局 store:一次播放一份状态,
 * 离开页面即销毁)。
 *
 * 时钟模型:音频 `ended` 事件是唯一时钟;动作在段首依序触发,段内不做
 * 时间戳对齐。无音频(未合成语音)时进入"无声预览":按讲稿字数估时。
 * 测验页:讲稿放完后暂停,等待作答;作答后允许前进。视频页:讲稿(开场引导)放完后暂停,学生自己看视频、自己翻页。
 */
import { computed, ref, shallowRef, type ComputedRef, type Ref } from 'vue'
import type { Action, Stage, Scene } from '@/features/courseware/dsl'

type PlayerState = 'idle' | 'playing' | 'paused' | 'waiting_quiz' | 'answering' | 'finished'

/** 估算无音频段的朗读时长(ms):中文约 4.5 字/秒 */
export function estimateDuration(text: string): number {
  return Math.max(1500, Math.round((text.length / 4.5) * 1000))
}

interface PlayerEngine {
  stage: Ref<Stage | null>
  state: Ref<PlayerState>
  sceneIndex: Ref<number>
  segmentIndex: Ref<number>
  /** 当前段开始的时间戳与预计时长(ms):有音频按音频时长,没有按字数估;讲台据此逐字显现 */
  segmentStartedAt: Ref<number>
  segmentDurationMs: Ref<number>
  hiddenIds: Ref<Set<string>>
  highlightId: Ref<string | null>
  quizAnswered: Ref<boolean>
  quizCorrect: Ref<boolean | null>
  currentScene: ComputedRef<Scene | null>
  /** 载入课件与其资源地址映射(讲稿段的 audioPath 经它解析成播放地址) */
  load: (target: Stage, assetUrls: Readonly<Record<string, string>>) => void
  play: () => void
  pause: () => void
  next: () => void
  prev: () => void
  gotoScene: (index: number, autoplay: boolean) => void
  stop: () => void
  submitQuiz: (correct: boolean) => void
  beginAsk: () => boolean
  applyAnswerActions: (actions: Action[]) => void
  endAsk: () => void
}

export function createPlayerEngine(): PlayerEngine {
  const stage = shallowRef<Stage | null>(null)
  let assetUrls: Readonly<Record<string, string>> = {}
  const state = ref<PlayerState>('idle')
  const sceneIndex = ref(0)
  const segmentIndex = ref(-1)
  const segmentStartedAt = ref(0)
  const segmentDurationMs = ref(0)

  const hiddenIds = ref<Set<string>>(new Set())
  const highlightId = ref<string | null>(null)

  const quizAnswered = ref(false)
  const quizCorrect = ref<boolean | null>(null)

  const audio = new Audio()
  let timer: ReturnType<typeof setTimeout> | null = null
  let onAudioEnded: (() => void) | null = null
  let runToken = 0 // 递增令牌:seek/stop 后旧的播放循环立即失效
  let stateBeforeAsk: PlayerState = 'paused' // 提问前的状态,提问结束后据此恢复/续播
  let askResumeSegment = -1 // 提问打断时的段号(断点续播用);-1 表示无断点

  const currentScene = computed<Scene | null>(() => stage.value?.scenes[sceneIndex.value] ?? null)

  /** 页面开场视觉状态:被 reveal 引用的块初始隐藏 */
  function resetSceneVisuals(scene: Scene): void {
    const toHide = new Set<string>()
    for (const segment of scene.speech) {
      for (const action of segment.actions) {
        if (action.type === 'reveal') toHide.add(action.target.split('#')[0] as string)
      }
    }
    hiddenIds.value = toHide
    highlightId.value = null
    quizAnswered.value = false
    quizCorrect.value = null
  }

  function applyAction(action: Action): number {
    switch (action.type) {
      case 'highlight':
        highlightId.value = action.target
        return 0
      case 'reveal': {
        const blockId = action.target.split('#')[0] as string
        if (hiddenIds.value.has(blockId)) {
          const updated = new Set(hiddenIds.value)
          updated.delete(blockId)
          hiddenIds.value = updated
        }
        return 0
      }
      case 'pause':
        return action.ms
    }
  }

  function clearTimers(): void {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    audio.pause()
    if (onAudioEnded) {
      audio.removeEventListener('ended', onAudioEnded)
      onAudioEnded = null
    }
  }

  function playSegment(token: number, scene: Scene, index: number): void {
    const segment = scene.speech[index]
    if (!segment) {
      // 还没有讲稿的页:停在这页等人翻,不能一闪而过(测验、视频页各有自己的停法)
      if (index === 0 && scene.type !== 'quiz' && scene.type !== 'video') {
        state.value = 'paused'
        return
      }
      finishScene(token)
      return
    }
    segmentIndex.value = index
    segmentStartedAt.value = Date.now()
    segmentDurationMs.value = estimateDuration(segment.text)
    // 高亮只维持一段:新段开始先清
    highlightId.value = null

    let extraPause = 0
    for (const action of segment.actions) {
      extraPause += applyAction(action)
    }

    const advance = (): void => {
      if (token !== runToken) return
      const go = (): void => {
        if (token !== runToken) return
        playSegment(token, scene, index + 1)
      }
      if (extraPause > 0) timer = setTimeout(go, extraPause)
      else go()
    }

    const audioUrl = segment.audioPath ? assetUrls[segment.audioPath] : undefined
    if (audioUrl) {
      audio.src = audioUrl
      onAudioEnded = advance
      audio.addEventListener('ended', advance, { once: true })
      audio.addEventListener(
        'loadedmetadata',
        () => {
          if (token === runToken && Number.isFinite(audio.duration) && audio.duration > 0) {
            segmentDurationMs.value = Math.round(audio.duration * 1000)
          }
        },
        { once: true },
      )
      void audio.play().catch(() => {
        // 音频加载失败退回估时推进(浏览器自动播放策略等),不中断整堂课
        timer = setTimeout(advance, estimateDuration(segment.text))
      })
    } else {
      timer = setTimeout(advance, estimateDuration(segment.text))
    }
  }

  function finishScene(token: number): void {
    if (token !== runToken) return
    const scene = currentScene.value
    if (!scene) return
    if (scene.type === 'quiz' && !quizAnswered.value) {
      state.value = 'waiting_quiz'
      return
    }
    if (scene.type === 'video') {
      state.value = 'paused'
      return
    }
    if (sceneIndex.value + 1 < (stage.value?.scenes.length ?? 0)) {
      gotoScene(sceneIndex.value + 1, true)
    } else {
      state.value = 'finished'
    }
  }

  function gotoScene(index: number, autoplay: boolean): void {
    const target = stage.value?.scenes[index]
    if (!target) return
    clearTimers()
    runToken += 1
    sceneIndex.value = index
    segmentIndex.value = -1
    resetSceneVisuals(target)
    if (autoplay) {
      state.value = 'playing'
      playSegment(runToken, target, 0)
    } else {
      state.value = 'paused'
    }
  }

  function load(target: Stage, urls: Readonly<Record<string, string>>): void {
    clearTimers()
    runToken += 1
    stage.value = target
    assetUrls = urls
    sceneIndex.value = 0
    state.value = 'idle'
    const first = target.scenes[0]
    if (first) resetSceneVisuals(first)
  }

  function play(): void {
    const scene = currentScene.value
    if (!scene) return
    if (state.value === 'playing') return
    // 从头(或本页头)开始:段内断点续播不支持,重放本页
    gotoScene(sceneIndex.value, true)
  }

  function pause(): void {
    if (state.value !== 'playing') return
    clearTimers()
    runToken += 1
    state.value = 'paused'
  }

  function next(): void {
    if (sceneIndex.value + 1 < (stage.value?.scenes.length ?? 0)) {
      gotoScene(sceneIndex.value + 1, state.value === 'playing')
    }
  }

  function prev(): void {
    if (sceneIndex.value > 0) {
      gotoScene(sceneIndex.value - 1, state.value === 'playing')
    }
  }

  /**
   * 举手提问:停下讲课进入 answering 态。
   * 记住被打断的段号,提问结束若此前在播放则从该段接着讲(断点续播,段级粒度:
   * 被打断的段从头重讲,已讲完的段不重放)。返回 false 表示当前已在提问中。
   */
  function beginAsk(): boolean {
    if (state.value === 'answering') return false
    stateBeforeAsk = state.value
    askResumeSegment = state.value === 'playing' ? Math.max(0, segmentIndex.value) : -1
    clearTimers()
    runToken += 1
    state.value = 'answering'
    return true
  }

  /** 展示回答时应用其视觉动作(highlight,目标已经服务端校验) */
  function applyAnswerActions(actions: Action[]): void {
    highlightId.value = null
    for (const action of actions) {
      applyAction(action)
    }
  }

  /**
   * 提问结束:清掉回答的视觉联动。提问前在播放的,从被打断的段落续播
   * (页面视觉不重置——前面段落 reveal 出来的块保持已显示);其余状态原样恢复。
   */
  function endAsk(): void {
    if (state.value !== 'answering') return
    highlightId.value = null
    const scene = currentScene.value
    if (stateBeforeAsk === 'playing' && scene && askResumeSegment >= 0) {
      runToken += 1
      state.value = 'playing'
      playSegment(runToken, scene, askResumeSegment)
    } else {
      state.value = stateBeforeAsk
    }
    askResumeSegment = -1
  }

  /** 测验作答回调:记录结果;若正在等待作答则解除等待(讲解由视图层展示) */
  function submitQuiz(correct: boolean): void {
    quizAnswered.value = true
    quizCorrect.value = correct
    if (state.value === 'waiting_quiz') {
      state.value = 'paused'
    }
  }

  function stop(): void {
    clearTimers()
    runToken += 1
    state.value = 'idle'
  }

  return {
    stage,
    state,
    sceneIndex,
    segmentIndex,
    segmentStartedAt,
    segmentDurationMs,
    hiddenIds,
    highlightId,
    quizAnswered,
    quizCorrect,
    currentScene,
    load,
    play,
    pause,
    next,
    prev,
    gotoScene,
    stop,
    submitQuiz,
    beginAsk,
    applyAnswerActions,
    endAsk,
  }
}

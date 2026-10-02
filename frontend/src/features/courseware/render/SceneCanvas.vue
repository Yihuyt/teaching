<script setup lang="ts">
/**
 * 页面画布:调用 layoutScene 得到帧,按 1280×720 逻辑坐标绝对定位渲染,
 * 外层用 transform: scale() 适配视口宽度。
 *
 * 视觉状态(播放器驱动):
 * - hiddenIds:被 reveal 前隐藏的块
 * - highlightId:当前高亮块/条目(`blk-x` 或 `blk-x#n`)
 */
import { computed } from 'vue'
import type { Block, Scene } from '@/features/courseware/dsl'
import { layoutScene, DEFAULT_THEME } from '@/features/courseware/layout'
import type { QuizVerdict } from '@/features/courseware/render/types'
import BlockView from '@/features/courseware/render/blocks/BlockView.vue'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

const props = defineProps<{
  scene: Scene
  width: number
  /** 对象键 → 短期播放地址(图片块据此取图;缺省显示占位) */
  assetUrls?: Readonly<Record<string, string>>
  hiddenIds?: ReadonlySet<string>
  highlightId?: string | null
  quizInteractive?: boolean
  quizVerdict?: QuizVerdict | null
  quizChosen?: string[] | null
}>()

const emit = defineEmits<{ quizSubmit: [blockId: string, chosen: string[]] }>()

const t = DEFAULT_THEME

const positioned = computed(() => layoutScene(props.scene))
const scale = computed(() => props.width / t.canvas.width)

const blockMap = computed(() => {
  const map = new Map<string, Block>()
  for (const block of props.scene.blocks) {
    map.set(block.id, block)
    if (block.type === 'columns') {
      for (const col of block.children) for (const child of col) map.set(child.id, child)
    }
  }
  return map
})

const renderableFrames = computed(() =>
  positioned.value.frames.filter((f) => {
    const b = blockMap.value.get(f.blockId)
    return b !== undefined && b.type !== 'columns'
  }),
)

const highlightBlockId = computed(() => props.highlightId?.split('#')[0] ?? null)
const highlightItemIndex = computed(() => {
  const parts = props.highlightId?.split('#')
  return parts && parts.length > 1 ? Number(parts[1]) : null
})

function frameClass(blockId: string): Record<string, boolean> {
  return {
    'frame-hidden': props.hiddenIds?.has(blockId) ?? false,
    'frame-highlight': highlightBlockId.value === blockId && highlightItemIndex.value === null,
  }
}
</script>

<template>
  <div
    class="scene-canvas-viewport"
    :style="{ width: `${width}px`, height: `${(width * t.canvas.height) / t.canvas.width}px` }"
  >
    <div
      class="scene-canvas"
      :class="`preset-${positioned.preset}`"
      :style="{
        width: `${t.canvas.width}px`,
        height: `${t.canvas.height}px`,
        transform: `scale(${scale})`,
        background: t.colors.background,
        fontFamily: t.fontFamily.body,
      }"
    >
      <div
        v-if="positioned.title"
        class="scene-title"
        :style="{
          position: 'absolute',
          left: `${positioned.title.x}px`,
          top: `${positioned.title.y}px`,
          width: `${positioned.title.w}px`,
          fontSize: `${positioned.title.fontSize}px`,
          lineHeight: String(t.lineHeight),
          fontWeight: '700',
          color: t.colors.text,
          textAlign: positioned.title.align,
        }"
      >
        <InlineText :text="scene.title" />
        <div
          v-if="positioned.title.align === 'left'"
          :style="{
            marginTop: '10px',
            width: '64px',
            height: '4px',
            borderRadius: '2px',
            background: t.colors.primary,
          }"
        />
      </div>

      <div
        v-for="frame in renderableFrames"
        :key="frame.blockId"
        class="block-frame"
        :class="frameClass(frame.blockId)"
        :data-block-id="frame.blockId"
        :style="{
          position: 'absolute',
          left: `${frame.x}px`,
          top: `${frame.y}px`,
          width: `${frame.w}px`,
          height: `${frame.h}px`,
        }"
      >
        <BlockView
          :block="blockMap.get(frame.blockId)!"
          :font-scale="frame.fontScale"
          :asset-urls="assetUrls"
          :quiz-interactive="quizInteractive ?? false"
          :quiz-verdict="quizVerdict ?? null"
          :quiz-chosen="quizChosen ?? null"
          :highlight-target="highlightId ?? null"
          @quiz-submit="(id, chosen) => emit('quizSubmit', id, chosen)"
        />
      </div>

      <div v-if="positioned.overflow === 'error'" class="overflow-badge">内容超出版面</div>
    </div>
  </div>
</template>

<style scoped>
.scene-canvas-viewport {
  overflow: hidden;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(31, 35, 41, 0.12);
}

.scene-canvas {
  position: relative;
  transform-origin: top left;
}

.block-frame {
  transition:
    opacity 0.35s ease,
    background-color 0.35s ease;
}

.frame-hidden {
  opacity: 0;
}

.frame-highlight {
  background: v-bind('t.colors.highlight');
  border-radius: 8px;
  box-shadow: 0 0 0 6px v-bind('t.colors.highlight');
}

.overflow-badge {
  position: absolute;
  right: 16px;
  bottom: 16px;
  padding: 4px 12px;
  border-radius: 6px;
  background: v-bind('t.colors.verdict.wrong');
  color: #fff;
  font-size: 16px;
}
</style>

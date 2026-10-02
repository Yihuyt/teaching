<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type {
  Block,
  BlockLayout,
  BlockSize,
  BulletsBlock,
  EditOp,
  LeafBlock,
  Scene,
  PresetName,
  QuizChoiceBlock,
} from '@/features/courseware/dsl'
import { BLOCK_SIZE_LABELS, BLOCK_TYPE_LABELS, PRESET_LABELS, PRESET_NAMES, WIDGET_TYPE_LABELS, isBlocklessScene } from '@/features/courseware/dsl'
import { nextBlockId } from '@/features/courseware/editor/clipboard'

const props = defineProps<{
  scene: Scene
  assetUrls: Readonly<Record<string, string>>
  /** 有操作在途时禁用改动,避免基于过期草稿连发 */
  busy: boolean
  selectedId: string | null
}>()
const emit = defineEmits<{
  ops: [ops: EditOp[]]
  select: [blockId: string | null]
  insertImage: []
  replaceImage: [blockId: string]
  replaceInteractive: []
  replaceVideo: []
}>()

const SIZE_OPTIONS: { value: BlockSize | 'normal'; label: string }[] = (
  ['small', 'normal', 'large', 'xlarge'] as const
).map((value) => ({ value, label: BLOCK_SIZE_LABELS[value] }))

function layoutOf(blockId: string): BlockLayout | undefined {
  return props.scene.layouts?.find((l) => l.blockId === blockId)
}

function sizeOf(blockId: string): BlockSize | 'normal' {
  return layoutOf(blockId)?.size ?? 'normal'
}

function setSize(blockId: string, size: BlockSize | 'normal'): void {
  send({ op: 'set_block_size', sceneId: draft.value.id, blockId, size })
}

function unpin(blockId: string): void {
  send({ op: 'unpin_block', sceneId: draft.value.id, blockId })
}

const cards = ref<HTMLElement[]>([])
watch(
  () => props.selectedId,
  (id) => {
    if (!id) return
    void nextTick(() => {
      cards.value.find((el) => el.dataset.blockId === id)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    })
  },
)

/** 可手动新增的块类型(图片走选图对话框;图表 / 分栏 / 选择题的结构由生成产出) */
const ADDABLE: LeafBlock['type'][] = ['heading', 'paragraph', 'bullets', 'formula', 'code', 'emphasis', 'callout']

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

const draft = ref<Scene>(clone(props.scene))
watch(
  () => props.scene,
  (scene) => {
    draft.value = clone(scene)
  },
)

function send(...ops: EditOp[]): void {
  emit('ops', ops)
}

function sceneMeta(patch: { title?: string; preset?: PresetName; summary?: string }): void {
  send({ op: 'update_scene_meta', sceneId: draft.value.id, ...patch })
}

function replace(block: Block): void {
  send({ op: 'replace_block', sceneId: draft.value.id, blockId: block.id, block: clone(block) })
}

function move(index: number, dir: -1 | 1): void {
  const block = draft.value.blocks[index]
  const target = index + dir
  if (!block || target < 0 || target >= draft.value.blocks.length) return
  send({ op: 'move_block', sceneId: draft.value.id, blockId: block.id, toIndex: target })
}

function remove(block: Block): void {
  send({ op: 'delete_block', sceneId: draft.value.id, blockId: block.id })
}

function addBlock(type: LeafBlock['type']): void {
  const id = nextBlockId(draft.value, type)
  const block: Block = (() => {
    switch (type) {
      case 'heading':
        return { id, type: 'heading', level: 2 as const, text: '新标题' }
      case 'paragraph':
        return { id, type: 'paragraph', text: '新段落' }
      case 'bullets':
        return { id, type: 'bullets', items: [{ text: '新要点' }] }
      case 'formula':
        return { id, type: 'formula', latex: 'F = ma' }
      case 'code':
        return { id, type: 'code', language: 'python', code: '# 代码' }
      case 'emphasis':
        return { id, type: 'emphasis', text: '一句话结论' }
      case 'callout':
        return { id, type: 'callout', variant: 'info' as const, text: '说明文字' }
      default:
        throw new Error(`不支持手动新增的块类型: ${type}`)
    }
  })()
  send({ op: 'add_block', sceneId: draft.value.id, index: draft.value.blocks.length, block })
}

function addBulletItem(block: BulletsBlock): void {
  block.items.push({ text: '新要点' })
  replace(block)
}

function removeBulletItem(block: BulletsBlock, index: number): void {
  if (block.items.length <= 1) return
  block.items.splice(index, 1)
  replace(block)
}

/** 勾选/取消正确答案;不允许清空到零(服务端校验 answer 非空) */
function toggleAnswer(block: QuizChoiceBlock, label: string, checked: boolean): void {
  if (checked) {
    if (!block.answer.includes(label)) block.answer.push(label)
  } else {
    if (block.answer.length <= 1) return
    block.answer.splice(block.answer.indexOf(label), 1)
  }
  block.multiple = block.answer.length > 1 ? true : block.multiple
  replace(block)
}

function setOptionalText(block: { caption?: string }, value: string): void {
  if (value) block.caption = value
  else delete block.caption
}
</script>

<template>
  <div class="panel-body">
    <el-form-item label="页面标题">
      <el-input v-model="draft.title" :disabled="busy" @change="sceneMeta({ title: draft.title })" />
    </el-form-item>
    <el-form-item v-if="!isBlocklessScene(draft.type)" label="布局预设">
      <el-select v-model="draft.preset" :disabled="busy" @change="sceneMeta({ preset: draft.preset })">
        <el-option
          v-for="p in PRESET_NAMES.filter((name) => (draft.type === 'quiz') === (name === 'quiz'))"
          :key="p"
          :label="PRESET_LABELS[p]"
          :value="p"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="本页概要">
      <el-input
        v-model="draft.summary"
        type="textarea"
        :rows="2"
        :disabled="busy"
        placeholder="这页要讲什么"
        @change="sceneMeta({ summary: draft.summary ?? '' })"
      />
    </el-form-item>

    <template v-if="draft.type === 'interactive'">
      <el-divider content-position="left">交互页</el-divider>
      <p class="uneditable-hint">
        组件类型:{{
          draft.interactive?.widgetType ? WIDGET_TYPE_LABELS[draft.interactive.widgetType] : '通用交互'
        }}
      </p>
      <el-button size="small" :disabled="busy" @click="emit('replaceInteractive')">替换网页</el-button>
    </template>

    <template v-else-if="draft.type === 'video'">
      <el-divider content-position="left">视频页</el-divider>
      <el-button size="small" :disabled="busy" @click="emit('replaceVideo')">换视频</el-button>
    </template>

    <template v-else>
      <el-divider content-position="left">内容块</el-divider>

      <div
        v-for="(block, i) in draft.blocks"
        :key="block.id"
        ref="cards"
        class="block-card"
        :class="{ selected: block.id === selectedId }"
        :data-block-id="block.id"
        @click="emit('select', block.id)"
      >
        <div class="block-card-header">
          <el-tag size="small">{{ BLOCK_TYPE_LABELS[block.type] }}</el-tag>
          <el-tag v-if="layoutOf(block.id)?.frame" size="small" type="warning" effect="plain">已钉住</el-tag>
          <span class="spacer" />
          <el-select
            :model-value="sizeOf(block.id)"
            size="small"
            class="size-select"
            :disabled="busy"
            title="字号档"
            @update:model-value="(v: BlockSize | 'normal') => setSize(block.id, v)"
          >
            <el-option v-for="o in SIZE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-button v-if="layoutOf(block.id)?.frame" text size="small" :disabled="busy" @click.stop="unpin(block.id)">
            恢复自动
          </el-button>
          <el-button text size="small" :disabled="busy || i === 0" @click="move(i, -1)">↑</el-button>
          <el-button text size="small" :disabled="busy || i === draft.blocks.length - 1" @click="move(i, 1)">
            ↓
          </el-button>
          <el-button text size="small" type="danger" :disabled="busy" @click="remove(block)">删</el-button>
        </div>

        <template v-if="block.type === 'heading'">
          <el-input v-model="block.text" :disabled="busy" @change="replace(block)" />
        </template>

        <template v-else-if="block.type === 'paragraph'">
          <el-input v-model="block.text" type="textarea" :rows="2" :disabled="busy" @change="replace(block)" />
        </template>

        <template v-else-if="block.type === 'bullets'">
          <div v-for="(item, ii) in block.items" :key="ii" class="bullet-row">
            <el-input v-model="item.text" :disabled="busy" @change="replace(block)" />
            <el-button
              text
              size="small"
              type="danger"
              :disabled="busy || block.items.length <= 1"
              @click="removeBulletItem(block, ii)"
            >
              −
            </el-button>
          </div>
          <el-button text size="small" :disabled="busy" @click="addBulletItem(block)">+ 加条目</el-button>
        </template>

        <template v-else-if="block.type === 'formula'">
          <el-input v-model="block.latex" :disabled="busy" @change="replace(block)">
            <template #prepend>LaTeX</template>
          </el-input>
        </template>

        <template v-else-if="block.type === 'code'">
          <el-input v-model="block.language" class="mb8" :disabled="busy" @change="replace(block)">
            <template #prepend>语言</template>
          </el-input>
          <el-input v-model="block.code" type="textarea" :rows="5" :disabled="busy" @change="replace(block)" />
        </template>

        <template v-else-if="block.type === 'emphasis'">
          <el-input v-model="block.text" class="mb8" :disabled="busy" @change="replace(block)" />
          <el-input
            :model-value="block.caption ?? ''"
            placeholder="说明(可选)"
            :disabled="busy"
            @update:model-value="(v: string) => setOptionalText(block, v)"
            @change="replace(block)"
          />
        </template>

        <template v-else-if="block.type === 'callout'">
          <el-select v-model="block.variant" class="mb8" :disabled="busy" @change="replace(block)">
            <el-option label="说明" value="info" />
            <el-option label="提示" value="tip" />
            <el-option label="注意" value="warning" />
            <el-option label="结论" value="conclusion" />
          </el-select>
          <el-input v-model="block.text" type="textarea" :rows="2" :disabled="busy" @change="replace(block)" />
        </template>

        <template v-else-if="block.type === 'table'">
          <div class="mini-table">
            <div class="mini-row">
              <el-input
                v-for="(_, hi) in block.headers"
                :key="hi"
                v-model="block.headers[hi]"
                size="small"
                :disabled="busy"
                @change="replace(block)"
              />
            </div>
            <div v-for="(row, ri) in block.rows" :key="ri" class="mini-row">
              <el-input
                v-for="(_, ci) in row"
                :key="ci"
                v-model="row[ci]"
                size="small"
                :disabled="busy"
                @change="replace(block)"
              />
            </div>
          </div>
        </template>

        <template v-else-if="block.type === 'quiz_choice'">
          <el-input v-model="block.stem" type="textarea" :rows="2" class="mb8" :disabled="busy" @change="replace(block)" />
          <div v-for="option in block.options" :key="option.label" class="bullet-row">
            <el-checkbox
              size="small"
              :disabled="busy"
              :model-value="block.answer.includes(option.label)"
              @update:model-value="(v: boolean | string | number) => toggleAnswer(block, option.label, Boolean(v))"
            >
              {{ option.label }}
            </el-checkbox>
            <el-input v-model="option.text" size="small" :disabled="busy" @change="replace(block)" />
          </div>
          <div class="answer-hint">勾选正确答案</div>
          <el-input
            v-model="block.explanation"
            type="textarea"
            :rows="2"
            class="mb8"
            placeholder="讲解"
            :disabled="busy"
            @change="replace(block)"
          />
        </template>

        <template v-else-if="block.type === 'image'">
          <img v-if="assetUrls[block.src]" :src="assetUrls[block.src]" :alt="block.caption ?? ''" class="image-preview" />
          <el-input
            :model-value="block.caption ?? ''"
            placeholder="图片说明(可选)"
            :disabled="busy"
            @update:model-value="(v: string) => setOptionalText(block, v)"
            @change="replace(block)"
          />
          <div class="image-actions">
            <el-button size="small" :disabled="busy" @click.stop="emit('replaceImage', block.id)">换图</el-button>
          </div>
        </template>

        <template v-else>
          <div class="uneditable-hint">图表与分栏不支持手工编辑</div>
        </template>
      </div>

      <el-dropdown trigger="click" class="add-block" :disabled="busy">
        <el-button :disabled="busy">+ 添加块</el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="type in ADDABLE" :key="type" @click="addBlock(type)">
              {{ BLOCK_TYPE_LABELS[type] }}
            </el-dropdown-item>
            <el-dropdown-item divided @click="emit('insertImage')">图片</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </template>
  </div>
</template>

<style scoped>
.image-preview {
  display: block;
  max-width: 100%;
  max-height: 120px;
  margin-bottom: 8px;
  border-radius: 4px;
}

.panel-body {
  padding: 12px 0 24px;
}

.block-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  padding: 10px;
  margin-bottom: 10px;
}

.block-card.selected {
  border-color: var(--brand);
  box-shadow: 0 0 0 1px var(--brand-soft);
}

.size-select {
  width: 84px;
}

.image-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.block-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.spacer {
  flex: 1;
}

.bullet-row {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 6px;
}

.mb8 {
  margin-bottom: 8px;
}

.mini-table {
  display: grid;
  gap: 4px;
}

.mini-row {
  display: flex;
  gap: 4px;
}

.answer-hint {
  font-size: 12px;
  color: var(--text-muted);
  margin: 4px 0 8px;
}

.uneditable-hint {
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-muted);
}

.add-block {
  margin-top: 8px;
}
</style>

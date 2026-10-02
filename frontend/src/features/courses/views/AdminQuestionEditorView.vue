<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Delete, Plus } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'
import CourseQuestionFormFields from '@/features/courses/components/CourseQuestionFormFields.vue'
import { useUnsavedGuard } from '@/shared/composables/useUnsavedGuard'
import { questionTypeLabels } from '@/shared/labels'
import {
  emptyQuestionItemForm,
  emptyQuestionPaperForm,
  paperTotalScore,
  questionItemFormFrom,
  questionPaperFormFrom,
  questionPaperPayload,
  type CourseQuestionTypeValue,
  type QuestionPaperForm,
} from '@/features/courses/question'

const route = useRoute()
const sourceTab = route.query.from === 'outline' ? 'outline' : 'resources'
const router = useRouter()
const courseId = computed(() => Number(route.params.courseId))
const questionId = computed(() => {
  const raw = route.params.questionId
  return typeof raw === 'string' && raw ? Number(raw) : null
})
const routeValid = computed(
  () =>
    Number.isSafeInteger(courseId.value) &&
    courseId.value > 0 &&
    (questionId.value === null || (Number.isSafeInteger(questionId.value) && questionId.value > 0)),
)

const loading = ref(true)
const loadError = ref('')
const form = ref<QuestionPaperForm>(emptyQuestionPaperForm())
const current = ref(0)
const saving = ref(false)
const saveError = ref('')
let snapshot = ''

const totalScore = computed(() => paperTotalScore(form.value.items))
const currentItem = computed(() => form.value.items[current.value] ?? null)
const timed = computed({
  get: () => form.value.timeLimitMinutes !== null,
  set: (value: boolean) => {
    form.value.timeLimitMinutes = value ? 30 : null
  },
})

const contentLocked = ref(false)
/** 当前表单对应的试题 id(新建为 null);保存后换址时用来跳过重新装载 */
const loadedQuestionId = ref<number | null>(null)

function markClean(): void {
  snapshot = JSON.stringify(form.value)
}

function isDirty(): boolean {
  return JSON.stringify(form.value) !== snapshot
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  if (!routeValid.value) {
    loadError.value = '无效的试题地址'
    loading.value = false
    return
  }
  try {
    if (questionId.value !== null) {
      const detail = (await api.courseQuestionGetForManagement(courseId.value, questionId.value)).data
      form.value = questionPaperFormFrom(detail)
      contentLocked.value = detail.contentLocked
      markClean()
    } else {
      form.value = emptyQuestionPaperForm()
      contentLocked.value = false
      // 空表单为基线:AI 出题经 history.state 带来的草稿算未保存修改,离开前要确认
      markClean()
      const state = window.history.state as { draftItems?: unknown; draftTitle?: unknown } | null
      if (Array.isArray(state?.draftItems)) {
        form.value.items = (state.draftItems as Parameters<typeof questionItemFormFrom>[0][]).map(
          questionItemFormFrom,
        )
      }
      if (typeof state?.draftTitle === 'string') {
        form.value.title = state.draftTitle
      }
    }
    loadedQuestionId.value = questionId.value
    current.value = 0
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function addItem(type: CourseQuestionTypeValue): void {
  form.value.items.push(emptyQuestionItemForm(type))
  current.value = form.value.items.length - 1
}

function removeItem(index: number): void {
  form.value.items.splice(index, 1)
  // 删除当前题之前的题时选中序号同步左移,否则编辑面板会悄悄切到别的题
  if (index < current.value) current.value -= 1
  else if (current.value >= form.value.items.length) current.value = Math.max(0, form.value.items.length - 1)
}

function moveItem(index: number, offset: -1 | 1): void {
  const target = index + offset
  if (target < 0 || target >= form.value.items.length) return
  const items = form.value.items
  const [moving] = items.splice(index, 1)
  if (moving) items.splice(target, 0, moving)
  if (current.value === index) current.value = target
  else if (current.value === target) current.value = index
}

function itemSummary(index: number): string {
  const item = form.value.items[index]
  if (!item) return ''
  const stem = item.stemMarkdown.trim().replace(/\s+/g, ' ')
  return stem ? stem.slice(0, 40) : '（未填写题干）'
}

async function save(): Promise<void> {
  let body: ReturnType<typeof questionPaperPayload>
  try {
    body = questionPaperPayload(form.value)
  } catch (error: unknown) {
    saveError.value = errorMessage(error)
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    if (questionId.value !== null) {
      const detail = (await api.courseQuestionUpdate(courseId.value, questionId.value, body)).data
      form.value = questionPaperFormFrom(detail)
      contentLocked.value = detail.contentLocked
    } else {
      const detail = (await api.courseQuestionCreate(courseId.value, body)).data
      form.value = questionPaperFormFrom(detail)
      contentLocked.value = detail.contentLocked
      // 新建保存后停留在编辑页(与编程题编辑器一致),地址换成该试题的编辑地址;
      // 换址是一次路由离开,先把基线标为已保存,守卫也放行到本题的编辑地址
      loadedQuestionId.value = detail.id
      markClean()
      await router.replace({
        path: `/focus/admin/courses/${courseId.value}/questions/${detail.id}/edit`,
        query: route.query,
      })
    }
    current.value = Math.min(current.value, Math.max(0, form.value.items.length - 1))
    markClean()
    ElMessage.success('已保存')
  } catch (error: unknown) {
    saveError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function back(): Promise<void> {
  await router.push(`/admin/courses/${courseId.value}?tab=${sourceTab}`)
}

useUnsavedGuard(isDirty, {
  allowTo: (to) =>
    to.name === 'admin-question-edit' && Number(to.params.questionId) === loadedQuestionId.value,
})

onMounted(() => {
  void load()
})
// 同一路由记录之间前进 / 后退只换参数不重建组件:参数变了要重新装载;新建保存后的换址不算
watch([courseId, questionId], () => {
  if (questionId.value !== loadedQuestionId.value) void load()
})
</script>

<template>
  <div class="editor">
    <header class="editor-header">
      <el-button link :icon="ArrowLeft" @click="back">{{
        sourceTab === 'outline' ? '返回课程内容' : '返回资料库'
      }}</el-button>
      <el-input
        v-model="form.title"
        class="title-input"
        maxlength="255"
        placeholder="试题标题"
        aria-label="试题标题"
        :disabled="loading || loadError !== ''"
      />
      <div class="header-actions">
        <span class="summary">{{ form.items.length }} 题 · 总分 {{ totalScore }}</span>
        <el-button type="primary" :loading="saving" :disabled="loading || loadError !== ''" @click="save">
          保存
        </el-button>
      </div>
    </header>

    <div v-if="loading" class="editor-loading">加载中…</div>
    <div v-else-if="loadError" class="editor-loading">{{ loadError }}</div>
    <template v-else>
      <div class="settings-bar">
        <div class="setting">
          <span class="setting-label">限时</span>
          <el-switch v-model="timed" />
          <template v-if="timed">
            <el-input-number
              v-model="form.timeLimitMinutes"
              :min="1"
              :max="600"
              :step="5"
              controls-position="right"
            />
            <span class="setting-hint">分钟</span>
          </template>
        </div>
        <div class="setting">
          <span class="setting-label">允许重做</span>
          <el-switch v-model="form.allowRetake" />
        </div>
        <div class="setting">
          <span class="setting-label">交卷后公开答案</span>
          <el-switch v-model="form.revealAnswers" />
        </div>
      </div>

      <el-alert
        v-if="contentLocked"
        title="已有学生作答，题目内容锁定，只可修改标题与作答设置"
        type="info"
        :closable="false"
        show-icon
        class="save-alert"
      />
      <el-alert
        v-if="saveError"
        :title="saveError"
        type="error"
        :closable="false"
        show-icon
        class="save-alert"
      />

      <div class="editor-body">
        <aside class="item-nav">
          <div class="item-nav__add">
            <el-button
              v-for="(label, type) in questionTypeLabels"
              :key="type"
              size="small"
              :icon="Plus"
              :disabled="contentLocked"
              @click="addItem(type)"
            >
              {{ label }}
            </el-button>
          </div>
          <p v-if="form.items.length === 0" class="item-nav__empty">暂无题目</p>
          <ol class="item-nav__list">
            <li v-for="(item, index) in form.items" :key="index" :class="{ active: index === current }">
              <button
                type="button"
                class="item-nav__select"
                :aria-current="index === current ? 'true' : undefined"
                @click="current = index"
              >
                <span class="item-nav__no">{{ index + 1 }}</span>
                <span class="item-nav__main">
                  <span class="item-nav__meta"
                    >{{ questionTypeLabels[item.type] }} · {{ item.score }} 分</span
                  >
                  <span class="item-nav__stem">{{ itemSummary(index) }}</span>
                </span>
              </button>
              <span class="item-nav__ops">
                <el-button
                  link
                  size="small"
                  :disabled="contentLocked || index === 0"
                  :aria-label="`上移第 ${index + 1} 题`"
                  @click="moveItem(index, -1)"
                  >↑</el-button
                >
                <el-button
                  link
                  size="small"
                  :disabled="contentLocked || index === form.items.length - 1"
                  :aria-label="`下移第 ${index + 1} 题`"
                  @click="moveItem(index, 1)"
                >
                  ↓
                </el-button>
                <el-button
                  link
                  size="small"
                  type="danger"
                  :icon="Delete"
                  :disabled="contentLocked"
                  :aria-label="`删除第 ${index + 1} 题`"
                  @click="removeItem(index)"
                />
              </span>
            </li>
          </ol>
        </aside>

        <main class="item-editor">
          <template v-if="currentItem">
            <h3 class="item-editor__title">第 {{ current + 1 }} 题</h3>
            <CourseQuestionFormFields v-model="form.items[current]!" :disabled="contentLocked" />
          </template>
          <div v-else class="item-editor__empty">请选择题目</div>
        </main>
      </div>
    </template>
  </div>
</template>

<style scoped>
.editor {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f6f8;
}

.editor-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.title-input {
  flex: 1;
  max-width: 640px;
}

.title-input :deep(.el-input__wrapper) {
  box-shadow: none;
  background: transparent;
  padding-left: 0;
}

.title-input :deep(.el-input__inner) {
  font-size: 18px;
  font-weight: 600;
}

.header-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 16px;
}

.summary {
  color: var(--text-secondary);
  font-size: 13px;
}

.settings-bar {
  display: flex;
  gap: 36px;
  padding: 10px 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.setting {
  display: flex;
  align-items: center;
  gap: 10px;
}

.setting-label {
  font-size: 13px;
  font-weight: 600;
}

.setting-hint {
  color: var(--text-secondary);
  font-size: 12px;
}

.save-alert {
  margin: 12px 24px 0;
}

.editor-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  padding: 16px 24px 24px;
}

.item-nav {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
}

.item-nav__add {
  display: flex;
  gap: 6px;
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.item-nav__empty {
  margin: 24px 12px;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

.item-nav__list {
  list-style: none;
  margin: 0;
  padding: 8px;
  overflow-y: auto;
}

.item-nav__list li {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px;
  border-radius: 8px;
}

.item-nav__select {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: flex-start;
  gap: 10px;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.item-nav__list li:hover {
  background: var(--surface-muted);
}

.item-nav__list li.active {
  background: var(--el-color-primary-light-9);
}

.item-nav__no {
  flex: 0 0 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: var(--el-fill-color);
  font-size: 12px;
  font-weight: 600;
}

li.active .item-nav__no {
  background: var(--el-color-primary);
  color: #fff;
}

.item-nav__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.item-nav__meta {
  color: var(--text-secondary);
  font-size: 12px;
}

.item-nav__stem {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.item-nav__ops {
  display: none;
  flex: 0 0 auto;
}

.item-nav__list li:hover .item-nav__ops,
.item-nav__list li.active .item-nav__ops {
  display: flex;
}

.item-editor {
  min-height: 0;
  overflow-y: auto;
  padding: 20px 28px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
}

.item-editor__title {
  margin: 0 0 16px;
  font-size: 16px;
}

.item-editor__empty {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}

.editor-loading {
  padding: 48px 0;
  text-align: center;
  color: var(--text-muted);
}
</style>

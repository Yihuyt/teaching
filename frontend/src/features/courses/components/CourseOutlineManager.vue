<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { api, errorMessage } from '@/api/client'
import type { CourseOutlineUnitView, CourseOutlineItemView, CourseOutlineView } from '@/api/generated'
import type { LibraryContent } from '@/features/courses/library'
import { openInNewTab } from '@/shared/openUrl'
import AsyncState from '@/shared/components/AsyncState.vue'
import CourseOutlineUnitNode from '@/features/courses/components/CourseOutlineUnitNode.vue'
import OutlineContentPicker from '@/features/courses/components/OutlineContentPicker.vue'
import OutlineItemRow from '@/features/courses/components/OutlineItemRow.vue'
import OutlineResultsDialog from '@/features/courses/components/OutlineResultsDialog.vue'
import { useOutlineItemDnd } from '@/features/courses/useOutlineItemDnd'
import {
  cloneOutline,
  findUnitSiblings,
  outlineOrderRequest,
  moveOutlineItem,
  placeBeside,
  sortOutline,
} from '@/features/courses/courseOutline'
import { deletionPrompt } from '@/shared/deletion'
import { confirm } from '@/shared/dialogs'
import { outlineDrag } from '@/features/courses/outlineDrag'
import { createLatestRequestGuard } from '@/shared/latestRequest'

const props = defineProps<{ courseId: number }>()

const loading = ref(true)
const loadError = ref('')
const outline = ref<CourseOutlineView>({ items: [], units: [] })
const savingOrder = ref(false)
const unitDialogVisible = ref(false)
const savingUnit = ref(false)
const editingUnit = ref<CourseOutlineUnitView>()
const newUnitParentId = ref<number | null>(null)
const unitForm = reactive({ title: '' })

const itemDialogVisible = ref(false)
const itemDialogUnitId = ref<number | null>(null)
const savingItem = ref(false)
const busy = computed(() => savingOrder.value || savingItem.value)
const topItemDnd = useOutlineItemDnd(
  () => null,
  () => busy.value,
  (itemId, targetItemId, after) => void moveItem(null, itemId, targetItemId, after),
)
const topDropActive = ref(false)
const loadedCourseId = ref<number>()

const outlineRequests = createLatestRequestGuard(() => props.courseId)
const unitSaveRequests = createLatestRequestGuard(() => props.courseId)
const unitRemoveRequests = createLatestRequestGuard(() => props.courseId)
const orderRequests = createLatestRequestGuard(() => props.courseId)
const itemMutationRequests = createLatestRequestGuard(() => props.courseId)

/** 换课程才显示骨架屏;同一课程的重新加载保留现有内容(单元展开状态、滚动位置不丢) */
async function load(): Promise<void> {
  const request = outlineRequests.begin()
  loading.value = loadedCourseId.value !== request.snapshot
  loadError.value = ''
  if (loading.value) outline.value = { items: [], units: [] }
  try {
    const response = await api.courseOutlineForManagement(request.snapshot)
    if (!outlineRequests.isCurrent(request)) return
    outline.value = sortOutline(response.data)
    loadedCourseId.value = request.snapshot
  } catch (error: unknown) {
    if (!outlineRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (outlineRequests.isCurrent(request)) loading.value = false
  }
}

function openCreateUnit(parentId: number | null): void {
  editingUnit.value = undefined
  newUnitParentId.value = parentId
  unitForm.title = ''
  unitDialogVisible.value = true
}

function openEditUnit(unit: CourseOutlineUnitView): void {
  editingUnit.value = unit
  newUnitParentId.value = null
  unitForm.title = unit.title
  unitDialogVisible.value = true
}

async function saveUnit(): Promise<void> {
  const title = unitForm.title.trim()
  if (!title) {
    ElMessage.warning('请输入单元名称')
    return
  }
  const request = unitSaveRequests.begin()
  const editing = editingUnit.value
  savingUnit.value = true
  try {
    if (editing) {
      await api.courseUpdateUnit(request.snapshot, editing.id, { title })
    } else {
      await api.courseCreateUnit(request.snapshot, { parentId: newUnitParentId.value, title })
    }
    if (!unitSaveRequests.isCurrent(request)) return
    unitDialogVisible.value = false
    await load()
    if (!unitSaveRequests.isCurrent(request)) return
    ElMessage.success(editing ? '单元已重命名' : '单元已创建')
  } catch (error: unknown) {
    if (!unitSaveRequests.isCurrent(request)) return
    ElMessage.error(errorMessage(error))
  } finally {
    if (unitSaveRequests.isCurrent(request)) savingUnit.value = false
  }
}

async function removeUnit(unit: CourseOutlineUnitView): Promise<void> {
  const associated = unit.children.length > 0 || unit.items.length > 0
  if (!(await confirm(deletionPrompt(1, associated), '删除单元'))) return
  const request = unitRemoveRequests.begin()
  try {
    await api.courseDeleteUnit(request.snapshot, unit.id)
    if (!unitRemoveRequests.isCurrent(request)) return
    await load()
    if (!unitRemoveRequests.isCurrent(request)) return
    ElMessage.success('单元已删除')
  } catch (error: unknown) {
    if (!unitRemoveRequests.isCurrent(request)) return
    ElMessage.error(errorMessage(error))
  }
}

function sameOrder(previous: CourseOutlineView): boolean {
  return JSON.stringify(outlineOrderRequest(previous)) === JSON.stringify(outlineOrderRequest(outline.value))
}

async function persistOrder(previous: CourseOutlineView, successMessage: string): Promise<void> {
  const request = orderRequests.begin()
  savingOrder.value = true
  try {
    const response = await api.courseReplaceOutlineOrder(request.snapshot, outlineOrderRequest(outline.value))
    if (!orderRequests.isCurrent(request)) return
    outline.value = sortOutline(response.data)
    ElMessage.success(successMessage)
  } catch (error: unknown) {
    if (!orderRequests.isCurrent(request)) return
    outline.value = previous
    ElMessage.error(errorMessage(error))
  } finally {
    if (orderRequests.isCurrent(request)) savingOrder.value = false
  }
}

async function reorderUnit(unitId: number, targetUnitId: number, after: boolean): Promise<void> {
  if (savingOrder.value) return
  const previous = cloneOutline(outline.value)
  const siblings = findUnitSiblings(outline.value.units, unitId)
  if (!siblings || !placeBeside(siblings, unitId, targetUnitId, after)) return
  if (sameOrder(previous)) return
  await persistOrder(previous, '单元顺序已更新')
}

/** 统一的条目移动:toUnitId 为 null = 顶层;targetItemId 为 null = 追加到组末尾 */
async function moveItem(
  toUnitId: number | null,
  itemId: number,
  targetItemId: number | null,
  after: boolean,
): Promise<void> {
  if (savingOrder.value) return
  const previous = cloneOutline(outline.value)
  if (!moveOutlineItem(outline.value, itemId, toUnitId, targetItemId, after)) return
  if (sameOrder(previous)) return
  await persistOrder(previous, '内容顺序已更新')
}

function nudgeTopItem(item: CourseOutlineItemView, direction: -1 | 1): void {
  const siblings = outline.value.items
  const target = siblings[siblings.findIndex((candidate) => candidate.id === item.id) + direction]
  if (target) void moveItem(null, item.id, target.id, direction > 0)
}

function nudgeTopUnit(unitId: number, direction: -1 | 1): void {
  const siblings = outline.value.units
  const target = siblings[siblings.findIndex((candidate) => candidate.id === unitId) + direction]
  if (target) void reorderUnit(unitId, target.id, direction > 0)
}

function overTopZone(event: DragEvent): void {
  if (outlineDrag.current?.kind !== 'item' || savingOrder.value) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  topDropActive.value = true
}

function dropOnTopZone(event: DragEvent): void {
  const dragging = outlineDrag.current
  if (dragging?.kind !== 'item' || savingOrder.value) return
  event.preventDefault()
  topDropActive.value = false
  outlineDrag.current = null
  void moveItem(null, dragging.id, null, true)
}

function openAddItem(unit: CourseOutlineUnitView | null): void {
  itemDialogUnitId.value = unit === null ? null : unit.id
  itemDialogVisible.value = true
}

async function addPicked(items: LibraryContent[]): Promise<void> {
  if (savingItem.value) return
  const unitId = itemDialogUnitId.value
  const request = itemMutationRequests.begin()
  savingItem.value = true
  const failures: string[] = []
  try {
    // 逐条顺序加入:单元内的序号按加入顺序分配,并发会打乱顺序
    await items.reduce(
      (chain, item) =>
        chain.then(async () => {
          if (!itemMutationRequests.isCurrent(request)) return
          try {
            await api.courseAddOutlineItem(request.snapshot, {
              unitId,
              itemType: item.itemType,
              contentId: item.contentId,
            })
          } catch (error: unknown) {
            failures.push(`“${item.title}”：${errorMessage(error)}`)
          }
        }),
      Promise.resolve(),
    )
    if (!itemMutationRequests.isCurrent(request)) return
    await load()
    if (!itemMutationRequests.isCurrent(request)) return
    if (failures.length === 0) ElMessage.success(`已添加 ${items.length} 项`)
    else ElMessage.error(`${items.length - failures.length} 项已添加；${failures.join('；')}`)
  } finally {
    if (itemMutationRequests.isCurrent(request)) savingItem.value = false
  }
}

const router = useRouter()
const resultsVisible = ref(false)
const resultsItem = ref<CourseOutlineItemView>()

function openResults(item: CourseOutlineItemView): void {
  resultsItem.value = item
  resultsVisible.value = true
}

async function openItem(item: CourseOutlineItemView): Promise<void> {
  if (item.itemType === 'question') {
    await router.push(`/focus/admin/courses/${props.courseId}/questions/${item.contentId}/edit?from=outline`)
    return
  }
  if (item.itemType === 'programming_problem') {
    await router.push(
      `/focus/admin/courses/${props.courseId}/programming-problems/${item.contentId}/edit?from=outline`,
    )
    return
  }
  try {
    await openInNewTab(
      async () =>
        (await api.courseMaterialCreateDownloadTicketForManagement(props.courseId, item.contentId)).data.url,
    )
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

async function removeItem(item: CourseOutlineItemView): Promise<void> {
  if (!(await confirm('确认移除？', '移除'))) return
  const request = itemMutationRequests.begin()
  try {
    await api.courseRemoveOutlineItem(request.snapshot, item.id)
    if (!itemMutationRequests.isCurrent(request)) return
    await load()
    if (!itemMutationRequests.isCurrent(request)) return
    ElMessage.success('已移除')
  } catch (error: unknown) {
    if (!itemMutationRequests.isCurrent(request)) return
    ElMessage.error(errorMessage(error))
  }
}

watch(
  () => props.courseId,
  () => {
    unitSaveRequests.invalidate()
    unitRemoveRequests.invalidate()
    orderRequests.invalidate()
    itemMutationRequests.invalidate()

    itemDialogVisible.value = false
    savingItem.value = false
    resultsVisible.value = false
    resultsItem.value = undefined

    unitDialogVisible.value = false
    savingUnit.value = false
    editingUnit.value = undefined
    newUnitParentId.value = null

    savingOrder.value = false

    void load()
  },
  { immediate: true },
)
</script>

<template>
  <section class="outline-manager">
    <div v-if="!loading && !loadError" class="manager-actions">
      <el-button :icon="Plus" :disabled="busy" @click="openAddItem(null)">添加内容</el-button>
      <el-button type="primary" :icon="Plus" :disabled="busy" @click="openCreateUnit(null)">
        新建单元
      </el-button>
    </div>

    <AsyncState :loading="loading" :error="loadError" :empty="false" @retry="load">
      <div v-if="outline.units.length === 0 && outline.items.length === 0" class="outline-empty">
        内容为空
      </div>

      <section v-else class="outline-units" aria-label="课程内容">
        <div
          v-if="outline.items.length === 0 && outlineDrag.current?.kind === 'item'"
          class="top-drop-zone"
          :class="{ 'top-drop-zone--active': topDropActive }"
          @dragenter="overTopZone"
          @dragover="overTopZone"
          @dragleave="topDropActive = false"
          @drop="dropOnTopZone"
        >
          移动到顶层
        </div>
        <div v-if="outline.items.length" class="top-item-list">
          <OutlineItemRow
            v-for="item in outline.items"
            :key="item.id"
            :item="item"
            :busy="busy"
            :state-class="topItemDnd.dropClass(item)"
            @open="openItem"
            @results="openResults"
            @remove="removeItem"
            @drag-start="topItemDnd.startDrag"
            @nudge="nudgeTopItem"
            @drag-end="topItemDnd.endDrag"
            @enter="topItemDnd.enter"
            @over="topItemDnd.over"
            @leave="topItemDnd.leave"
            @drop="topItemDnd.drop"
          />
        </div>
        <CourseOutlineUnitNode
          v-for="unit in outline.units"
          :key="unit.id"
          :unit="unit"
          :parent-id="null"
          :depth="0"
          :busy="busy"
          @add-item="openAddItem"
          @create-child="openCreateUnit"
          @edit-unit="openEditUnit"
          @remove-unit="removeUnit"
          @open-item="openItem"
          @results-item="openResults"
          @remove-item="removeItem"
          @reorder-unit="reorderUnit"
          @nudge-unit="nudgeTopUnit"
          @reorder-item="moveItem"
        />
      </section>
    </AsyncState>

    <el-dialog v-model="unitDialogVisible" :title="editingUnit ? '重命名单元' : '新建单元'" width="620px">
      <el-form :model="unitForm" label-position="top" @submit.prevent="saveUnit">
        <el-form-item label="单元名称" required>
          <el-input v-model="unitForm.title" maxlength="128" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="unitDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingUnit" @click="saveUnit">保存</el-button>
      </template>
    </el-dialog>

    <OutlineContentPicker v-model="itemDialogVisible" :course-id="courseId" :busy="busy" @add="addPicked" />
    <OutlineResultsDialog v-model="resultsVisible" :course-id="courseId" :item="resultsItem" />
  </section>
</template>

<style scoped>
.top-drop-zone {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  margin: 10px 12px;
  border: 1px dashed var(--border);
  border-radius: 6px;
  color: var(--text-muted);
  font-size: 13px;
}

.top-drop-zone--active {
  border-color: var(--brand);
  color: var(--brand);
}

.top-item-list {
  border-bottom: 1px solid var(--border);
}

.top-item-list :deep(.item-row:first-child) {
  border-top: 0;
}

.outline-empty {
  display: flex;
  min-height: 180px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface-muted);
  color: var(--text-muted);
  font-size: 14px;
}

.manager-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 14px;
}

.outline-units {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
}
</style>

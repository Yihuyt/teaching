<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { MemberView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import { createLatestRequestGuard } from '@/shared/latestRequest'
import { roleLabels } from '@/shared/labels'

const props = defineProps<{ courseId: number; joinCode: string }>()
const emit = defineEmits<{ 'join-code-updated': [joinCode: string] }>()

const loading = ref(true)
const rotating = ref(false)
const loadError = ref('')
const members = ref<MemberView[]>([])
const currentJoinCode = ref(props.joinCode)
const loadRequests = createLatestRequestGuard(() => props.courseId)

async function load(): Promise<void> {
  const request = loadRequests.begin()
  loading.value = true
  loadError.value = ''
  members.value = []
  try {
    const response = await api.courseMembers(request.snapshot)
    if (!loadRequests.isCurrent(request)) return
    members.value = response.data
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) {
      loading.value = false
    }
  }
}

async function copyJoinCode(): Promise<void> {
  try {
    await navigator.clipboard.writeText(currentJoinCode.value)
    ElMessage.success('课程码已复制')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

async function rotateJoinCode(): Promise<void> {
  if (!(await confirm('更新后，原课程码立即失效。已经加入课程的成员不会受影响。', '更新课程码'))) return
  rotating.value = true
  try {
    const response = await api.courseRotateJoinCode(props.courseId)
    currentJoinCode.value = response.data.joinCode
    emit('join-code-updated', response.data.joinCode)
    ElMessage.success('课程码已更新')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    rotating.value = false
  }
}

async function remove(member: MemberView): Promise<void> {
  if (!(await confirm(`确定移除课程成员“${member.displayName}”吗？`, '移除成员'))) return
  try {
    await api.courseRemoveMember(props.courseId, member.accountId)
    await load()
    ElMessage.success('课程成员已移除')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

watch(
  () => [props.courseId, props.joinCode] as const,
  ([, joinCode]) => {
    currentJoinCode.value = joinCode
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <section>
    <div class="join-code-card">
      <div>
        <span>课程码</span>
        <strong>{{ currentJoinCode }}</strong>
      </div>
      <div class="join-code-actions">
        <el-button @click="copyJoinCode">复制</el-button>
        <el-button :loading="rotating" @click="rotateJoinCode">更新课程码</el-button>
      </div>
    </div>
    <AsyncState
      :loading="loading"
      :error="loadError"
      :empty="members.length === 0"
      empty-text="暂无课程成员"
      @retry="load"
    >
      <el-table :data="members">
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="displayName" label="显示名称" />
        <el-table-column label="角色" width="120">
          <template #default="{ row }: { row: MemberView }">
            {{ roleLabels[row.role] }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }: { row: MemberView }">
            <el-button link type="danger" @click="remove(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </AsyncState>
  </section>
</template>

<style scoped>
.join-code-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface-muted);
}

.join-code-card div {
  display: flex;
  align-items: baseline;
  gap: 16px;
}

.join-code-card span {
  color: var(--text-secondary);
}

.join-code-card strong {
  color: var(--text);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 24px;
  letter-spacing: 0.12em;
}

.join-code-card small {
  color: var(--text-muted);
  font-size: 13px;
}

.join-code-actions {
  display: flex;
  gap: 8px;
}
</style>

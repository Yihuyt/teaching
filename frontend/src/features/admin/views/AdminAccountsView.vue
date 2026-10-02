<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import { AccountViewRole, type AccountView, type CreateAccountRequest } from '@/api/generated'
import { canCreateAccountRole, canManageAccountRole } from '@/features/admin/accountAdministration'
import AsyncState from '@/shared/components/AsyncState.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import { formatDateTime } from '@/shared/format'
import { roleLabels } from '@/shared/labels'
import { useSessionStore } from '@/stores/session'

const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const formError = ref('')
const items = ref<AccountView[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, keyword: '' })
const dialogVisible = ref(false)
const resetVisible = ref(false)
const editing = ref<AccountView>()
const resetting = ref<AccountView>()
const resetPassword = ref('')
const form = reactive<CreateAccountRequest>({
  username: '',
  initialPassword: '',
  role: AccountViewRole.student,
  displayName: '',
})

const session = useSessionStore()
if (!session.account) {
  throw new Error('账户管理页面要求已登录的平台管理员')
}
const actorRole = session.account.role
const assignableRoles = [
  { value: AccountViewRole.admin, label: '管理员' },
  { value: AccountViewRole.teacher, label: '教师' },
  { value: AccountViewRole.student, label: '学生' },
].filter((role) => canCreateAccountRole(actorRole, role.value))

function canManage(account: AccountView): boolean {
  return canManageAccountRole(actorRole, account.role)
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.accountList(query)
    items.value = response.data.items
    total.value = response.data.total
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function search(): void {
  query.page = 1
  void load()
}

function openCreate(): void {
  editing.value = undefined
  Object.assign(form, {
    username: '',
    initialPassword: '',
    role: AccountViewRole.student,
    displayName: '',
  })
  formError.value = ''
  dialogVisible.value = true
}

function openEdit(account: AccountView): void {
  if (!canManage(account)) {
    return
  }
  editing.value = account
  Object.assign(form, {
    username: account.username,
    initialPassword: '',
    role: account.role,
    displayName: account.displayName,
  })
  formError.value = ''
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!editing.value && !/^[a-z0-9._-]{3,32}$/.test(form.username)) {
    formError.value = '用户名只能包含小写字母、数字、点、下划线和连字符，长度为 3 至 32'
    return
  }
  if (
    !form.username.trim() ||
    !form.displayName.trim() ||
    (!editing.value && (form.initialPassword.length < 6 || form.initialPassword.length > 128))
  ) {
    formError.value = editing.value
      ? '请完整填写用户名和显示名称'
      : '请完整填写账户信息，初始密码长度为 6 至 128 个字符'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    if (editing.value) {
      await api.accountUpdateProfile(editing.value.id, {
        displayName: form.displayName,
      })
      ElMessage.success('账户资料已更新')
    } else {
      await api.accountCreate({ ...form })
      ElMessage.success('账户已创建，用户首次登录后必须修改初始密码')
    }
    dialogVisible.value = false
    await load()
  } catch (error: unknown) {
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function toggleStatus(account: AccountView): Promise<void> {
  if (
    !(await confirm(
      account.enabled ? `确定停用账户“${account.username}”吗？` : `确定启用账户“${account.username}”吗？`,
      account.enabled ? '停用账户' : '启用账户',
    ))
  )
    return
  try {
    await api.accountSetEnabled(account.id, { enabled: !account.enabled })
    ElMessage.success(account.enabled ? '账户已停用' : '账户已启用')
    await load()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

function openReset(account: AccountView): void {
  resetting.value = account
  resetPassword.value = ''
  formError.value = ''
  resetVisible.value = true
}

async function submitReset(): Promise<void> {
  if (!resetting.value || resetPassword.value.length < 6 || resetPassword.value.length > 128) {
    formError.value = '新密码长度必须为 6 至 128 个字符'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    await api.accountResetPassword(resetting.value.id, { initialPassword: resetPassword.value })
    resetVisible.value = false
    ElMessage.success('密码已重置')
    await load()
  } catch (error: unknown) {
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="账户管理">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openCreate">创建账户</el-button>
      </template>
    </PageHeader>
    <section class="panel panel-body">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="搜索用户名或姓名"
          style="width: 360px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #append><el-button :icon="Search" @click="search" /></template>
        </el-input>
        <span class="muted">共 {{ total }} 个账户</span>
      </div>
      <AsyncState
        :loading="loading"
        :error="loadError"
        :empty="items.length === 0"
        empty-text="暂无账户"
        @retry="load"
      >
        <el-table :data="items">
          <el-table-column prop="username" label="用户名" min-width="180" />
          <el-table-column prop="displayName" label="姓名" min-width="180" />
          <el-table-column label="角色" width="120">
            <template #default="{ row }: { row: AccountView }">{{ roleLabels[row.role] }}</template>
          </el-table-column>
          <el-table-column label="状态" width="140">
            <template #default="{ row }: { row: AccountView }">
              <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '正常' : '已停用' }}</el-tag>
              <el-tag v-if="row.mustResetPassword" type="warning" class="reset-tag">待改密</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="190">
            <template #default="{ row }: { row: AccountView }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="240" align="right">
            <template #default="{ row }: { row: AccountView }">
              <template v-if="canManage(row)">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
                <el-button link :type="row.enabled ? 'danger' : 'success'" @click="toggleStatus(row)">
                  {{ row.enabled ? '停用' : '启用' }}
                </el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          layout="total, sizes, prev, pager, next"
          class="pagination"
          @current-change="load"
          @size-change="search"
        />
      </AsyncState>
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑账户' : '创建账户'" width="560px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="dialog-alert" />
      <el-form :model="form" label-position="top">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="Boolean(editing)" minlength="3" maxlength="32" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.displayName" maxlength="64" />
        </el-form-item>
        <el-form-item v-if="!editing" label="初始密码" required>
          <el-input
            v-model="form.initialPassword"
            type="password"
            minlength="6"
            maxlength="128"
            show-password
          />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.role" :disabled="Boolean(editing)" style="width: 100%">
            <el-option
              v-for="role in assignableRoles"
              :key="role.value"
              :label="role.label"
              :value="role.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetVisible" title="重置密码" width="520px">
      <p class="muted">账户：{{ resetting?.username }}</p>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="dialog-alert" />
      <el-form label-position="top">
        <el-form-item label="新密码" required>
          <el-input v-model="resetPassword" type="password" minlength="6" maxlength="128" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReset">确认设置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.reset-tag {
  margin-left: 6px;
}

.pagination {
  justify-content: flex-end;
  margin-top: 20px;
}

.dialog-alert {
  margin-bottom: 16px;
}
</style>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { toUpdateProfileRequest, type AccountProfileForm } from '@/features/account/profileForm'
import { api, errorMessage } from '@/api/client'
import type { ChangePasswordRequest } from '@/api/generated'
import AiServiceSettings from '@/features/account/components/AiServiceSettings.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const profileFormRef = ref<FormInstance>()
const passwordFormRef = ref<FormInstance>()
const savingProfile = ref(false)
const savingPassword = ref(false)
const profileError = ref('')
const passwordError = ref('')
const activeTab = ref(route.path.endsWith('/password') ? 'password' : 'profile')

if (!session.account) {
  throw new Error('账户页面需要登录会话')
}

const profile = reactive<AccountProfileForm>({
  displayName: session.account.displayName,
})

const password = reactive<ChangePasswordRequest & { confirmation: string }>({
  currentPassword: '',
  newPassword: '',
  confirmation: '',
})

const profileRules: FormRules<AccountProfileForm> = {
  displayName: [
    { required: true, whitespace: true, message: '请输入显示名称', trigger: 'blur' },
    { min: 1, max: 64, message: '显示名称长度应为 1 至 64 个字符', trigger: 'blur' },
  ],
}

const passwordRules: FormRules<typeof password> = {
  currentPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '新密码至少需要 6 个字符', trigger: 'blur' },
  ],
  confirmation: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== password.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

const account = computed(() => session.account)

watch(activeTab, async (tab) => {
  const target = tab === 'password' ? '/account/password' : '/account'
  if (route.path !== target) {
    await router.replace(target)
  }
})

async function saveProfile(): Promise<void> {
  if (!(await profileFormRef.value?.validate()) || !session.account) {
    return
  }
  savingProfile.value = true
  profileError.value = ''
  try {
    const response = await api.accountUpdateProfile(session.account.id, toUpdateProfileRequest(profile))
    session.account = response.data
    ElMessage.success('账户资料已保存')
  } catch (error: unknown) {
    profileError.value = errorMessage(error)
  } finally {
    savingProfile.value = false
  }
}

async function savePassword(): Promise<void> {
  if (!(await passwordFormRef.value?.validate())) {
    return
  }
  savingPassword.value = true
  passwordError.value = ''
  try {
    await session.changePassword({
      currentPassword: password.currentPassword,
      newPassword: password.newPassword,
    })
    await router.replace({ name: 'login', query: { notice: 'password-changed' } })
  } catch (error: unknown) {
    passwordError.value = errorMessage(error)
  } finally {
    savingPassword.value = false
  }
}
</script>

<template>
  <div class="page account-page">
    <PageHeader title="账户设置" />
    <el-alert
      v-if="account?.mustResetPassword"
      title="首次登录，请先修改密码"
      type="warning"
      :closable="false"
      show-icon
      class="password-alert"
    />
    <section class="panel account-panel">
      <div class="account-content">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="个人资料" name="profile">
            <el-alert
              v-if="profileError"
              :title="profileError"
              type="error"
              :closable="false"
              class="form-alert"
            />
            <el-form
              ref="profileFormRef"
              :model="profile"
              :rules="profileRules"
              label-position="top"
              class="account-form"
            >
              <el-form-item label="用户名">
                <el-input :model-value="account?.username" disabled />
              </el-form-item>
              <el-form-item label="显示名称" prop="displayName">
                <el-input v-model="profile.displayName" maxlength="64" show-word-limit />
              </el-form-item>
              <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存资料</el-button>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="修改密码" name="password">
            <el-alert
              v-if="passwordError"
              :title="passwordError"
              type="error"
              :closable="false"
              class="form-alert"
            />
            <el-form
              ref="passwordFormRef"
              :model="password"
              :rules="passwordRules"
              label-position="top"
              class="account-form"
            >
              <el-form-item label="当前密码" prop="currentPassword">
                <el-input v-model="password.currentPassword" type="password" show-password />
              </el-form-item>
              <el-form-item label="新密码" prop="newPassword">
                <el-input
                  v-model="password.newPassword"
                  type="password"
                  minlength="6"
                  maxlength="128"
                  show-password
                />
              </el-form-item>
              <el-form-item label="确认新密码" prop="confirmation">
                <el-input
                  v-model="password.confirmation"
                  type="password"
                  minlength="6"
                  maxlength="128"
                  show-password
                />
              </el-form-item>
              <el-button type="primary" :loading="savingPassword" @click="savePassword"> 修改密码 </el-button>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="AI 服务" name="ai">
            <div class="ai-tab-body">
              <AiServiceSettings />
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </section>
  </div>
</template>

<style scoped>
.account-page {
  max-width: 1100px;
}

.password-alert {
  margin-bottom: 20px;
}

.account-panel {
  width: 760px;
}

.account-content {
  padding: 12px 32px 32px;
}

.account-content :deep(.el-tabs__item) {
  height: 54px;
  font-weight: 600;
}

.account-form {
  width: 560px;
  padding-top: 20px;
}

.form-alert {
  width: 560px;
  margin-top: 18px;
}

.ai-tab-body {
  padding-top: 20px;
}
</style>

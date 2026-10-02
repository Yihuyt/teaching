<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'

import { errorMessage } from '@/api/client'
import type { LoginRequest } from '@/api/generated'
import PlatformFooter from '@/shared/components/PlatformFooter.vue'
import { usePlatformStore } from '@/stores/platform'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const platform = usePlatformStore()
const session = useSessionStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const submitError = ref('')
const form = reactive<LoginRequest>({
  username: '',
  password: '',
})

const rules: FormRules<LoginRequest> = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const redirectTarget = computed(() => {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/home'
})
const notice = computed(() => (route.query.notice === 'password-changed' ? '密码已修改，请重新登录' : ''))

async function submit(): Promise<void> {
  if (!(await formRef.value?.validate())) {
    return
  }
  submitting.value = true
  submitError.value = ''
  try {
    const account = await session.login(form)
    await router.replace(account.mustResetPassword ? '/account/password' : redirectTarget.value)
  } catch (error: unknown) {
    submitError.value = errorMessage(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-shell">
    <main class="login-page">
      <section class="login-card">
        <p class="login-card__brand">{{ platform.siteName }}</p>
        <h1>登录</h1>
        <el-alert
          v-if="notice"
          :title="notice"
          type="success"
          :closable="false"
          show-icon
          class="login-notice"
        />
        <el-alert
          v-if="submitError"
          :title="submitError"
          type="error"
          :closable="false"
          show-icon
          class="login-error"
        />
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" autocomplete="username" placeholder="请输入用户名" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              autocomplete="current-password"
              show-password
              placeholder="请输入密码"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-button type="primary" :loading="submitting" class="login-submit" @click="submit">
            登录
          </el-button>
        </el-form>
        <p class="login-card__notice">没有账户请联系老师或管理员。</p>
      </section>
    </main>
    <PlatformFooter />
  </div>
</template>

<style scoped>
.login-shell {
  min-height: 100vh;
  background: #f5f6f8;
}

.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 48px);
  padding: 64px;
}

.login-card {
  width: 420px;
  padding: 34px 36px 30px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: #fff;
}

.login-card__brand {
  margin: 0 0 28px;
  color: var(--brand);
  font-size: 16px;
  font-weight: 700;
}

.login-card h1 {
  margin: 0 0 28px;
  font-size: 27px;
}

.login-error {
  margin-bottom: 20px;
}

.login-notice {
  margin-bottom: 20px;
}

.login-submit {
  width: 100%;
  margin-top: 8px;
}

.login-card__notice {
  margin: 20px 0 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.6;
}
</style>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Collection, HomeFilled, Management, User } from '@element-plus/icons-vue'

import BrandMark from '@/shared/components/BrandMark.vue'
import PlatformFooter from '@/shared/components/PlatformFooter.vue'
import { usePlatformStore } from '@/stores/platform'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const platform = usePlatformStore()
const session = useSessionStore()

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/courses')) return '/courses'
  return '/home'
})

async function handleCommand(command: string): Promise<void> {
  if (command === 'logout') {
    await session.logout()
    await router.replace({ name: 'login' })
    return
  }
  await router.push(command)
}
</script>

<template>
  <div class="portal-layout">
    <header class="topbar">
      <div class="topbar__inner">
        <router-link class="brand" to="/home" :aria-label="`${platform.siteName}首页`">
          <BrandMark />
          <span>{{ platform.siteName }}</span>
        </router-link>

        <el-menu :default-active="activeMenu" mode="horizontal" router :ellipsis="false" class="topbar__menu">
          <el-menu-item index="/home"
            ><el-icon><HomeFilled /></el-icon>首页</el-menu-item
          >
          <el-menu-item index="/courses"
            ><el-icon><Collection /></el-icon>课程</el-menu-item
          >
        </el-menu>

        <div class="topbar__account">
          <el-button v-if="session.canEnterManagement" text @click="router.push('/admin/courses')">
            <el-icon><Management /></el-icon>
            管理后台
          </el-button>
          <el-dropdown trigger="click" @command="handleCommand">
            <button class="account-trigger" type="button">
              <span class="avatar">{{ session.account?.displayName.slice(0, 1) }}</span>
              <strong>{{ session.account?.displayName }}</strong>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="/account">
                  <el-icon><User /></el-icon>账户设置
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </header>
    <main>
      <router-view />
    </main>
    <PlatformFooter />
  </div>
</template>

<style scoped>
.portal-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.portal-layout > main {
  flex: 1;
}

.topbar {
  position: sticky;
  z-index: 50;
  top: 0;
  height: 64px;
  border-bottom: 1px solid var(--border);
  background: rgb(255 255 255 / 98%);
}

.topbar__inner {
  display: grid;
  grid-template-columns: 210px minmax(300px, 1fr) auto;
  align-items: center;
  width: calc(100% - var(--page-gutter) * 2);
  height: 100%;
  margin: 0 auto;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  color: var(--text);
  font-size: 17px;
  font-weight: 650;
}

.brand > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar__menu {
  min-width: 300px;
  height: 63px;
  border-bottom: 0;
  background: transparent;
}

.topbar__menu :deep(.el-menu-item) {
  height: 63px;
  padding: 0 18px;
  font-weight: 500;
}

.topbar__account {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  white-space: nowrap;
}

.account-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--radius-control);
  color: var(--text);
  background: transparent;
  cursor: pointer;
}

.account-trigger:hover {
  background: var(--surface-muted);
}

.account-trigger strong {
  display: block;
  max-width: 112px;
  overflow: hidden;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.avatar {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  background: var(--brand);
  font-weight: 600;
}
</style>

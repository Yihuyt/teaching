<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Bell, Collection, HomeFilled, Setting, User } from '@element-plus/icons-vue'

import BrandMark from '@/shared/components/BrandMark.vue'
import PlatformFooter from '@/shared/components/PlatformFooter.vue'
import { usePlatformStore } from '@/stores/platform'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const router = useRouter()
const platform = usePlatformStore()
const session = useSessionStore()
const activeMenu = computed(() => {
  if (route.path.startsWith('/admin/courses')) return '/admin/courses'
  return route.path
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
  <div class="admin-layout">
    <aside class="admin-sidebar">
      <router-link to="/admin/courses" class="admin-brand">
        <BrandMark />
        <div>
          <strong>{{ platform.siteName }}</strong>
          <small v-if="session.isPlatformAdmin">管理后台</small>
        </div>
      </router-link>
      <el-menu :default-active="activeMenu" router class="admin-menu">
        <el-menu-item v-if="session.isPlatformAdmin" index="/admin/accounts">
          <el-icon><User /></el-icon>账户管理
        </el-menu-item>
        <el-menu-item index="/admin/courses"
          ><el-icon><Collection /></el-icon>课程管理</el-menu-item
        >
        <el-menu-item v-if="session.isPlatformAdmin" index="/admin/announcements">
          <el-icon><Bell /></el-icon>公告管理
        </el-menu-item>
        <el-menu-item v-if="session.isRoot" index="/admin/platform">
          <el-icon><Setting /></el-icon>设置
        </el-menu-item>
      </el-menu>
    </aside>
    <section class="admin-main">
      <header class="admin-topbar">
        <el-dropdown trigger="click" @command="handleCommand">
          <button class="account-trigger" type="button">
            <span class="avatar">{{ session.account?.displayName.slice(0, 1) }}</span>
            <strong>{{ session.account?.displayName }}</strong>
            <el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="/home">
                <el-icon><HomeFilled /></el-icon>返回教学平台
              </el-dropdown-item>
              <el-dropdown-item command="/account">
                <el-icon><User /></el-icon>账户设置
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>
      <main class="admin-content">
        <router-view />
      </main>
      <PlatformFooter />
    </section>
  </div>
</template>

<style scoped>
.admin-layout {
  display: grid;
  grid-template-columns: 220px minmax(980px, 1fr);
  min-height: 100vh;
  background: var(--el-bg-color-page);
}

.admin-sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  border-right: 1px solid var(--border);
  background: #fff;
}

.admin-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 64px;
  padding: 0 18px;
  border-bottom: 1px solid var(--border);
  color: var(--text);
}

.admin-brand strong,
.admin-brand small {
  display: block;
}

.admin-brand strong {
  max-width: 138px;
  overflow: hidden;
  font-size: 15px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-brand small {
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 12px;
}

.admin-menu {
  padding: 10px;
  border-right: 0;
}

.admin-menu :deep(.el-menu-item) {
  height: 48px;
  margin: 2px 0;
  border-radius: var(--radius-control);
}

.admin-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 100vh;
}

.admin-topbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  height: 56px;
  padding: 0 24px;
  border-bottom: 1px solid var(--border);
  background: #fff;
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
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.avatar {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  background: var(--brand);
  font-weight: 600;
}

.admin-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  padding: 24px;
}

/* 子页面根节点撑满内容区,便于列表类页面把面板拉到底 */
.admin-content > * {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
</style>

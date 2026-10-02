<script setup lang="ts">
import { watch } from 'vue'
import { RouterView } from 'vue-router'
import { useRoute } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import { usePlatformStore } from '@/stores/platform'

const route = useRoute()
const platform = usePlatformStore()

watch(
  [() => route.meta.title, () => platform.settings?.siteName],
  ([pageTitle, siteName]) => {
    const title = String(pageTitle)
    document.title = siteName ? `${title} - ${siteName}` : title
  },
  { immediate: true },
)
</script>

<template>
  <el-config-provider :locale="zhCn">
    <RouterView />
  </el-config-provider>
</template>

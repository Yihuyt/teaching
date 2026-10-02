<script setup lang="ts">
import { computed } from 'vue'
import type { Scene } from '@/features/courseware/dsl'

const props = defineProps<{ scene: Scene; assetUrls: Readonly<Record<string, string>> }>()

const url = computed(() => (props.scene.video ? props.assetUrls[props.scene.video.src] : undefined))
</script>

<template>
  <div class="video-host">
    <video v-if="url" class="video-el" :src="url" controls preload="metadata" />
    <p v-else class="video-missing">视频地址暂不可用,请刷新后重试</p>
  </div>
</template>

<style scoped>
.video-host {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: #000;
}

.video-el {
  max-width: 100%;
  max-height: 100%;
}

.video-missing {
  margin: 0;
  color: #ccc;
  font-size: 14px;
}
</style>

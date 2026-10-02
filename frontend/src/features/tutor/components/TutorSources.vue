<script setup lang="ts">
import type { TutorSource } from '@/features/tutor/tutorStream'

defineProps<{ sources: TutorSource[]; highlight?: string | null }>()
</script>

<template>
  <div v-if="sources.length" class="sources">
    <div
      v-for="source in sources"
      :id="`tutor-src-${source.ref}`"
      :key="source.ref"
      class="source"
      :class="{ 'source--highlight': highlight === source.ref }"
    >
      <el-tag size="small" effect="plain" type="info">
        {{ source.ref.replace('source-', '') }}
      </el-tag>
      <span class="source-title">{{ source.documentName }}</span>
      <span v-if="source.section" class="source-section">› {{ source.section }}</span>
      <span class="source-kb">{{ source.kbName }}</span>
      <p class="source-snippet">{{ source.snippet }}</p>
    </div>
  </div>
</template>

<style scoped>
.sources {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.source {
  padding: 6px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  font-size: 12px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  transition: background 0.2s;
}

.source--highlight {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
}

.source-title {
  font-weight: 600;
}

.source-section,
.source-kb {
  color: var(--el-text-color-secondary);
}

.source-kb {
  margin-left: auto;
}

.source-snippet {
  flex-basis: 100%;
  margin: 0;
  color: var(--el-text-color-regular);
}
</style>

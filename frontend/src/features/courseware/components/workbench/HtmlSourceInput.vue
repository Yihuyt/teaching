<script setup lang="ts">
/**
 * 交互页网页的录入:粘贴整份 HTML,或选一个 .html 文件读进来。格式校验在服务端落库时做。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const model = defineModel<string>({ required: true })
defineProps<{ disabled?: boolean }>()

const MAX_BYTES = 1024 * 1024
const fileInput = ref<HTMLInputElement | null>(null)

function pickFile(): void {
  fileInput.value?.click()
}

async function onFilePicked(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > MAX_BYTES) {
    ElMessage.warning('网页文件不能超过 1MB')
    return
  }
  model.value = await file.text()
}
</script>

<template>
  <div class="html-source">
    <el-input
      v-model="model"
      type="textarea"
      :rows="10"
      resize="vertical"
      :disabled="disabled"
      class="html-textarea"
      placeholder="粘贴整份 HTML(从 <!DOCTYPE html> 到 </html>)…"
    />
    <div class="html-actions">
      <input ref="fileInput" type="file" accept=".html,.htm,text/html" hidden @change="onFilePicked" />
      <el-button size="small" :disabled="disabled" @click="pickFile">选择 .html 文件</el-button>
      <span class="html-hint">
        外部脚本与样式只能来自 cdn.jsdelivr.net、unpkg.com、cdnjs.cloudflare.com
      </span>
    </div>
  </div>
</template>

<style scoped>
.html-source {
  width: 100%;
}

.html-textarea :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
}

.html-actions {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 6px;
}

.html-hint {
  flex: 1;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
</style>

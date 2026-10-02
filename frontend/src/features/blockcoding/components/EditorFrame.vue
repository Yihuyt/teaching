<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import { errorMessage } from '@/api/client'
import type { ScratchBridge } from '@/features/blockcoding/scratchBridge'

const props = defineProps<{
  bridge: ScratchBridge
}>()

const emit = defineEmits<{
  ready: []
  dirty: []
  failed: [message: string]
}>()

const frame = ref<HTMLIFrameElement | null>(null)

onMounted(() => {
  if (!frame.value) {
    return
  }
  props.bridge.attach(frame.value, () => emit('dirty'))
  props.bridge
    .waitReady()
    .then(() => emit('ready'))
    .catch((error: unknown) => emit('failed', errorMessage(error)))
})

onBeforeUnmount(() => {
  props.bridge.detach()
})
</script>

<template>
  <iframe
    ref="frame"
    class="editor-frame"
    src="/scratch/teaching.html?locale=zh-cn"
    title="积木编辑器"
    allow="autoplay; camera; microphone"
  ></iframe>
</template>

<style scoped>
.editor-frame {
  width: 100%;
  height: 100%;
  border: 0;
  background: #fff;
}
</style>

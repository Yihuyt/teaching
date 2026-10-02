<script setup lang="ts">
/**
 * 交互仿真宿主:沙箱 iframe(allow-scripts,无 same-origin),学生自主操作。
 * HTML 注入错误捕获垫片,运行错误经 postMessage 回传,展示在角标上。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { Scene } from '@/features/courseware/dsl'

const props = defineProps<{ scene: Scene }>()

const frame = ref<HTMLIFrameElement | null>(null)
const runtimeErrors = ref<string[]>([])

/** SFC 的 script 块不能出现字面 script 闭合标签,拼接生成 */
const SCRIPT_CLOSE = `</${'script'}>`

const ERROR_SHIM = `<script>
(function () {
  function report(kind, message) {
    try {
      parent.postMessage({ coursewareShim: true, kind: 'runtime-error', errorKind: kind, message: String(message).slice(0, 500) }, '*');
    } catch (e) {}
  }
  window.addEventListener('error', function (e) { report('error', e.message); });
  window.addEventListener('unhandledrejection', function (e) { report('rejection', e.reason); });
})();
${SCRIPT_CLOSE}`

/** 沙箱内 localStorage 会抛异常,注入内存垫片 */
const STORAGE_SHIM = `<script>
(function () {
  function memoryStorage() {
    var data = {};
    return {
      getItem: function (k) { return Object.prototype.hasOwnProperty.call(data, k) ? data[k] : null; },
      setItem: function (k, v) { data[k] = String(v); },
      removeItem: function (k) { delete data[k]; },
      clear: function () { data = {}; },
      key: function (i) { return Object.keys(data)[i] || null; },
      get length() { return Object.keys(data).length; }
    };
  }
  try { window.localStorage.length; } catch (e) {
    Object.defineProperty(window, 'localStorage', { value: memoryStorage() });
    Object.defineProperty(window, 'sessionStorage', { value: memoryStorage() });
  }
})();
${SCRIPT_CLOSE}`

const srcDoc = computed(() => {
  const html = props.scene.interactive?.html ?? ''
  const shims = ERROR_SHIM + STORAGE_SHIM
  // 垫片注入 <head> 起始处,确保先于页面脚本执行
  if (/<head[^>]*>/i.test(html)) {
    return html.replace(/<head[^>]*>/i, (m) => `${m}\n${shims}`)
  }
  return shims + html
})

function onMessage(event: MessageEvent): void {
  const data = event.data as { coursewareShim?: boolean; kind?: string; message?: string } | null
  if (!data || data.coursewareShim !== true || data.kind !== 'runtime-error') return
  if (event.source !== frame.value?.contentWindow) return
  runtimeErrors.value.push(data.message ?? '未知错误')
}

onMounted(() => {
  window.addEventListener('message', onMessage)
})

onBeforeUnmount(() => {
  window.removeEventListener('message', onMessage)
})
</script>

<template>
  <div class="interactive-host">
    <iframe
      ref="frame"
      class="widget-frame"
      sandbox="allow-scripts allow-forms"
      :srcdoc="srcDoc"
      title="交互仿真"
    />
    <el-tooltip v-if="runtimeErrors.length" :content="runtimeErrors.join('\n')" placement="top">
      <div class="error-badge">仿真出错 ×{{ runtimeErrors.length }}</div>
    </el-tooltip>
  </div>
</template>

<style scoped>
.interactive-host {
  position: relative;
  width: 100%;
  height: 100%;
}

.widget-frame {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}

.error-badge {
  position: absolute;
  right: 12px;
  bottom: 12px;
  padding: 4px 10px;
  border-radius: var(--radius-control);
  background: var(--danger);
  color: #fff;
  font-size: 13px;
  cursor: default;
}
</style>

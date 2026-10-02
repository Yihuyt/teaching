import { ref, watch, type Ref } from 'vue'

const KEY = 'kg-inspector-width'
export const INSPECTOR_MIN = 280
export const INSPECTOR_MAX = 720

export function useInspectorWidth(): Ref<number> {
  const stored = Number(localStorage.getItem(KEY))
  const width = ref(stored >= INSPECTOR_MIN && stored <= INSPECTOR_MAX ? stored : 440)
  watch(width, (value) => localStorage.setItem(KEY, String(Math.round(value))))
  return width
}

import { ref, type Ref } from 'vue'

import type { CourseOutlineItemView } from '@/api/generated'
import { outlineDrag } from '@/features/courses/outlineDrag'

interface ItemDropTarget {
  id: number
  after: boolean
}

export function placeAfter(event: DragEvent): boolean {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  return event.clientY - rect.top > rect.height / 2
}

function accepts(item: CourseOutlineItemView): boolean {
  const dragging = outlineDrag.current
  return dragging !== null && dragging.kind === 'item' && dragging.id !== item.id
}

export function useOutlineItemDnd(
  unitId: () => number | null,
  savingOrder: () => boolean,
  reorder: (itemId: number, targetItemId: number, after: boolean) => void,
) {
  const dropTarget: Ref<ItemDropTarget | null> = ref(null)
  /** dragenter / dragleave 在子元素间移动时成对触发:计数归零才算真正离开 */
  let hovering = 0

  function startDrag(event: DragEvent, item: CourseOutlineItemView): void {
    if (savingOrder()) {
      event.preventDefault()
      return
    }
    outlineDrag.current = { kind: 'item', id: item.id, unitId: unitId() }
    event.dataTransfer?.setData('text/plain', `item:${item.id}`)
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
  }

  function endDrag(): void {
    outlineDrag.current = null
    dropTarget.value = null
    hovering = 0
  }

  function enter(event: DragEvent, item: CourseOutlineItemView): void {
    if (!accepts(item)) return
    hovering += 1
    over(event, item)
  }

  function over(event: DragEvent, item: CourseOutlineItemView): void {
    if (!accepts(item)) return
    event.preventDefault()
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
    dropTarget.value = { id: item.id, after: placeAfter(event) }
  }

  function leave(): void {
    hovering = Math.max(0, hovering - 1)
    if (hovering === 0) dropTarget.value = null
  }

  function drop(event: DragEvent, item: CourseOutlineItemView): void {
    const dragging = outlineDrag.current
    if (!accepts(item) || dragging === null) return
    event.preventDefault()
    reorder(dragging.id, item.id, placeAfter(event))
    endDrag()
  }

  function dropClass(item: CourseOutlineItemView): Record<string, boolean> {
    const target = dropTarget.value
    const active = target !== null && target.id === item.id
    return { 'drop-before': active && !target.after, 'drop-after': active && target.after }
  }

  return { startDrag, endDrag, enter, over, leave, drop, dropClass }
}

import type {
  CourseOutlineUnitView,
  CourseOutlineItemView,
  CourseOutlineItemViewItemType,
  CourseOutlineView,
  ReplaceOutlineOrderRequest,
} from '@/api/generated'

export function visitOutlineItems(
  units: CourseOutlineUnitView[],
  visitor: (item: CourseOutlineItemView, unit: CourseOutlineUnitView) => void,
): void {
  for (const unit of units) {
    for (const item of unit.items) visitor(item, unit)
    visitOutlineItems(unit.children, visitor)
  }
}

function sortUnit(unit: CourseOutlineUnitView): CourseOutlineUnitView {
  return {
    ...unit,
    children: unit.children.toSorted((left, right) => left.position - right.position).map(sortUnit),
    items: unit.items.toSorted((left, right) => left.position - right.position),
  }
}

/** 按 position 递归排序(服务端已排好,这里只是不依赖数组顺序的契约) */
export function sortOutline(value: CourseOutlineView): CourseOutlineView {
  return {
    items: value.items.toSorted((left, right) => left.position - right.position),
    units: value.units.toSorted((left, right) => left.position - right.position).map(sortUnit),
  }
}

function cloneUnit(unit: CourseOutlineUnitView): CourseOutlineUnitView {
  return {
    ...unit,
    children: unit.children.map(cloneUnit),
    items: unit.items.map((item) => ({ ...item })),
  }
}

export function cloneOutline(value: CourseOutlineView): CourseOutlineView {
  return { items: value.items.map((item) => ({ ...item })), units: value.units.map(cloneUnit) }
}

export function findUnit(units: CourseOutlineUnitView[], unitId: number): CourseOutlineUnitView | undefined {
  for (const unit of units) {
    if (unit.id === unitId) return unit
    const found = findUnit(unit.children, unitId)
    if (found) return found
  }
  return undefined
}

export function findUnitSiblings(
  units: CourseOutlineUnitView[],
  unitId: number,
): CourseOutlineUnitView[] | undefined {
  if (units.some((unit) => unit.id === unitId)) return units
  for (const unit of units) {
    const found = findUnitSiblings(unit.children, unitId)
    if (found) return found
  }
  return undefined
}

/**
 * 把条目移动到目标组(toUnitId 为 null = 顶层)中 targetItemId 之前 / 之后;
 * targetItemId 为 null 时追加到组末尾。原地修改,目标不存在或自移时返回 false。
 */
export function moveOutlineItem(
  outline: CourseOutlineView,
  itemId: number,
  toUnitId: number | null,
  targetItemId: number | null,
  after: boolean,
): boolean {
  if (itemId === targetItemId) return false
  const destination = toUnitId === null ? outline.items : findUnit(outline.units, toUnitId)?.items
  if (!destination) return false
  const source = listContaining(outline, itemId)
  if (!source) return false
  if (targetItemId !== null && !destination.some((entry) => entry.id === targetItemId)) return false
  const item = source.splice(
    source.findIndex((entry) => entry.id === itemId),
    1,
  )[0]!
  if (targetItemId === null) {
    destination.push(item)
    return true
  }
  const targetIndex = destination.findIndex((entry) => entry.id === targetItemId)
  destination.splice(after ? targetIndex + 1 : targetIndex, 0, item)
  return true
}

function listContaining(outline: CourseOutlineView, itemId: number): CourseOutlineItemView[] | null {
  if (outline.items.some((entry) => entry.id === itemId)) return outline.items
  let found: CourseOutlineItemView[] | null = null
  visitOutlineItems(outline.units, (entry, owner) => {
    if (entry.id === itemId) found = owner.items
  })
  return found
}

export function placeBeside<T extends { id: number }>(
  list: T[],
  movingId: number,
  targetId: number,
  after: boolean,
): boolean {
  const from = list.findIndex((entry) => entry.id === movingId)
  const targetIndex = list.findIndex((entry) => entry.id === targetId)
  if (from < 0 || targetIndex < 0 || from === targetIndex) return false
  const [moving] = list.splice(from, 1)
  if (!moving) return false
  const base = list.findIndex((entry) => entry.id === targetId)
  list.splice(after ? base + 1 : base, 0, moving)
  return true
}

/** 当前树的完整顺序(顶层内容 unitId 为 null;单元与内容的 position 都从 1 连续编号) */
export function outlineOrderRequest(value: CourseOutlineView): ReplaceOutlineOrderRequest {
  const units: ReplaceOutlineOrderRequest['units'] = []
  const items: ReplaceOutlineOrderRequest['items'] = []
  value.items.forEach((item, index) => {
    items.push({ itemId: item.id, unitId: null, position: index + 1 })
  })
  function visit(nodes: CourseOutlineUnitView[], parentId: number | null): void {
    nodes.forEach((unit, unitIndex) => {
      units.push({ unitId: unit.id, parentId, position: unitIndex + 1 })
      unit.items.forEach((item, itemIndex) => {
        items.push({ itemId: item.id, unitId: unit.id, position: itemIndex + 1 })
      })
      visit(unit.children, unit.id)
    })
  }
  visit(value.units, null)
  return { units, items }
}

export function contentKey(itemType: CourseOutlineItemViewItemType, contentId: number): string {
  return `${itemType}-${contentId}`
}

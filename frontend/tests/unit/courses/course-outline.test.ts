import type { CourseOutlineUnitView, CourseOutlineView } from '@/api/generated'
import {
  cloneOutline,
  findUnit,
  findUnitSiblings,
  moveOutlineItem,
  outlineOrderRequest,
  placeBeside,
  sortOutline,
  visitOutlineItems,
} from '@/features/courses/courseOutline'

function unit(
  id: number,
  position: number,
  children: CourseOutlineUnitView[] = [],
  items: CourseOutlineUnitView['items'] = [],
): CourseOutlineUnitView {
  return { id, title: `单元 ${id}`, position, children, items }
}

const outline: CourseOutlineView = {
  items: [
    { id: 202, itemType: 'material', contentId: 30, title: '顶层附件', position: 2 },
    { id: 201, itemType: 'question', contentId: 31, title: '顶层测验', position: 1 },
  ],
  units: [
    unit(2, 2, [], [{ id: 21, itemType: 'question', contentId: 5, title: '测验', position: 1 }]),
    unit(
      1,
      1,
      [unit(12, 2), unit(11, 1)],
      [
        { id: 102, itemType: 'material', contentId: 9, title: '讲义', position: 2 },
        { id: 101, itemType: 'programming_problem', contentId: 7, title: 'A+B', position: 1 },
      ],
    ),
  ],
}

describe('sortOutline', () => {
  it('按 position 递归排序单元与内容', () => {
    const sorted = sortOutline(outline)
    expect(sorted.items.map((i) => i.id)).toEqual([201, 202])
    expect(sorted.units.map((c) => c.id)).toEqual([1, 2])
    expect(sorted.units[0]!.children.map((c) => c.id)).toEqual([11, 12])
    expect(sorted.units[0]!.items.map((i) => i.id)).toEqual([101, 102])
  })

  it('不修改原对象', () => {
    sortOutline(outline)
    expect(outline.units[0]!.id).toBe(2)
  })
})

describe('cloneOutline', () => {
  it('深拷贝,修改副本不影响原树', () => {
    const copy = cloneOutline(outline)
    copy.items[0]!.title = '顶层改名'
    copy.units[1]!.items[0]!.title = '改了'
    copy.units[1]!.children.push(unit(99, 3))
    expect(outline.items[0]!.title).toBe('顶层附件')
    expect(outline.units[1]!.items[0]!.title).toBe('讲义')
    expect(outline.units[1]!.children).toHaveLength(2)
  })
})

describe('findUnit / findUnitSiblings', () => {
  it('能找到任意深度的单元', () => {
    expect(findUnit(outline.units, 12)?.id).toBe(12)
    expect(findUnit(outline.units, 404)).toBeUndefined()
  })

  it('顶级单元返回根数组,子单元返回其父的 children', () => {
    expect(findUnitSiblings(outline.units, 2)).toBe(outline.units)
    expect(findUnitSiblings(outline.units, 11)).toBe(outline.units[1]!.children)
    expect(findUnitSiblings(outline.units, 404)).toBeUndefined()
  })
})

describe('placeBeside', () => {
  it('移到目标之前 / 之后', () => {
    const list = [{ id: 1 }, { id: 2 }, { id: 3 }]
    expect(placeBeside(list, 3, 1, false)).toBe(true)
    expect(list.map((e) => e.id)).toEqual([3, 1, 2])
    expect(placeBeside(list, 3, 2, true)).toBe(true)
    expect(list.map((e) => e.id)).toEqual([1, 2, 3])
  })

  it('目标不存在或自身为目标时不变', () => {
    const list = [{ id: 1 }, { id: 2 }]
    expect(placeBeside(list, 1, 1, true)).toBe(false)
    expect(placeBeside(list, 1, 9, true)).toBe(false)
    expect(list.map((e) => e.id)).toEqual([1, 2])
  })
})

describe('outlineOrderRequest', () => {
  it('按当前树顺序给单元与内容连续编号', () => {
    const request = outlineOrderRequest(sortOutline(outline))
    expect(request.items.slice(0, 2)).toEqual([
      { itemId: 201, unitId: null, position: 1 },
      { itemId: 202, unitId: null, position: 2 },
    ])
    expect(request.units).toEqual([
      { unitId: 1, parentId: null, position: 1 },
      { unitId: 11, parentId: 1, position: 1 },
      { unitId: 12, parentId: 1, position: 2 },
      { unitId: 2, parentId: null, position: 2 },
    ])
    expect(request.items.slice(2)).toEqual([
      { itemId: 101, unitId: 1, position: 1 },
      { itemId: 102, unitId: 1, position: 2 },
      { itemId: 21, unitId: 2, position: 1 },
    ])
  })
})

describe('visitOutlineItems', () => {
  it('访问器拿到条目与所属单元', () => {
    const seen: string[] = []
    visitOutlineItems(outline.units, (item, owner) => seen.push(`${owner.id}:${item.id}`))
    expect(seen).toEqual(['2:21', '1:102', '1:101'])
  })
})

describe('moveOutlineItem', () => {
  it('跨组移动:顶层条目插到单元条目之前', () => {
    const tree = cloneOutline(outline)
    expect(moveOutlineItem(tree, 201, 1, 101, false)).toBe(true)
    expect(tree.items.map((i) => i.id)).toEqual([202])
    expect(tree.units[1]!.items.map((i) => i.id)).toEqual([102, 201, 101])
  })

  it('targetItemId 为 null 时追加到组末尾(拖到单元标题行)', () => {
    const tree = cloneOutline(outline)
    expect(moveOutlineItem(tree, 202, 2, null, true)).toBe(true)
    expect(tree.units[0]!.items.map((i) => i.id)).toEqual([21, 202])
  })

  it('单元条目移回顶层', () => {
    const tree = cloneOutline(outline)
    expect(moveOutlineItem(tree, 21, null, 201, true)).toBe(true)
    expect(tree.items.map((i) => i.id)).toEqual([202, 201, 21])
    expect(tree.units[0]!.items).toEqual([])
  })

  it('同组内移动等价于排序', () => {
    const tree = cloneOutline(outline)
    expect(moveOutlineItem(tree, 202, null, 201, true)).toBe(true)
    expect(tree.items.map((i) => i.id)).toEqual([201, 202])
  })

  it('目标组或目标条目不存在时不变', () => {
    const tree = cloneOutline(outline)
    expect(moveOutlineItem(tree, 201, 404, null, true)).toBe(false)
    expect(moveOutlineItem(tree, 201, 1, 404, true)).toBe(false)
    expect(moveOutlineItem(tree, 404, null, 201, true)).toBe(false)
    expect(tree.items.map((i) => i.id)).toEqual([202, 201])
  })
})

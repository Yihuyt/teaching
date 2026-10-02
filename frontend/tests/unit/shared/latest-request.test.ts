import { createLatestRequestGuard } from '@/shared/latestRequest'

describe('latest request guard', () => {
  it('只接受最新序号且快照仍一致的请求', () => {
    let courseId = 31
    const guard = createLatestRequestGuard(() => courseId)

    const first = guard.begin()
    const second = guard.begin()

    expect(guard.isCurrent(first)).toBe(false)
    expect(guard.isCurrent(second)).toBe(true)

    courseId = 32
    expect(guard.isCurrent(second)).toBe(false)
  })

  it('支持复合快照和主动失效', () => {
    let snapshot = { courseId: 31, itemType: 'material' }
    const guard = createLatestRequestGuard(
      () => snapshot,
      (left, right) => left.courseId === right.courseId && left.itemType === right.itemType,
    )
    const ticket = guard.begin()

    snapshot = { courseId: 31, itemType: 'question' }
    expect(guard.isCurrent(ticket)).toBe(false)

    const current = guard.begin()
    guard.invalidate()
    expect(guard.isCurrent(current)).toBe(false)
  })
})

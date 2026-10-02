import { tabFromQuery } from '@/shared/routeTab'

const tabs = new Set(['outline', 'resources'] as const)

describe('tabFromQuery', () => {
  it('取合法的 ?tab=', () => {
    expect(tabFromQuery({ tab: 'resources' }, tabs, 'outline')).toBe('resources')
  })

  it('缺省、未知值、数组值都回退', () => {
    expect(tabFromQuery({}, tabs, 'outline')).toBe('outline')
    expect(tabFromQuery({ tab: 'nope' }, tabs, 'outline')).toBe('outline')
    expect(tabFromQuery({ tab: ['outline'] }, tabs, 'outline')).toBe('outline')
  })
})

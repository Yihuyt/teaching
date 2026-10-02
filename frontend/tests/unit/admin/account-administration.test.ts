import { AccountViewRole } from '@/api/generated'
import { canCreateAccountRole, canManageAccountRole } from '@/features/admin/accountAdministration'

describe('账户管理角色边界', () => {
  it('root 可以创建和管理 admin、teacher、student，但不能通过普通接口管理 root', () => {
    expect(canCreateAccountRole(AccountViewRole.root, AccountViewRole.admin)).toBe(true)
    expect(canCreateAccountRole(AccountViewRole.root, AccountViewRole.teacher)).toBe(true)
    expect(canCreateAccountRole(AccountViewRole.root, AccountViewRole.student)).toBe(true)
    expect(canManageAccountRole(AccountViewRole.root, AccountViewRole.root)).toBe(false)
  })

  it('admin 只能创建和管理 teacher、student', () => {
    expect(canCreateAccountRole(AccountViewRole.admin, AccountViewRole.teacher)).toBe(true)
    expect(canCreateAccountRole(AccountViewRole.admin, AccountViewRole.student)).toBe(true)
    expect(canCreateAccountRole(AccountViewRole.admin, AccountViewRole.admin)).toBe(false)
    expect(canManageAccountRole(AccountViewRole.admin, AccountViewRole.root)).toBe(false)
    expect(canManageAccountRole(AccountViewRole.admin, AccountViewRole.admin)).toBe(false)
  })

  it('teacher 和 student 没有账户管理权限', () => {
    expect(canCreateAccountRole(AccountViewRole.teacher, AccountViewRole.student)).toBe(false)
    expect(canManageAccountRole(AccountViewRole.student, AccountViewRole.student)).toBe(false)
  })
})

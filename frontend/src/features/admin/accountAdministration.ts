import { AccountViewRole, type AccountViewRole as SystemRole } from '@/api/generated'

export function canCreateAccountRole(actorRole: SystemRole, targetRole: SystemRole): boolean {
  if (targetRole === AccountViewRole.root) {
    return false
  }
  return (
    actorRole === AccountViewRole.root ||
    (actorRole === AccountViewRole.admin && targetRole !== AccountViewRole.admin)
  )
}

export function canManageAccountRole(actorRole: SystemRole, targetRole: SystemRole): boolean {
  return canCreateAccountRole(actorRole, targetRole)
}

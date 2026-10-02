import type { UpdateProfileRequest } from '@/api/generated'

export interface AccountProfileForm {
  displayName: string
}

export function toUpdateProfileRequest(form: AccountProfileForm): UpdateProfileRequest {
  return { displayName: form.displayName.trim() }
}

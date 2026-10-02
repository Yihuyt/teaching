import { describe, expect, it } from 'vitest'

import { toUpdateProfileRequest } from '@/features/account/profileForm'

describe('账户资料表单', () => {
  it('显示名称去掉首尾空白', () => {
    expect(toUpdateProfileRequest({ displayName: ' 教师一 ' })).toEqual({ displayName: '教师一' })
  })
})

import { MaterialViewKind, MaterialViewState } from '@/api/generated'
import { assertMaterialContract, materialSizeLabel } from '@/features/courses/material'

const base = {
  id: 1,
  courseId: 2,
  parentId: null,
  name: '课程资料',
  state: MaterialViewState.active,
  createdAt: '2026-07-23T00:00:00Z',
  updatedAt: '2026-07-23T00:00:00Z',
}

describe('课程资料契约', () => {
  it('文件资料必须包含完整的 OSS 元数据', () => {
    const file = {
      ...base,
      kind: MaterialViewKind.file,
      contentType: 'application/pdf',
      sizeBytes: 2048,
      sha256: 'a'.repeat(64),
    }

    expect(() => assertMaterialContract([file])).not.toThrow()
    expect(materialSizeLabel(file)).toBe('2.0 KB')
  })

  it('拒绝用零字节掩盖缺失的文件大小', () => {
    const invalidFile = {
      ...base,
      kind: MaterialViewKind.file,
      contentType: 'application/pdf',
      sizeBytes: null,
      sha256: 'a'.repeat(64),
    }

    expect(() => assertMaterialContract([invalidFile])).toThrow('存储元数据不完整')
    expect(() => materialSizeLabel(invalidFile)).toThrow('缺少文件大小')
  })

  it('拒绝会被浏览器舍入的课程资料编号', () => {
    const unsafeFolder = {
      ...base,
      id: 9_000_000_000_000_000_000,
      kind: MaterialViewKind.folder,
      contentType: null,
      sizeBytes: null,
      sha256: null,
    }

    expect(() => assertMaterialContract([unsafeFolder])).toThrow('课程资料编号超出浏览器可精确表示的范围')
  })
})

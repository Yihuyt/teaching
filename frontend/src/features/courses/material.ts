import { MaterialViewKind, type MaterialView } from '@/api/generated'
import { formatFileSize } from '@/shared/format'

export function assertMaterialContract(materials: readonly MaterialView[]): void {
  for (const material of materials) {
    if (
      !Number.isSafeInteger(material.id) ||
      !Number.isSafeInteger(material.courseId) ||
      (material.parentId !== null && !Number.isSafeInteger(material.parentId))
    ) {
      throw new Error('课程资料编号超出浏览器可精确表示的范围')
    }
    if (material.kind === MaterialViewKind.file) {
      if (material.contentType === null || material.sizeBytes === null || material.sha256 === null) {
        throw new Error(`课程资料 ${material.id} 的存储元数据不完整`)
      }
      continue
    }
    if (material.contentType !== null || material.sizeBytes !== null || material.sha256 !== null) {
      throw new Error(`课程资料目录 ${material.id} 包含了不应存在的文件元数据`)
    }
  }
}

export function materialSizeLabel(material: MaterialView): string {
  if (material.sizeBytes === null) {
    throw new Error(`课程资料 ${material.id} 缺少文件大小`)
  }
  return formatFileSize(material.sizeBytes)
}

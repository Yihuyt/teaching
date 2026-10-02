import { api, ApiProblem } from '@/api/client'
import { calculateFileSha256 } from '@/shared/fileHash'

/** 上传取票被同名文件 / 文件夹拒绝(后端 409 文案是契约,e2e 锁定) */
function isMaterialNameConflict(cause: unknown): boolean {
  return (
    cause instanceof ApiProblem &&
    cause.status === 409 &&
    cause.message === '同一文件夹内已有同名文件或文件夹'
  )
}

export function nextAvailableName(name: string, existingNames: Iterable<string>): string {
  const taken = new Set<string>()
  for (const existing of existingNames) taken.add(existing.toLowerCase())
  const dot = name.lastIndexOf('.')
  const base = dot > 0 ? name.slice(0, dot) : name
  const extension = dot > 0 ? name.slice(dot) : ''
  for (let ordinal = 2; ; ordinal += 1) {
    const suffix = ` (${ordinal})`
    const budget = Math.max(1, 255 - extension.length - suffix.length)
    const candidate = base.slice(0, budget) + suffix + extension
    if (!taken.has(candidate.toLowerCase())) return candidate
  }
}

interface UploadedMaterial {
  materialId: number
  /** 实际入库的名称;同名冲突被自动改名时与原文件名不同 */
  name: string
}

export async function uploadCourseMaterial(
  courseId: number,
  file: File,
  parentId: number | null,
): Promise<UploadedMaterial> {
  const checksum = await calculateFileSha256(file)
  return uploadWithName(courseId, file, parentId, file.name, checksum)
}

/** 并发上传同名时双方可能反复算出同一建议名:最多重试 5 次后如实报错 */
const MAX_RENAME_RETRIES = 5

async function uploadWithName(
  courseId: number,
  file: File,
  parentId: number | null,
  name: string,
  checksum: string,
  renameRetries = 0,
): Promise<UploadedMaterial> {
  let ticket
  try {
    ticket = (
      await api.courseMaterialCreateUploadTicket(courseId, {
        parentId,
        name,
        contentType: file.type || 'application/octet-stream',
        sizeBytes: file.size,
        sha256: checksum,
      })
    ).data
  } catch (cause: unknown) {
    if (!isMaterialNameConflict(cause) || renameRetries >= MAX_RENAME_RETRIES) throw cause
    const materials = (await api.courseMaterialList(courseId, parentId === null ? undefined : { parentId }))
      .data
    const renamed = nextAvailableName(
      file.name,
      materials.map((item) => item.name),
    )
    return uploadWithName(courseId, file, parentId, renamed, checksum, renameRetries + 1)
  }
  if (ticket.method !== 'PUT') {
    throw new Error('当前上传方式不受支持，请联系管理员')
  }
  const response = await fetch(ticket.url, {
    method: ticket.method,
    headers: ticket.requiredHeaders,
    body: file,
  })
  if (!response.ok) {
    throw new Error('文件上传失败，请重试；若持续出现，请联系管理员')
  }
  await api.courseMaterialConfirmUpload(courseId, ticket.materialId)
  return { materialId: ticket.materialId, name }
}

export function validateMaterialName(name: string): string | null {
  const trimmed = name.trim()
  if (!trimmed) return '名称不能为空'
  if (trimmed.length > 255) return '名称不能超过 255 个字符'
  return null
}

export function validateUploadFile(file: File): string | null {
  const nameError = validateMaterialName(file.name)
  if (nameError) {
    return nameError
  }
  if ((file.type || 'application/octet-stream').length > 128) {
    return '文件内容类型不能超过 128 个字符'
  }
  if (file.size < 1 || file.size > 5 * 1024 * 1024 * 1024) {
    return '文件大小必须在 1 字节到 5 GiB 之间'
  }
  return null
}

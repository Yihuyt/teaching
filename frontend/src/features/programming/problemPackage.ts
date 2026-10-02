export const MAX_PROBLEM_PACKAGE_BYTES = 256 * 1024 * 1024

export function validateProblemPackageFile(file: File): string | null {
  const lower = file.name.toLowerCase()
  if (!lower.endsWith('.zip') && !lower.endsWith('.kpp')) {
    return `${file.name}：题目包必须是 .zip 或 .kpp`
  }
  if (file.size < 1 || file.size > MAX_PROBLEM_PACKAGE_BYTES) {
    return `${file.name}：题目包大小必须在 1 字节到 256 MiB 之间`
  }
  return null
}

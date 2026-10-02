const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

export function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    throw new Error(`无效的日期时间：${value}`)
  }
  return dateTimeFormatter.format(date)
}

/** 课件接口的时间戳是 epoch 毫秒(JSON 数字),与 ISO 字符串版分开取名 */
export function formatEpochMillis(value: number): string {
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`无效的时间戳：${value}`)
  }
  return dateTimeFormatter.format(new Date(value))
}

export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    throw new Error(`无效的文件大小：${bytes}`)
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  if (bytes < 1024 * 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  }
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`
}

export function deletionPrompt(count: number, associated: boolean): string {
  if (count === 1) return associated ? '该条目和其他部分关联，确认删除？' : '确认删除？'
  return associated ? '存在和其他部分关联的条目，确认删除？' : `确认删除所选 ${count} 项？`
}

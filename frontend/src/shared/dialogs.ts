import { ElMessageBox } from 'element-plus'

/** 确认框:取消返回 false(不抛 rejection) */
export async function confirm(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

/** 输入框:取消返回 null;validate 返回错误文案时阻止确认 */
export async function prompt(
  message: string,
  title: string,
  options: { value?: string; validate?: (value: string) => string | null } = {},
): Promise<string | null> {
  try {
    const result = await ElMessageBox.prompt(message, title, {
      inputValue: options.value ?? '',
      inputValidator: (value: string) => options.validate?.(value) ?? true,
    })
    return result.value
  } catch {
    return null
  }
}

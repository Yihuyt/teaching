/**
 * 在新标签页打开一个要先向服务端取得的地址(下载票据等):
 * 先在用户手势内同步开窗,再把拿到的地址写进去——await 之后再 window.open 会被浏览器当弹窗拦截。
 * 取地址失败时关掉空白窗并抛出,由调用方提示。
 */
export async function openInNewTab(resolveUrl: () => Promise<string>): Promise<void> {
  const tab = window.open('', '_blank')
  try {
    const url = await resolveUrl()
    if (tab) tab.location.href = url
    else window.location.assign(url)
  } catch (error: unknown) {
    tab?.close()
    throw error
  }
}

/**
 * 把英文记法的积木文本解析后画成中文积木:积木文字用 scratchblocks 自带的中文包翻,
 * 下拉选项(空格、鼠标指针、全部脚本……)库不翻,按编辑器自己的中文文案对照(menu-labels.zh-cn.json,
 * 从编辑器的中文文案导出)换掉。模型写的和作品里读出来的都是英文记法,只在画图这一步变中文。
 */
import scratchblocks from 'scratchblocks/browser.es.js'
import zhCn from 'scratchblocks/locales/zh-cn.json'

import menuLabels from '@/features/blockcoding/menu-labels.zh-cn.json'

const LANGUAGE = 'zh-cn'
scratchblocks.loadLanguages({ [LANGUAGE]: zhCn })

type MenuLabels = { labels: Record<string, string>; byBlock: Record<string, Record<string, string>> }
const menus: MenuLabels = menuLabels

function isInput(node: unknown): node is InstanceType<typeof scratchblocks.Input> {
  return node instanceof scratchblocks.Input
}

function isBlock(node: unknown): node is InstanceType<typeof scratchblocks.Block> {
  return node instanceof scratchblocks.Block
}

function isScript(node: unknown): node is InstanceType<typeof scratchblocks.Script> {
  return node instanceof scratchblocks.Script
}

function translateMenus(block: InstanceType<typeof scratchblocks.Block>): void {
  for (const child of block.children) {
    if (isInput(child)) {
      if (!child.hasArrow) continue
      const zh = menus.byBlock[block.info.id ?? '']?.[child.value] ?? menus.labels[child.value]
      if (zh) {
        child.value = zh
        child.label = new scratchblocks.Label(zh, `literal-${child.shape}`)
      }
    } else if (isBlock(child)) {
      translateMenus(child)
    } else if (isScript(child)) {
      child.blocks.forEach((inner) => isBlock(inner) && translateMenus(inner))
    }
  }
}

export function parseChinese(code: string): ReturnType<typeof scratchblocks.parse> {
  const doc = scratchblocks.parse(code, { languages: ['en'] })
  doc.translate(scratchblocks.allLanguages[LANGUAGE])
  doc.scripts.forEach((script) => script.blocks.forEach((block) => isBlock(block) && translateMenus(block)))
  return doc
}

import { describe, expect, it } from 'vitest'

import { parseChinese } from '@/features/blockcoding/scratchblocksZh'

describe('积木图画成中文', () => {
  it('积木文字按中文包翻,下拉选项按编辑器文案翻,变量名与自定义积木名原样', () => {
    const doc = parseChinese(
      'when [space v] key pressed\nset rotation style [left-right v]\nforever\n  move (10) steps\n  if <touching [edge v]?> then\n    stop [all v]\n  end\nend',
    )
    const text = doc.stringify()
    expect(text).toContain('空格')
    expect(text).toContain('左右翻转')
    expect(text).toContain('移动 (10) 步')
    expect(text).toContain('舞台边缘')
    expect(text).toContain('全部脚本')
    expect(text).not.toContain('space')

    const custom = parseChinese('define 跳 (高度)\nchange [分数 v] by (1)\nerase all')
    const customText = custom.stringify()
    expect(customText).toContain('定义')
    expect(customText).toContain('跳')
    expect(customText).toContain('分数')
    expect(customText).toContain('全部擦除')
  })
})

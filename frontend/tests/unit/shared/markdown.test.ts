import { describe, expect, it } from 'vitest'

import { markdownToPlainText, plainTextExcerpt } from '@/shared/markdown'

describe('Markdown 纯文本摘要', () => {
  it('保留正文语义并移除 Markdown 标记和链接地址', () => {
    expect(markdownToPlainText('# 课程说明\n\n学习 **变量** 与 [`print`](https://example.com)。')).toBe(
      '课程说明 学习 变量 与 print。',
    )
  })

  it('把列表、图片替代文字和代码整理为单行文本', () => {
    expect(markdownToPlainText('- 第一项\n- 第二项\n\n![流程图](flow.png)\n\n```js\nrun()\n```')).toBe(
      '第一项 第二项 流程图 run()',
    )
  })

  it('按 Unicode 字符截断并明确拒绝无效长度', () => {
    expect(plainTextExcerpt('智能教学平台', 4)).toBe('智能教学…')
    expect(plainTextExcerpt('课程简介', 20)).toBe('课程简介')
    expect(() => plainTextExcerpt('课程简介', 0)).toThrow('摘要长度必须是正整数')
  })
})

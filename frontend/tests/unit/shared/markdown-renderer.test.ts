import { mount } from '@vue/test-utils'

import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'

describe('MarkdownRenderer', () => {
  it('渲染 Markdown 并清除脚本标签', () => {
    const wrapper = mount(MarkdownRenderer, {
      props: {
        source: '# 课程内容\n\n<script>alert("xss")</script>\n\n**重点**',
      },
    })

    expect(wrapper.find('h1').text()).toBe('课程内容')
    expect(wrapper.find('strong').text()).toBe('重点')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('<script')
  })

  it('保留原题面的安全 HTML 并清除危险属性与嵌入内容', () => {
    const wrapper = mount(MarkdownRenderer, {
      props: {
        source: `
<div class="legacy-statement" onclick="alert('xss')" style="position: fixed">
  <strong>原题面 HTML</strong>
  <img src="/diagram.png" onerror="alert('xss')">
  <a href="javascript:alert('xss')">危险链接</a>
  <iframe src="https://example.com"></iframe>
  <script>alert("xss")</script>
</div>
`,
      },
    })

    const statement = wrapper.find('.legacy-statement')
    const image = wrapper.find('img')
    const link = wrapper.find('a')

    expect(statement.exists()).toBe(true)
    expect(statement.find('strong').text()).toBe('原题面 HTML')
    expect(statement.attributes()).not.toHaveProperty('onclick')
    expect(statement.attributes()).not.toHaveProperty('style')
    expect(image.attributes('src')).toBe('/diagram.png')
    expect(image.attributes()).not.toHaveProperty('onerror')
    expect(link.attributes()).not.toHaveProperty('href')
    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.find('script').exists()).toBe(false)
  })
})

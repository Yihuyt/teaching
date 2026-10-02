import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SceneCanvas from '@/features/courseware/render/SceneCanvas.vue'
import { physicsStage, csStage, imageStage } from './fixtures'

// ECharts 需要真实布局尺寸,jsdom 下仅冒烟不断言图形
describe('SceneCanvas — fixture 渲染', () => {
  it('概念页:标题、要点、公式、强调框全部渲染,帧绝对定位', () => {
    const scene = physicsStage.scenes[1]!
    const wrapper = mount(SceneCanvas, { props: { scene, width: 880 } })

    expect(wrapper.text()).toContain('定律的表述')
    expect(wrapper.text()).toContain('合外力')
    expect(wrapper.text()).toContain('易错点')

    const frames = wrapper.findAll('.block-frame')
    expect(frames.length).toBe(3)
    for (const frame of frames) {
      const style = frame.attributes('style') ?? ''
      expect(style).toContain('position: absolute')
    }
  })

  it('双栏页:columns 子块渲染在不同横向位置', () => {
    const scene = physicsStage.scenes[2]!
    const wrapper = mount(SceneCanvas, { props: { scene, width: 880 } })
    const left = wrapper.find('[data-block-id="blk-heading-1"]')
    const right = wrapper.find('[data-block-id="blk-heading-2"]')
    expect(left.exists()).toBe(true)
    expect(right.exists()).toBe(true)
    const leftX = Number(/left: ([\d.]+)px/.exec(left.attributes('style') ?? '')?.[1])
    const rightX = Number(/left: ([\d.]+)px/.exec(right.attributes('style') ?? '')?.[1])
    expect(leftX).toBeLessThan(rightX)
  })

  it('quiz 页:选项渲染,非交互模式无提交按钮', () => {
    const scene = physicsStage.scenes[4]!
    const wrapper = mount(SceneCanvas, { props: { scene, width: 880 } })
    expect(wrapper.text()).toContain('随堂测验')
    expect(wrapper.text()).toContain('A.')
    expect(wrapper.text()).not.toContain('提交答案')
    expect(wrapper.text()).not.toContain('乘除搞反')
  })

  it('代码页:代码与 caption 渲染', () => {
    const scene = csStage.scenes[1]!
    const wrapper = mount(SceneCanvas, { props: { scene, width: 880 } })
    expect(wrapper.text()).toContain('binary_search')
    expect(wrapper.text()).toContain('Python 实现')
  })

  it('强调块:文字与说明渲染', () => {
    const scene = csStage.scenes[2]!
    const wrapper = mount(SceneCanvas, { props: { scene, width: 880 } })
    expect(wrapper.text()).toContain('10 亿数据,最多 30 次比较')
    expect(wrapper.text()).toContain('最坏比较次数')
  })

  it('图片块:有地址映射时渲染 img,否则显示占位', () => {
    const scene = imageStage.scenes[0]!
    const withUrl = mount(SceneCanvas, {
      props: { scene, width: 880, assetUrls: { 'courseware/1/images/img_1.png': 'https://example.com/a.png' } },
    })
    expect(withUrl.find('img').attributes('src')).toBe('https://example.com/a.png')
    const withoutUrl = mount(SceneCanvas, { props: { scene, width: 880 } })
    expect(withoutUrl.find('img').exists()).toBe(false)
    expect(withoutUrl.find('.image-placeholder').exists()).toBe(true)
  })

  it('视觉状态:hiddenIds 隐藏块,highlightId 高亮块', () => {
    const scene = physicsStage.scenes[1]!
    const wrapper = mount(SceneCanvas, {
      props: {
        scene,
        width: 880,
        hiddenIds: new Set(['blk-callout-1']),
        highlightId: 'blk-formula-1',
      },
    })
    expect(wrapper.find('[data-block-id="blk-callout-1"]').classes()).toContain('frame-hidden')
    expect(wrapper.find('[data-block-id="blk-formula-1"]').classes()).toContain('frame-highlight')
  })
})

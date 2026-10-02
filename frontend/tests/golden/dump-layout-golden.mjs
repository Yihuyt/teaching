/**
 * 金标准用例导出:用 TS 布局引擎(唯一事实源)对约 20 个代表性页面跑
 * layoutScene / describeOverflow,结果写入 Java 侧一致性测试的固定资产。
 *
 * 运行:node --experimental-strip-types scripts/courseware/dump-layout-golden.mjs
 * 输出:backend/server/src/test/resources/layout-golden/cases.json
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { layoutScene, describeOverflow } from '../../src/features/courseware/layout/index.ts'

const heading = (id, text, level = 2) => ({ id, type: 'heading', level, text })
const bullets = (id, texts) => ({ id, type: 'bullets', items: texts.map((text) => ({ text })) })
const paragraph = (id, text) => ({ id, type: 'paragraph', text })

function makeScene(partial) {
  return {
    id: 'scene-1',
    type: 'content',
    title: '牛顿第二定律',
    preset: 'standard',
    speech: [],
    ...partial,
  }
}

// 约 92 个汉字:原字号下每条占两行,是标定溢出阶梯的基准单位(取自 layout.test.ts)
const longText =
  '这是一条相当长的要点内容,用来占据版面空间以测试布局引擎的溢出处理逻辑是否正确工作,' +
  '再补充一句让它在标准内容区宽度下无论如何都需要换行排成两行的额外说明文字以便标定阶梯。'

const cases = []
function addCase(name, scene) {
  cases.push({ name, scene, positioned: layoutScene(scene), overflowMessage: describeOverflow(scene) })
}

// --- 常规排布 ---
addCase(
  'standard-basic',
  makeScene({
    blocks: [heading('blk-heading-1', '本节要点'), bullets('blk-bullets-1', ['第一点', '第二点', '第三点'])],
  }),
)

addCase(
  'standard-bullets-sub-ordered',
  makeScene({
    blocks: [
      {
        id: 'blk-bullets-1',
        type: 'bullets',
        ordered: true,
        items: [
          {
            text: '一级条目甲,后面跟随两条二级说明。',
            sub: ['第一条子说明文字较长需要在缩进后的宽度里断行测试', '第二条子说明'],
          },
          { text: '一级条目乙' },
        ],
      },
    ],
  }),
)

addCase(
  'chart-flex-expand',
  makeScene({
    blocks: [
      heading('blk-heading-1', '销量对比'),
      {
        id: 'blk-chart-1',
        type: 'chart',
        chartType: 'bar',
        categories: ['一月', '二月'],
        series: [{ name: '销量', data: [10, 20] }],
      },
    ],
  }),
)

addCase(
  'double-flex-share',
  makeScene({
    blocks: [
      {
        id: 'blk-chart-1',
        type: 'chart',
        chartType: 'line',
        categories: ['甲', '乙', '丙'],
        series: [{ name: '序列', data: [1, 2, 3] }],
        caption: '带说明的图表,弹性最小高度要加上这行说明文字的高度。',
      },
      {
        id: 'blk-chart-2',
        type: 'chart',
        chartType: 'bar',
        categories: ['甲', '乙'],
        series: [{ name: '序列', data: [4, 5] }],
      },
    ],
  }),
)

addCase(
  'two-column-ratio',
  makeScene({
    preset: 'two-column',
    blocks: [
      {
        id: 'blk-columns-1',
        type: 'columns',
        ratio: [7, 5],
        children: [
          [heading('blk-heading-1', '左列'), bullets('blk-bullets-1', ['甲', '乙'])],
          [heading('blk-heading-2', '右列')],
        ],
      },
    ],
  }),
)

addCase(
  'two-column-no-ratio-and-mismatch',
  makeScene({
    preset: 'two-column',
    blocks: [
      {
        id: 'blk-columns-1',
        type: 'columns',
        children: [
          [paragraph('blk-paragraph-1', '无 ratio 时两列均分宽度。')],
          [paragraph('blk-paragraph-2', '右列内容')],
        ],
      },
      {
        id: 'blk-columns-2',
        type: 'columns',
        ratio: [3],
        children: [
          [paragraph('blk-paragraph-3', 'ratio 长度与列数不符时退回均分。')],
          [paragraph('blk-paragraph-4', '右列内容')],
        ],
      },
    ],
  }),
)

addCase(
  'media-right-code',
  makeScene({
    preset: 'media-right',
    blocks: [
      bullets('blk-bullets-1', ['要点甲', '要点乙']),
      { id: 'blk-code-1', type: 'code', language: 'python', code: 'print("hello")' },
    ],
  }),
)

addCase(
  'media-right-degrade',
  makeScene({
    preset: 'media-right',
    blocks: [paragraph('blk-paragraph-1', '一段说明文字。'), bullets('blk-bullets-1', [' 要点'])],
  }),
)

addCase(
  'media-right-only-media',
  makeScene({
    preset: 'media-right',
    blocks: [
      {
        id: 'blk-chart-1',
        type: 'chart',
        chartType: 'pie',
        categories: ['A', 'B'],
        series: [{ name: '占比', data: [30, 70] }],
      },
    ],
  }),
)

addCase(
  'video-scene',
  makeScene({
    type: 'video',
    blocks: [],
    video: { src: 'courseware/1/videos/demo.mp4' },
  }),
)

addCase(
  'interactive-scene',
  makeScene({
    type: 'interactive',
    blocks: [],
    interactive: {
      html: '<!DOCTYPE html><html><body>demo</body></html>',
      widgetType: 'simulation',
      widgetOutline: { concept: 'demo' },
    },
  }),
)

addCase(
  'title-cover',
  makeScene({
    preset: 'title-cover',
    title: '高中物理·力学总复习',
    blocks: [paragraph('blk-paragraph-1', '主讲:张老师 | 2026 年春季学期')],
  }),
)

addCase(
  'section-divider',
  makeScene({
    preset: 'section-divider',
    title: '第二章 牛顿运动定律',
    blocks: [paragraph('blk-paragraph-1', '从惯性谈起')],
  }),
)

addCase(
  'quiz-scene',
  makeScene({
    type: 'quiz',
    preset: 'quiz',
    title: '随堂检测',
    blocks: [
      {
        id: 'blk-quiz_choice-1',
        type: 'quiz_choice',
        stem: '一个质量为 2kg 的物体受到 6N 的合外力,它的加速度是多少?',
        options: [
          { label: 'A', text: '2 m/s^2' },
          { label: 'B', text: '3 m/s^2' },
          { label: 'C', text: '6 m/s^2' },
          { label: 'D', text: '12 m/s^2,这个选项文字明显更长一些用来测试选项内换行的高度累计是否一致' },
        ],
        answer: ['B'],
        multiple: false,
        explanation: '由 F=ma 得 a=F/m=6/2=3 m/s^2。',
      },
    ],
  }),
)

addCase(
  'image-aspect-ratio-and-media-right',
  makeScene({
    preset: 'media-right',
    blocks: [
      heading('blk-heading-1', '光的反射'),
      bullets('blk-bullets-1', ['反射角等于入射角', '三线共面']),
      {
        id: 'blk-image-1',
        type: 'image',
        src: 'courseware/1/images/img_1.jpg',
        width: 884,
        height: 424,
        caption: '平面镜反射光路图',
      },
    ],
  }),
)

addCase(
  'image-tall-capped-and-unknown-size',
  makeScene({
    blocks: [
      paragraph('blk-paragraph-1', '竖图按最大高度封顶,尺寸未知的图按默认高度。'),
      { id: 'blk-image-1', type: 'image', src: 'courseware/1/images/img_2.png', width: 300, height: 900 },
      { id: 'blk-image-2', type: 'image', src: 'courseware/1/images/img_3.png', width: 0, height: 0, caption: '未知尺寸' },
    ],
  }),
)

addCase(
  'emphasis-with-caption',
  makeScene({
    blocks: [
      {
        id: 'blk-emphasis-1',
        type: 'emphasis',
        text: 'F = ma',
        caption: '力等于质量乘以加速度,这是动力学的核心方程。',
      },
    ],
  }),
)

addCase(
  'emphasis-without-caption',
  makeScene({
    blocks: [
      paragraph('blk-paragraph-1', '没有说明文字的强调块只按一级标题字号计高。'),
      { id: 'blk-emphasis-1', type: 'emphasis', text: '十亿数据,最多三十次比较' },
    ],
  }),
)

addCase(
  'callout-variants',
  makeScene({
    blocks: [
      {
        id: 'blk-callout-1',
        type: 'callout',
        variant: 'info',
        text: '没有标题的 callout 也要按含标题行测量。',
      },
      {
        id: 'blk-callout-2',
        type: 'callout',
        variant: 'warning',
        title: '易错警示',
        text: '注意合外力方向与运动方向不一定相同,减速时二者相反。',
      },
    ],
  }),
)

addCase(
  'table-caption',
  makeScene({
    blocks: [
      {
        id: 'blk-table-1',
        type: 'table',
        headers: ['物理量', '符号', '单位'],
        rows: [
          ['力', 'F', '牛顿 N'],
          ['质量', 'm', '千克 kg'],
          ['加速度,即速度随时间的变化率,这个单元格文字很长会换行', 'a', 'm/s^2'],
        ],
        caption: '表 1:牛顿第二定律涉及的物理量一览',
      },
    ],
  }),
)

addCase(
  'formula-multirow-caption',
  makeScene({
    blocks: [
      {
        id: 'blk-formula-1',
        type: 'formula',
        latex: 'F = ma \\\\ a = \\frac{F}{m} \\\\ m = \\frac{F}{a}',
        caption: '同一关系的三种表达形式',
      },
    ],
  }),
)

addCase(
  'code-long-lines-caption',
  makeScene({
    blocks: [
      {
        id: 'blk-code-1',
        type: 'code',
        language: 'python',
        code: 'def acceleration(force, mass):\n    """compute a = F / m with a deliberately very long docstring line to exercise column based soft wrapping"""\n    return force / mass\n',
        caption: '用 Python 计算加速度',
      },
    ],
  }),
)

// --- 溢出阶梯 ---
addCase('overflow-none', makeScene({ blocks: [bullets('blk-bullets-1', Array(5).fill(longText))] }))
addCase('overflow-shrunk', makeScene({ blocks: [bullets('blk-bullets-1', Array(7).fill(longText))] }))
addCase('overflow-error', makeScene({ blocks: [bullets('blk-bullets-1', Array(14).fill(longText))] }))

addCase(
  'media-right-overflow-both-regions',
  makeScene({
    preset: 'media-right',
    blocks: [
      bullets('blk-bullets-1', Array(9).fill(longText)),
      {
        id: 'blk-table-1',
        type: 'table',
        headers: ['阶段', '说明'],
        rows: Array.from({ length: 14 }, (_, i) => [`阶段 ${i + 1}`, longText]),
      },
    ],
  }),
)

// --- 文本测量边界 ---
addCase(
  'mixed-cjk-latin-inline',
  makeScene({
    blocks: [
      paragraph(
        'blk-paragraph-1',
        '速度 velocity 与加速度 acceleration 是**两个不同**的物理量,由 $a = \\Delta v / t$ 定义,数字 12345 也参与测量。',
      ),
    ],
  }),
)

addCase(
  'long-url-forcesplit',
  makeScene({
    blocks: [
      paragraph(
        'blk-paragraph-1',
        '参考资料:https://example.com/very/long/path/that/never/ends/and/keeps/going/forever/and/ever/physics-newton-second-law-explained',
      ),
    ],
  }),
)

addCase(
  'closing-punct-lines',
  makeScene({
    blocks: [
      paragraph(
        'blk-paragraph-1',
        '第一句话说完了。第二句话紧跟其后,并且带着标点。第三句继续,逗号也算。结束!排版引擎必须保证闭合标点不出现在行首。',
      ),
    ],
  }),
)

addCase(
  'title-two-line-shrink',
  makeScene({
    title:
      '这是一个特别冗长的页面标题用来触发标题条的两行限制从而让布局引擎把标题字号降低一档再重新断行排版以验证缩字逻辑在两端实现中的一致性表现',
    blocks: [paragraph('blk-paragraph-1', '正文内容。')],
  }),
)

// --- 钉住块与字号档(排版覆盖) ---
const image = (id) => ({ id, type: 'image', src: 'courseware/1/images/a.png', width: 800, height: 600 })

addCase(
  'pinned-image-wrap',
  makeScene({
    blocks: [heading('blk-heading-1', '要点'), bullets('blk-bullets-1', ['甲', '乙', '丙']), image('blk-image-1')],
    layouts: [{ blockId: 'blk-image-1', frame: { x: 60, y: 260, w: 400, h: 120 } }],
  }),
)

addCase(
  'pinned-text-measured-height',
  makeScene({
    blocks: [bullets('blk-bullets-1', [longText, longText]), heading('blk-heading-1', '钉住的标题')],
    layouts: [{ blockId: 'blk-heading-1', frame: { x: 700, y: 300, w: 500 }, size: 'xlarge' }],
  }),
)

addCase(
  'pinned-does-not-shrink',
  makeScene({
    blocks: [bullets('blk-bullets-1', Array(7).fill(longText)), heading('blk-heading-1', '钉住的标题')],
    layouts: [{ blockId: 'blk-heading-1', frame: { x: 900, y: 40, w: 300 } }],
  }),
)

addCase(
  'pinned-bottom-overflow',
  makeScene({
    blocks: [heading('blk-heading-1', '要点'), bullets('blk-bullets-1', [longText, longText, longText])],
    layouts: [{ blockId: 'blk-bullets-1', frame: { x: 60, y: 650, w: 600 } }],
  }),
)

addCase(
  'pinned-media-right-degrades',
  makeScene({
    preset: 'media-right',
    blocks: [bullets('blk-bullets-1', ['甲']), image('blk-image-1')],
    layouts: [{ blockId: 'blk-image-1', frame: { x: 900, y: 500, w: 300, h: 180 } }],
  }),
)

addCase(
  'size-tiers-flow-and-columns',
  makeScene({
    preset: 'two-column',
    blocks: [
      heading('blk-heading-0', '标题'),
      {
        id: 'blk-columns-1',
        type: 'columns',
        children: [[heading('blk-heading-1', '左'), bullets('blk-bullets-1', ['甲'])], [heading('blk-heading-2', '右')]],
      },
      paragraph('blk-paragraph-1', '尾段。'),
    ],
    layouts: [
      { blockId: 'blk-columns-1', size: 'small' },
      { blockId: 'blk-paragraph-1', size: 'large' },
    ],
  }),
)

addCase(
  'pinned-chart-flex-with-band',
  makeScene({
    blocks: [
      heading('blk-heading-1', '销量对比'),
      {
        id: 'blk-chart-1',
        type: 'chart',
        chartType: 'bar',
        categories: ['一月', '二月'],
        series: [{ name: '销量', data: [10, 20] }],
      },
      image('blk-image-1'),
    ],
    layouts: [{ blockId: 'blk-image-1', frame: { x: 60, y: 560, w: 400, h: 120 } }],
  }),
)

const outPath = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../../backend/server/src/test/resources/layout-golden/cases.json',
)
mkdirSync(dirname(outPath), { recursive: true })
writeFileSync(outPath, JSON.stringify(cases, null, 2) + '\n', 'utf8')

import type { Stage } from '@/features/courseware/dsl'

export const physicsStage: Stage = {
  title: '牛顿第二定律',
  theme: 'default',
  scenes: [
    {
      id: 'p-cover',
      type: 'content',
      title: '牛顿第二定律',
      preset: 'title-cover',
      blocks: [
        {
          id: 'blk-paragraph-1',
          type: 'paragraph',
          text: '力、质量与加速度的定量关系 —— 经典力学的核心',
        },
      ],
      speech: [{ text: '同学们好,今天我们来学习经典力学中最核心的一条定律:牛顿第二定律。', actions: [] }],
    },
    {
      id: 'p-concept',
      type: 'content',
      title: '定律的表述',
      preset: 'standard',
      blocks: [
        {
          id: 'blk-bullets-1',
          type: 'bullets',
          items: [
            { text: '物体的加速度与所受**合外力**成正比' },
            { text: '加速度与物体的**质量**成反比' },
            { text: '加速度方向与合外力方向相同' },
          ],
        },
        { id: 'blk-formula-1', type: 'formula', latex: 'F = ma', caption: '合外力 = 质量 × 加速度' },
        {
          id: 'blk-callout-1',
          type: 'callout',
          variant: 'warning',
          title: '易错点',
          text: 'F 指的是合外力,不是任何单个力;单位使用牛顿(N)时,质量必须用千克、加速度用米每二次方秒。',
        },
      ],
      speech: [
        { text: '牛顿第二定律讲的是力与运动的定量关系,一共三个要点。', actions: [] },
        {
          text: '第一,加速度与合外力成正比:推得越用力,加速度越大。',
          actions: [{ type: 'highlight', target: 'blk-bullets-1#1' }],
        },
        {
          text: '第二,加速度与质量成反比:同样的力,推越重的东西越难加速。',
          actions: [{ type: 'highlight', target: 'blk-bullets-1#2' }],
        },
        {
          text: '写成公式,就是这条著名的 F 等于 m a。',
          actions: [
            { type: 'highlight', target: 'blk-formula-1' },
            { type: 'pause', ms: 800 },
          ],
        },
        {
          text: '注意,这里的 F 是合外力,单位换算也要当心。',
          actions: [{ type: 'highlight', target: 'blk-callout-1' }],
        },
      ],
    },
    {
      id: 'p-compare',
      type: 'content',
      title: '同样的力,不同的质量',
      preset: 'two-column',
      blocks: [
        {
          id: 'blk-columns-1',
          type: 'columns',
          ratio: [1, 1],
          children: [
            [
              { id: 'blk-heading-1', type: 'heading', level: 2, text: '小车(1 kg)' },
              {
                id: 'blk-bullets-2',
                type: 'bullets',
                items: [{ text: '受力 10 N' }, { text: '加速度 $a = 10\\ m/s^2$' }],
              },
            ],
            [
              { id: 'blk-heading-2', type: 'heading', level: 2, text: '卡车(1000 kg)' },
              {
                id: 'blk-bullets-3',
                type: 'bullets',
                items: [{ text: '受力 10 N' }, { text: '加速度 $a = 0.01\\ m/s^2$' }],
              },
            ],
          ],
        },
        {
          id: 'blk-callout-2',
          type: 'callout',
          variant: 'conclusion',
          text: '相同的力作用在不同质量的物体上,质量越大,加速度越小。',
        },
      ],
      speech: [
        {
          text: '我们对比一下:同样 10 牛的力,推一辆小车和推一辆卡车,结果完全不同。',
          actions: [{ type: 'reveal', target: 'blk-columns-1' }],
        },
        {
          text: '所以说,质量是惯性大小的量度。',
          actions: [{ type: 'highlight', target: 'blk-callout-2' }],
        },
      ],
    },
    {
      id: 'p-graph',
      type: 'content',
      title: '加速度与力的关系',
      preset: 'media-right',
      blocks: [
        {
          id: 'blk-bullets-4',
          type: 'bullets',
          items: [
            { text: '固定质量 $m = 2\\ kg$' },
            { text: '逐渐增大拉力,记录加速度' },
            { text: '数据点连成过原点的直线' },
            { text: '斜率即 $1/m$' },
          ],
        },
        {
          id: 'blk-chart-1',
          type: 'chart',
          chartType: 'line',
          categories: ['2N', '4N', '6N', '8N', '10N'],
          series: [{ name: '加速度 (m/s²)', data: [1, 2, 3, 4, 5] }],
        },
      ],
      speech: [
        {
          text: '实验上我们固定质量,改变拉力,测加速度。',
          actions: [{ type: 'highlight', target: 'blk-bullets-4#2' }],
        },
        {
          text: '把数据画出来,是一条过原点的直线,这正是正比关系的图像特征。',
          actions: [{ type: 'highlight', target: 'blk-chart-1' }],
        },
      ],
    },
    {
      id: 'p-quiz',
      type: 'quiz',
      title: '随堂测验',
      preset: 'quiz',
      blocks: [
        {
          id: 'blk-quiz_choice-1',
          type: 'quiz_choice',
          stem: '质量为 2 kg 的物体受到 6 N 的合外力,它的加速度是多少?',
          options: [
            { label: 'A', text: '$3\\ m/s^2$' },
            { label: 'B', text: '$12\\ m/s^2$' },
            { label: 'C', text: '$0.33\\ m/s^2$' },
            { label: 'D', text: '$6\\ m/s^2$' },
          ],
          answer: ['A'],
          multiple: false,
          explanation: '由 F = ma 得 a = F/m = 6/2 = 3 m/s²。选 B 的同学把乘除搞反了。',
        },
      ],
      speech: [{ text: '来做一道小题检验一下,请选出正确答案。', actions: [] }],
    },
  ],
}

export const csStage: Stage = {
  title: '二分查找',
  theme: 'default',
  scenes: [
    {
      id: 'p-divider',
      type: 'content',
      title: '第二章 · 查找算法',
      preset: 'section-divider',
      blocks: [{ id: 'blk-paragraph-1', type: 'paragraph', text: '从线性查找到二分查找:效率的飞跃' }],
      speech: [{ text: '这一章我们研究查找算法。', actions: [] }],
    },
    {
      id: 'p-code',
      type: 'content',
      title: '二分查找的实现',
      preset: 'media-right',
      blocks: [
        {
          id: 'blk-bullets-1',
          type: 'bullets',
          items: [
            { text: '前提:数组**有序**' },
            {
              text: '每次取中点,比较后舍弃一半',
              sub: ['左边界 left、右边界 right', '中点 mid = (left + right) // 2'],
            },
            { text: '时间复杂度 $O(\\log n)$' },
          ],
        },
        {
          id: 'blk-code-1',
          type: 'code',
          language: 'python',
          code: 'def binary_search(nums, target):\n    left, right = 0, len(nums) - 1\n    while left <= right:\n        mid = (left + right) // 2\n        if nums[mid] == target:\n            return mid\n        if nums[mid] < target:\n            left = mid + 1\n        else:\n            right = mid - 1\n    return -1',
          caption: 'Python 实现',
        },
      ],
      speech: [
        { text: '二分查找的前提是数组有序。', actions: [{ type: 'highlight', target: 'blk-bullets-1#1' }] },
        {
          text: '看右边的代码,核心就是不断收缩左右边界。',
          actions: [{ type: 'highlight', target: 'blk-code-1' }],
        },
      ],
    },
    {
      id: 'p-table',
      type: 'content',
      title: '查找算法对比',
      preset: 'standard',
      blocks: [
        {
          id: 'blk-table-1',
          type: 'table',
          headers: ['算法', '前提', '时间复杂度', '适用场景'],
          rows: [
            ['线性查找', '无', 'O(n)', '小规模或无序数据'],
            ['二分查找', '有序', 'O(log n)', '静态有序数组'],
            ['哈希查找', '建哈希表', 'O(1) 均摊', '高频查找'],
          ],
        },
        {
          id: 'blk-emphasis-1',
          type: 'emphasis',
          text: '10 亿数据,最多 30 次比较',
          caption: '二分查找在 n = 10⁹ 时的最坏比较次数仅 ⌈log₂n⌉ = 30',
        },
      ],
      speech: [
        { text: '把三种查找放在一起对比。', actions: [{ type: 'highlight', target: 'blk-table-1' }] },
        {
          text: '感受一下对数复杂度的威力:十亿条数据,三十次比较就够了。',
          actions: [{ type: 'highlight', target: 'blk-emphasis-1' }],
        },
      ],
    },
  ],
}

export const imageStage: Stage = {
  title: '光的折射',
  theme: 'default',
  scenes: [
    {
      id: 'p-image',
      type: 'content',
      title: '折射现象',
      preset: 'media-right',
      blocks: [
        {
          id: 'blk-bullets-1',
          type: 'bullets',
          items: [
            { text: '光从一种介质**斜射**进入另一种介质时,传播方向发生偏折' },
            { text: '入射角 θ₁ 与折射角 θ₂ 不相等' },
            { text: '光密介质中角度更小' },
          ],
        },
        {
          id: 'blk-image-1',
          type: 'image',
          src: 'courseware/1/images/img_1.png',
          width: 1328,
          height: 1328,
          caption: '光从空气斜射入水中,折射角小于入射角',
        },
      ],
      speech: [
        { text: '筷子插进水里看起来折断了,原因就是光的折射。', actions: [] },
        {
          text: '看配图:光从空气进入水中,传播方向在界面处发生了偏折。',
          actions: [{ type: 'highlight', target: 'blk-image-1' }],
        },
      ],
    },
  ],
}


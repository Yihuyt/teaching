<script lang="ts">
import DOMPurify from 'dompurify'

/**
 * 全局禁止内联 style(防止任意 CSS),但 KaTeX 靠内联 style 摆放上下标与分数线,
 * 只对 .katex 子树放行;DOMPurify 仍会过滤 style 里的危险内容。
 */
DOMPurify.addHook('uponSanitizeAttribute', (node, data) => {
  if (data.attrName === 'style' && node instanceof Element && node.closest('.katex')) {
    data.forceKeepAttr = true
  }
})
</script>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import { katex } from '@mdit/plugin-katex'
import renderMathInElement from 'katex/contrib/auto-render'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import cpp from 'highlight.js/lib/languages/cpp'
import css from 'highlight.js/lib/languages/css'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'

hljs.registerLanguage('bash', bash)
hljs.registerLanguage('sh', bash)
hljs.registerLanguage('c', cpp)
hljs.registerLanguage('cpp', cpp)
hljs.registerLanguage('css', css)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('js', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('python', python)
hljs.registerLanguage('py', python)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('ts', typescript)
hljs.registerLanguage('xml', xml)

const props = defineProps<{
  source: string
  inline?: boolean
}>()

const markdown = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: false,
  highlight(code, language) {
    if (language && hljs.getLanguage(language)) {
      return hljs.highlight(code, { language }).value
    }
    return hljs.highlightAuto(code).value
  },
}).use(katex)

const safeHtml = computed(() =>
  DOMPurify.sanitize(props.inline ? markdown.renderInline(props.source) : markdown.render(props.source), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['base', 'embed', 'form', 'iframe', 'link', 'meta', 'object', 'script', 'style'],
    FORBID_ATTR: ['style'],
  }),
)

/**
 * 第二遍公式渲染:markdown-it 只处理 Markdown 语法里的 $…$;历史迁移进来的题面是整段 HTML,
 * 被当作 html_block 原样输出,里面的公式到不了插件。渲染完成后再对 DOM 里残留的 $…$ 跑一遍
 * KaTeX auto-render(跳过 code / pre 与已渲染的 .katex)。
 */
const host = ref<HTMLElement>()

function renderLeftoverMath(): void {
  if (!host.value) return
  renderMathInElement(host.value, {
    delimiters: [
      { left: '$$', right: '$$', display: true },
      { left: '$', right: '$', display: false },
    ],
    ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code', 'option'],
    ignoredClasses: ['katex'],
    throwOnError: false,
  })
}

watch(safeHtml, () => void nextTick(renderLeftoverMath), { immediate: true })
</script>

<template>
  <span v-if="inline" ref="host" class="markdown-inline" v-html="safeHtml" />
  <div v-else ref="host" class="markdown-body" v-html="safeHtml" />
</template>

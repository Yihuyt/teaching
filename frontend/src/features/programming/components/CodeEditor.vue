<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { cpp } from '@codemirror/lang-cpp'
import { python } from '@codemirror/lang-python'
import { EditorState, type Extension } from '@codemirror/state'
import { basicSetup, EditorView } from 'codemirror'

const props = withDefaults(
  defineProps<{
    modelValue: string
    language: 'C17' | 'CPP20' | 'PYTHON312'
    readonly?: boolean
  }>(),
  {
    readonly: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const host = ref<HTMLDivElement>()
let view: EditorView | undefined

function languageExtension(): Extension {
  return props.language === 'PYTHON312' ? python() : cpp()
}

function createState(): EditorState {
  return EditorState.create({
    doc: props.modelValue,
    extensions: [
      basicSetup,
      languageExtension(),
      EditorState.readOnly.of(props.readonly),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          emit('update:modelValue', update.state.doc.toString())
        }
      }),
    ],
  })
}

onMounted(() => {
  if (!host.value) {
    throw new Error('代码编辑器挂载节点不存在')
  }
  view = new EditorView({
    state: createState(),
    parent: host.value,
  })
})

watch(
  () => props.modelValue,
  (value) => {
    if (!view || value === view.state.doc.toString()) {
      return
    }
    view.dispatch({
      changes: {
        from: 0,
        to: view.state.doc.length,
        insert: value,
      },
    })
  },
)

watch(
  () => [props.language, props.readonly] as const,
  () => {
    if (view) {
      view.setState(createState())
    }
  },
)

onBeforeUnmount(() => {
  view?.destroy()
  view = undefined
})
</script>

<template>
  <div ref="host" class="code-editor" />
</template>

<style scoped>
.code-editor {
  min-height: 420px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #fff;
}

.code-editor :deep(.cm-editor) {
  min-height: 420px;
  font-size: 14px;
}

.code-editor :deep(.cm-scroller) {
  overflow: auto;
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
}
</style>

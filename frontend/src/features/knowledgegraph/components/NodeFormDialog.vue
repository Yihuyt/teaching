<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'

import type { NodeView } from '@/api/generated'
import {
  KP_TYPES,
  NODE_KIND_LABELS,
  type KpType,
  type NodeKind,
  NODE_TEXT_LABELS,
} from '@/features/knowledgegraph/knowledgeGraph'

/**
 * 节点表单:类型由上下文固定(新建时由父节点动作决定,编辑时不可改)。
 * 文字栏按类型各归其名——章节的摘要 / 知识点的释义 / 代码示例的说明;
 * 知识点另有小类与别名,代码示例另有语言与代码。
 */
export interface NodeFormValue {
  label: string
  kpType: KpType | null
  summary: string | null
  definition: string | null
  explanation: string | null
  aliases: string[]
  code: string | null
  language: string | null
  sourceSectionTitle: string | null
  quote: string | null
}

const props = defineProps<{
  kind: NodeKind
  initial?: NodeView | null
  busy: boolean
}>()

const emit = defineEmits<{ submit: [value: NodeFormValue] }>()
const visible = defineModel<boolean>({ required: true })

const form = reactive({
  label: '',
  kpType: '概念' as KpType,
  text: '',
  aliases: [] as string[],
  aliasInput: '',
  code: '',
  language: '',
  sourceSectionTitle: '',
  quote: '',
})
const error = ref('')

const textLabel = computed(() => NODE_TEXT_LABELS[props.kind])

watch(visible, (open) => {
  if (!open) return
  const initial = props.initial
  form.label = initial?.label ?? ''
  form.kpType = initial?.kpType ?? '概念'
  form.text = initial?.summary ?? initial?.definition ?? initial?.explanation ?? ''
  form.aliases = initial ? [...initial.aliases] : []
  form.aliasInput = ''
  form.code = initial?.code ?? ''
  form.language = initial?.language ?? ''
  form.sourceSectionTitle = initial?.sourceSectionTitle ?? ''
  form.quote = initial?.quote ?? ''
  error.value = ''
})

function addAlias(): void {
  const alias = form.aliasInput.trim()
  if (!alias) return
  if (form.aliases.includes(alias)) {
    error.value = '别名已存在'
    return
  }
  if (form.aliases.length >= 10) {
    error.value = '别名最多 10 个'
    return
  }
  form.aliases = [...form.aliases, alias]
  form.aliasInput = ''
  error.value = ''
}

function removeAlias(alias: string): void {
  form.aliases = form.aliases.filter((item) => item !== alias)
}

function submit(): void {
  if (!form.label.trim()) {
    error.value = '名称不能为空'
    return
  }
  if (props.kind === 'code_example' && (!form.code.trim() || !form.language.trim())) {
    error.value = '代码示例必须填写语言和代码'
    return
  }
  error.value = ''
  const text = form.text.trim() || null
  emit('submit', {
    label: form.label.trim(),
    kpType: props.kind === 'knowledge_point' ? form.kpType : null,
    summary: props.kind === 'unit' ? text : null,
    definition: props.kind === 'knowledge_point' ? text : null,
    explanation: props.kind === 'code_example' ? text : null,
    aliases: props.kind === 'knowledge_point' ? form.aliases : [],
    code: props.kind === 'code_example' ? form.code : null,
    language: props.kind === 'code_example' ? form.language.trim() : null,
    sourceSectionTitle: props.kind === 'unit' ? null : form.sourceSectionTitle.trim() || null,
    quote: props.kind === 'unit' ? null : form.quote.trim() || null,
  })
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`${initial ? '编辑' : '添加'}${NODE_KIND_LABELS[kind]}`"
    width="560px"
    destroy-on-close
    append-to-body
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert" />
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="名称" required>
        <el-input v-model="form.label" maxlength="255" autofocus />
      </el-form-item>
      <el-form-item v-if="kind === 'knowledge_point'" label="小类" required>
        <el-radio-group v-model="form.kpType">
          <el-radio-button v-for="type in KP_TYPES" :key="type" :value="type">{{ type }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <template v-if="kind === 'code_example'">
        <el-form-item label="语言" required>
          <el-input v-model="form.language" maxlength="32" placeholder="如 python" class="language-input" />
        </el-form-item>
        <el-form-item label="代码" required>
          <el-input v-model="form.code" type="textarea" :rows="8" maxlength="20000" class="code-input" />
        </el-form-item>
      </template>
      <el-form-item :label="textLabel">
        <el-input v-model="form.text" type="textarea" :rows="4" maxlength="2000" />
      </el-form-item>
      <el-form-item v-if="kind === 'knowledge_point'" label="别名">
        <div class="alias-editor">
          <div v-if="form.aliases.length" class="alias-tags">
            <el-tag v-for="alias in form.aliases" :key="alias" closable @close="removeAlias(alias)">
              {{ alias }}
            </el-tag>
          </div>
          <div class="alias-add">
            <el-input v-model="form.aliasInput" maxlength="80" @keyup.enter.prevent="addAlias" />
            <el-button :disabled="!form.aliasInput.trim()" @click="addAlias">添加</el-button>
          </div>
        </div>
      </el-form-item>
      <template v-if="kind !== 'unit'">
        <el-form-item label="出处小节">
          <el-input v-model="form.sourceSectionTitle" maxlength="255" />
        </el-form-item>
        <el-form-item label="原文引文">
          <el-input v-model="form.quote" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.alias-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.alias-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.alias-add {
  display: flex;
  gap: 8px;
}

.form-alert {
  margin-bottom: 14px;
}

.language-input {
  width: 200px;
}

.code-input :deep(textarea) {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
}

</style>

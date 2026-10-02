<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ElInput, ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

import { questionTypeLabels } from '@/shared/labels'
import { BLANK_MARK, insertBlankMark, optionLetter, type QuestionItemForm } from '@/features/courses/question'

const model = defineModel<QuestionItemForm>({ required: true })
defineProps<{ disabled?: boolean }>()

function changeType(): void {
  model.value.singleAnswerIndex = null
  model.value.blankAnswer = ''
  model.value.trueFalseAnswer = true
  model.value.options = model.value.type === 'single_choice' ? ['', '', '', ''] : []
}

const stemInput = ref<InstanceType<typeof ElInput>>()

function insertBlank(): void {
  const textarea = stemInput.value?.textarea as HTMLTextAreaElement | undefined
  const start = textarea?.selectionStart ?? model.value.stemMarkdown.length
  const end = textarea?.selectionEnd ?? start
  const { text, cursor } = insertBlankMark(model.value.stemMarkdown, start, end)
  model.value.stemMarkdown = text
  void nextTick(() => {
    textarea?.focus()
    textarea?.setSelectionRange(cursor, cursor)
  })
}

function addOption(): void {
  model.value.options.push('')
}

function removeOption(index: number): void {
  if (model.value.options.length <= 2) {
    ElMessage.warning('选择题至少需要两个选项')
    return
  }
  model.value.options.splice(index, 1)
  const selected = model.value.singleAnswerIndex
  if (selected !== null) {
    if (selected === index) model.value.singleAnswerIndex = null
    else if (selected > index) model.value.singleAnswerIndex = selected - 1
  }
}
</script>

<template>
  <el-form :model="model" label-position="top" :disabled="disabled">
    <div class="two-columns">
      <el-form-item label="题型" required>
        <el-select v-model="model.type" @change="changeType">
          <el-option
            v-for="(label, value) in questionTypeLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="分值" required>
        <el-input-number v-model="model.score" :min="0.1" :max="1000" :precision="1" :step="1" />
      </el-form-item>
    </div>
    <el-form-item required>
      <template #label>
        <span class="stem-label">
          题干
          <el-button
            v-if="model.type === 'fill_in_blank'"
            size="small"
            :icon="Plus"
            :disabled="model.stemMarkdown.includes(BLANK_MARK)"
            @click="insertBlank"
          >
            插入空格
          </el-button>
        </span>
      </template>
      <el-input
        ref="stemInput"
        v-model="model.stemMarkdown"
        type="textarea"
        :rows="4"
        maxlength="100000"
        :placeholder="model.type === 'fill_in_blank' ? BLANK_MARK : ''"
      />
    </el-form-item>

    <template v-if="model.type === 'single_choice'">
      <el-form-item label="选项" required>
        <div class="option-list">
          <div v-for="(_, index) in model.options" :key="index" class="option-row">
            <span>{{ optionLetter(index) }}</span>
            <el-input
              v-model="model.options[index]"
              :placeholder="`选项 ${optionLetter(index)}`"
              maxlength="500"
            />
            <el-button link type="danger" @click="removeOption(index)">删除</el-button>
          </div>
          <el-button plain @click="addOption">添加选项</el-button>
        </div>
      </el-form-item>
      <el-form-item label="正确选项" required>
        <el-radio-group v-model="model.singleAnswerIndex">
          <el-radio
            v-for="(option, index) in model.options"
            :key="index"
            :value="index"
            :disabled="!option.trim()"
          >
            {{ optionLetter(index) }}. {{ option || '未填写' }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
    </template>
    <el-form-item v-else-if="model.type === 'fill_in_blank'" label="标准答案" required>
      <el-input v-model="model.blankAnswer" maxlength="200" show-word-limit placeholder="标准答案" />
    </el-form-item>
    <el-form-item v-else label="正确答案" required>
      <el-radio-group v-model="model.trueFalseAnswer">
        <el-radio :value="true">正确</el-radio>
        <el-radio :value="false">错误</el-radio>
      </el-radio-group>
    </el-form-item>

    <el-form-item label="解析">
      <el-input v-model="model.analysisMarkdown" type="textarea" :rows="3" maxlength="100000" />
    </el-form-item>
  </el-form>
</template>

<style scoped>
.stem-label {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.two-columns {
  display: grid;
  grid-template-columns: 220px 200px;
  gap: 18px;
}

.two-columns :deep(.el-select) {
  width: 100%;
}

.option-list {
  display: grid;
  width: 100%;
  gap: 8px;
}

.option-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.option-row > span {
  width: 18px;
  color: var(--text-secondary);
  font-weight: 600;
}
</style>

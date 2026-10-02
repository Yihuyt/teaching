<script setup lang="ts">
import { SubmissionViewStatus } from '@/api/generated'
import { programmingLanguageLabels, submissionStatusLabels, submissionStatusTagTypes } from '@/shared/labels'

const languageRows = [
  { name: programmingLanguageLabels.C17, environment: 'GCC 13' },
  { name: programmingLanguageLabels.CPP20, environment: 'G++ 13' },
  { name: programmingLanguageLabels.PYTHON312, environment: 'Python 3.12' },
]

const resultItems = [
  { status: SubmissionViewStatus.QUEUED, description: '提交已收到，正在等待评测。' },
  { status: SubmissionViewStatus.ACCEPTED, description: '程序通过了全部测试点。' },
  { status: SubmissionViewStatus.WRONG_ANSWER, description: '至少一个测试点的输出与标准答案不一致。' },
  { status: SubmissionViewStatus.COMPILE_ERROR, description: '源代码没有通过所选语言的编译检查。' },
  { status: SubmissionViewStatus.RUNTIME_ERROR, description: '程序在运行测试点时异常结束。' },
  { status: SubmissionViewStatus.TIME_LIMIT_EXCEEDED, description: '至少一个测试点的运行时间超过题目限制。' },
  { status: SubmissionViewStatus.MEMORY_LIMIT_EXCEEDED, description: '至少一个测试点的内存使用超过题目限制。' },
  { status: SubmissionViewStatus.OUTPUT_LIMIT_EXCEEDED, description: '至少一个测试点的输出量超过题目限制。' },
  {
    status: SubmissionViewStatus.SYSTEM_ERROR,
    description: '本次评测未能完成，请稍后重新提交；若持续出现，请联系教师。',
  },
]
</script>

<template>
  <div class="judge-guide">
    <h3>提交与评测</h3>
    <ol class="rules">
      <li>提交一份完整源代码，程序从标准输入读取数据，并将答案写入标准输出。</li>
      <li>系统会使用多个测试点检查程序；每个测试点分别执行题目给出的时间、内存和输出限制。</li>
      <li>输出中的空格、换行和调试信息都会参与答案比较，请只输出题目要求的内容。</li>
      <li>程序只能使用题目提供的输入，不能访问网络或系统文件。</li>
    </ol>

    <h3>支持的编程语言</h3>
    <el-table :data="languageRows" size="small">
      <el-table-column prop="name" label="语言" min-width="160" />
      <el-table-column prop="environment" label="运行环境" min-width="160" />
    </el-table>

    <h3>结果说明</h3>
    <dl class="results">
      <div v-for="item in resultItems" :key="item.status">
        <dt>
          <el-tag :type="submissionStatusTagTypes[item.status]" effect="plain" size="small">
            {{ submissionStatusLabels[item.status] }}
          </el-tag>
        </dt>
        <dd>{{ item.description }}</dd>
      </div>
    </dl>
  </div>
</template>

<style scoped>
.judge-guide h3 {
  margin: 0 0 12px;
  font-size: 15px;
}

.judge-guide h3 + h3,
.judge-guide .rules + h3,
.judge-guide .el-table + h3 {
  margin-top: 18px;
}

.rules {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 22px;
  color: var(--text-secondary);
  line-height: 1.6;
}

.results {
  margin: 0;
}

.results > div {
  display: grid;
  grid-template-columns: 140px 1fr;
  align-items: center;
  gap: 14px;
  padding: 8px 0;
  border-top: 1px solid var(--border);
}

.results > div:first-child {
  border-top: 0;
}

.results dt,
.results dd {
  margin: 0;
}

.results dd {
  color: var(--text-secondary);
  line-height: 1.5;
}
</style>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, Refresh } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'
import type { DeadLetterView, JudgeWorkerView, ObjectStorageStatus, PlatformView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import { confirm } from '@/shared/dialogs'
import { formatDateTime } from '@/shared/format'
import { usePlatformStore } from '@/stores/platform'

const platform = usePlatformStore()
const loading = ref(true)
const saving = ref(false)
const checking = ref(false)
const loadError = ref('')
const formError = ref('')
const settings = ref<PlatformView>()
const ossStatus = ref<ObjectStorageStatus>()
const retrying = ref(false)
const judgeWorkers = ref<JudgeWorkerView[]>([])
const judgeWorkersLoading = ref(false)
const deadLetters = ref<DeadLetterView[]>([])
const deadLettersLoading = ref(false)
const deadLetterBusyId = ref<number | null>(null)
const form = reactive({
  siteName: '',
  footerText: '',
})

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const [settingsResponse, statusResponse] = await Promise.all([
      api.platformSettings(),
      api.platformObjectStorageStatus(),
    ])
    settings.value = settingsResponse.data
    ossStatus.value = statusResponse.data
    Object.assign(form, {
      siteName: settingsResponse.data.siteName,
      footerText: settingsResponse.data.footerText,
    })
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  if (!form.siteName.trim()) {
    formError.value = '请输入平台名称'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    const updated = (
      await api.platformUpdate({
        siteName: form.siteName,
        footerText: form.footerText,
      })
    ).data
    settings.value = updated
    platform.replace(updated)
    ElMessage.success('已保存')
  } catch (error: unknown) {
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function retryFailedDeletions(): Promise<void> {
  retrying.value = true
  try {
    const { data } = await api.platformRetryFailedDeletions()
    ElMessage.success(`已重投 ${data.retried} 个删除任务`)
    await checkOss()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    retrying.value = false
  }
}

async function loadJudgeWorkers(): Promise<void> {
  judgeWorkersLoading.value = true
  try {
    judgeWorkers.value = (await api.judgeWorkerList()).data
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    judgeWorkersLoading.value = false
  }
}

function idleText(idleMs: number): string {
  if (idleMs < 1000) return '刚刚'
  if (idleMs < 60_000) return `${Math.round(idleMs / 1000)} 秒前`
  return `${Math.round(idleMs / 60_000)} 分钟前`
}

async function loadDeadLetters(): Promise<void> {
  deadLettersLoading.value = true
  try {
    deadLetters.value = (await api.judgeAdminList()).data
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    deadLettersLoading.value = false
  }
}

async function replayDeadLetter(letter: DeadLetterView): Promise<void> {
  deadLetterBusyId.value = letter.id
  try {
    const { data } = await api.judgeAdminReplay(letter.id)
    ElMessage.success(`重放完成：${data.outcome}`)
    await loadDeadLetters()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    deadLetterBusyId.value = null
  }
}

async function discardDeadLetter(letter: DeadLetterView): Promise<void> {
  if (!(await confirm(`确定丢弃死信 #${letter.id} 吗？`, '丢弃死信'))) return
  deadLetterBusyId.value = letter.id
  try {
    await api.judgeAdminDiscard(letter.id)
    ElMessage.success('已丢弃')
    await loadDeadLetters()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    deadLetterBusyId.value = null
  }
}

async function checkOss(): Promise<void> {
  checking.value = true
  try {
    ossStatus.value = (await api.platformObjectStorageStatus()).data
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    checking.value = false
  }
}

onMounted(() => {
  void load()
  void loadJudgeWorkers()
  void loadDeadLetters()
})
</script>

<template>
  <div>
    <PageHeader title="设置" />
    <AsyncState :loading="loading" :error="loadError" :empty="!settings" @retry="load">
      <div class="settings-grid">
        <section class="panel settings-panel">
          <div class="panel-header"><h2>基本信息</h2></div>
          <div class="panel-body">
            <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="form-alert" />
            <el-form :model="form" label-position="top">
              <el-form-item label="平台名称" required>
                <el-input v-model="form.siteName" maxlength="64" />
              </el-form-item>
              <el-form-item label="页脚文字">
                <el-input v-model="form.footerText" maxlength="255" />
              </el-form-item>
              <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
            </el-form>
          </div>
        </section>

        <section class="panel oss-panel">
          <div class="panel-header">
            <h2>
              <el-icon><Connection /></el-icon>阿里云 OSS 状态
            </h2>
            <el-button :icon="Refresh" :loading="checking" @click="checkOss">重新检测</el-button>
          </div>
          <div v-if="ossStatus" class="panel-body">
            <div
              :class="[
                'status-banner',
                ossStatus.connected ? 'status-banner--success' : 'status-banner--error',
              ]"
            >
              <strong>{{ ossStatus.connected ? '连接正常' : '连接失败' }}</strong>
              <span>检测时间：{{ formatDateTime(ossStatus.checkedAt) }}</span>
            </div>
            <dl>
              <div>
                <dt>OSS 接入地址</dt>
                <dd>{{ ossStatus.endpoint }}</dd>
              </div>
              <div>
                <dt>OSS 存储桶</dt>
                <dd>{{ ossStatus.bucket }}</dd>
              </div>
              <div>
                <dt>错误代码</dt>
                <dd>{{ ossStatus.errorCode ?? '—' }}</dd>
              </div>
              <div>
                <dt>待删除文件</dt>
                <dd>{{ ossStatus.pendingDeletionCount }} 个</dd>
              </div>
              <div>
                <dt>删除失败</dt>
                <dd :class="{ 'danger-text': ossStatus.failedDeletionCount > 0 }">
                  {{ ossStatus.failedDeletionCount }} 个
                  <el-button
                    v-if="ossStatus.failedDeletionCount > 0"
                    size="small"
                    :loading="retrying"
                    @click="retryFailedDeletions"
                  >
                    重投失败任务
                  </el-button>
                </dd>
              </div>
            </dl>
          </div>
        </section>

        <section class="panel judge-worker-panel">
          <div class="panel-header">
            <h2>判题机（在线 {{ judgeWorkers.filter((worker) => worker.alive).length }}）</h2>
            <el-button :icon="Refresh" :loading="judgeWorkersLoading" @click="loadJudgeWorkers"
              >刷新</el-button
            >
          </div>
          <div class="panel-body">
            <p v-if="judgeWorkers.length === 0" class="muted">没有判题机接入</p>
            <el-table v-else :data="judgeWorkers" size="small">
              <el-table-column prop="name" label="判题机" min-width="220" show-overflow-tooltip />
              <el-table-column label="状态" width="100">
                <template #default="{ row }: { row: JudgeWorkerView }">
                  <el-tag :type="row.alive ? 'success' : 'info'" effect="plain" size="small">
                    {{ row.alive ? '在线' : '离线' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="pending" label="处理中" width="100" />
              <el-table-column label="最近活动" width="140">
                <template #default="{ row }: { row: JudgeWorkerView }">{{ idleText(row.idleMs) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </section>

        <section class="panel dead-letter-panel">
          <div class="panel-header">
            <h2>判题死信（{{ deadLetters.length }}）</h2>
            <el-button :icon="Refresh" :loading="deadLettersLoading" @click="loadDeadLetters">刷新</el-button>
          </div>
          <div class="panel-body">
            <p v-if="deadLetters.length === 0" class="muted">无死信</p>
            <el-table v-else :data="deadLetters" size="small">
              <el-table-column prop="id" label="#" width="70" />
              <el-table-column prop="jobId" label="作业" min-width="200" show-overflow-tooltip />
              <el-table-column prop="errorType" label="类型" width="180" />
              <el-table-column prop="errorMessage" label="原因" min-width="220" show-overflow-tooltip />
              <el-table-column label="时间" width="170">
                <template #default="{ row }: { row: DeadLetterView }">{{
                  formatDateTime(row.createdAt)
                }}</template>
              </el-table-column>
              <el-table-column label="操作" width="140">
                <template #default="{ row }: { row: DeadLetterView }">
                  <el-button link :loading="deadLetterBusyId === row.id" @click="replayDeadLetter(row)">
                    重放
                  </el-button>
                  <el-button
                    link
                    type="danger"
                    :disabled="deadLetterBusyId === row.id"
                    @click="discardDeadLetter(row)"
                  >
                    丢弃
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </section>
      </div>
    </AsyncState>
  </div>
</template>

<style scoped>
.settings-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
  gap: 20px;
}

.panel-header h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 18px;
}

.form-alert {
  margin-bottom: 16px;
}

.status-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 20px;
  padding: 18px;
  border-radius: var(--radius-control);
}

.status-banner--success {
  color: #05603a;
  background: #ecfdf3;
}

.status-banner--error {
  color: #912018;
  background: #fef3f2;
}

.status-banner span {
  font-size: 12px;
}

dl {
  margin: 0 0 22px;
}

dl div {
  display: grid;
  grid-template-columns: 110px 1fr;
  gap: 12px;
  padding: 13px 0;
  border-bottom: 1px solid var(--border);
}

dt {
  color: var(--text-muted);
}

dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.danger-text {
  color: var(--danger);
  font-weight: 600;
}
</style>

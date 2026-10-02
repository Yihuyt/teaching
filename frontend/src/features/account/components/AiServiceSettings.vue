<script setup lang="ts">
/**
 * AI 服务配置面板(用户级密钥,跟人走):大模型 API Key + MinerU 令牌。
 * 密钥永不回显,只展示"已配置 + 尾号";课程内消费统一解析课程负责人的配置。
 * 被「账户设置 → AI 服务」与「管理后台 → AI 服务配置」共用,数据同一份。
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { api, errorMessage } from '@/api/client'
import type { ConfigView } from '@/api/generated'

const aiConfig = ref<ConfigView | null>(null)
const aiError = ref('')
const llmKeyInput = ref('')
const mineruTokenInput = ref('')
const savingLlm = ref(false)
const savingMineru = ref(false)

async function loadAiConfig(): Promise<void> {
  aiError.value = ''
  try {
    const response = await api.userAiConfigView()
    aiConfig.value = response.data
  } catch (error: unknown) {
    aiError.value = errorMessage(error)
  }
}

async function saveLlmKey(): Promise<void> {
  const apiKey = llmKeyInput.value.trim()
  if (!apiKey || savingLlm.value) return
  savingLlm.value = true
  try {
    const response = await api.userAiConfigUpdateLlmKey({ apiKey })
    aiConfig.value = response.data
    llmKeyInput.value = ''
    ElMessage.success('大模型 API Key 已保存(服务端加密存储)')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    savingLlm.value = false
  }
}

async function clearLlmKey(): Promise<void> {
  try {
    const response = await api.userAiConfigClearLlmKey()
    aiConfig.value = response.data
    ElMessage.success('已清除')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

async function saveMineruToken(): Promise<void> {
  const token = mineruTokenInput.value.trim()
  if (!token || savingMineru.value) return
  savingMineru.value = true
  try {
    const response = await api.userAiConfigUpdateMineruToken({ token })
    aiConfig.value = response.data
    mineruTokenInput.value = ''
    ElMessage.success('MinerU 令牌已保存(服务端加密存储)')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    savingMineru.value = false
  }
}

async function clearMineruToken(): Promise<void> {
  try {
    const response = await api.userAiConfigClearMineruToken()
    aiConfig.value = response.data
    ElMessage.success('已清除')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

onMounted(loadAiConfig)
</script>

<template>
  <div>
    <el-alert v-if="aiError" :title="aiError" type="error" :closable="false" class="ai-alert" />
    <div v-if="aiConfig" class="ai-config">
      <div class="ai-item">
        <div class="ai-item-head">
          <span class="ai-item-title">大模型 API Key(阿里云百炼 DashScope)</span>
          <el-tag v-if="aiConfig.llmConfigured" type="success" effect="plain">
            已配置 · 尾号 {{ aiConfig.llmKeyTail }}
          </el-tag>
          <el-tag v-else type="info" effect="plain">未配置</el-tag>
        </div>
        <div class="ai-item-row">
          <el-input
            v-model="llmKeyInput"
            type="password"
            show-password
            maxlength="500"
            :placeholder="aiConfig.llmConfigured ? '输入新 Key 以覆盖…' : '粘贴 DashScope API Key…'"
          />
          <el-button type="primary" :loading="savingLlm" :disabled="!llmKeyInput.trim()" @click="saveLlmKey">
            保存
          </el-button>
          <el-button v-if="aiConfig.llmConfigured" type="danger" plain @click="clearLlmKey">清除</el-button>
        </div>
      </div>
      <div class="ai-item">
        <div class="ai-item-head">
          <span class="ai-item-title">MinerU 文档解析令牌</span>
          <el-tag v-if="aiConfig.mineruConfigured" type="success" effect="plain">
            已配置 · 尾号 {{ aiConfig.mineruTokenTail }}
          </el-tag>
          <el-tag v-else type="info" effect="plain">未配置</el-tag>
        </div>
        <p class="ai-item-hint">
          令牌在 mineru.net 申请。
        </p>
        <div class="ai-item-row">
          <el-input
            v-model="mineruTokenInput"
            type="password"
            show-password
            maxlength="2000"
            :placeholder="aiConfig.mineruConfigured ? '输入新令牌以覆盖…' : '粘贴 MinerU API Token…'"
          />
          <el-button
            type="primary"
            :loading="savingMineru"
            :disabled="!mineruTokenInput.trim()"
            @click="saveMineruToken"
          >
            保存
          </el-button>
          <el-button v-if="aiConfig.mineruConfigured" type="danger" plain @click="clearMineruToken">
            清除
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-alert {
  width: 620px;
  margin-bottom: 16px;
}

.ai-config {
  width: 620px;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.ai-item-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ai-item-title {
  font-size: 14px;
  font-weight: 600;
}

.ai-item-hint {
  margin: 8px 0 10px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-muted);
}

.ai-item-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>

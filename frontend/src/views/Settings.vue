<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, type LlmConfig } from '../api'
import { ElMessage } from 'element-plus'

const form = ref<LlmConfig>({
  providerType: 'openai-compat',
  baseUrl: '',
  apiKey: '',
  model: '',
  temperature: 0.1,
  maxTokens: 8192,
  enabled: false,
})

const saving = ref(false)
const testing = ref(false)
const testResult = ref<{ ok: boolean; model?: string; response?: string; tokens?: string; error?: string } | null>(null)

const providerPresets: Record<string, { baseUrl: string; modelHint: string }> = {
  'openai-compat': { baseUrl: '', modelHint: 'e.g. gpt-4o / claude-sonnet-4-6 (via proxy) / deepseek-chat' },
  'anthropic':     { baseUrl: 'https://api.anthropic.com', modelHint: 'e.g. claude-opus-4-7 / claude-sonnet-4-6 / claude-haiku-4-5-20251001' },
  'ollama':        { baseUrl: 'http://localhost:11434/v1', modelHint: 'e.g. deepseek-coder:6.7b / llama3.2' },
  'openai':        { baseUrl: 'https://api.openai.com/v1', modelHint: 'e.g. gpt-4o / gpt-4o-mini' },
  'deepseek':      { baseUrl: 'https://api.deepseek.com/v1', modelHint: 'e.g. deepseek-chat / deepseek-reasoner' },
}

const modelHint = ref('')

const onProviderChange = (type: string) => {
  const preset = providerPresets[type]
  if (preset) {
    if (preset.baseUrl) form.value.baseUrl = preset.baseUrl
    modelHint.value = preset.modelHint
  }
}

onMounted(async () => {
  try {
    const cfg = await api.getLlmConfig()
    Object.assign(form.value, cfg)
    modelHint.value = providerPresets[cfg.providerType]?.modelHint ?? ''
  } catch {
    // no config yet
  }
})

const save = async () => {
  if (!form.value.baseUrl || !form.value.model) {
    ElMessage.error('Base URL and Model are required')
    return
  }
  saving.value = true
  try {
    const saved = await api.saveLlmConfig(form.value)
    Object.assign(form.value, saved)
    ElMessage.success('LLM configuration saved')
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error ?? 'Save failed')
  } finally {
    saving.value = false
  }
}

const test = async () => {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await api.testLlmConfig()
  } catch (e: any) {
    testResult.value = { ok: false, error: e.response?.data?.error ?? e.message }
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <div style="max-width:640px; margin:0 auto">
    <h2 style="color:#e2e8f0; margin-bottom:24px">Settings</h2>

    <el-card style="background:#1e1e3a; border:1px solid #2a2a4a">
      <template #header>
        <div style="display:flex; align-items:center; gap:8px">
          <span style="color:#e2e8f0; font-weight:600">LLM Configuration</span>
          <el-tag v-if="form.enabled" type="success" size="small">Enabled</el-tag>
          <el-tag v-else type="info" size="small">Disabled</el-tag>
        </div>
      </template>

      <el-form :model="form" label-position="top" label-width="auto"
        style="--el-text-color-regular:#94a3b8; --el-form-label-font-size:13px">

        <!-- Provider Type -->
        <el-form-item label="Provider Type">
          <el-select v-model="form.providerType" @change="onProviderChange"
            style="width:100%; --el-select-input-focus-border-color:#60a5fa"
            :popper-options="{ placement: 'bottom' }">
            <el-option label="OpenAI-Compatible (通用，推荐)" value="openai-compat" />
            <el-option label="Anthropic (Claude 官方 API)" value="anthropic" />
            <el-option label="Ollama (本地模型)" value="ollama" />
            <el-option label="OpenAI (官方)" value="openai" />
            <el-option label="DeepSeek" value="deepseek" />
          </el-select>
          <div style="color:#64748b; font-size:12px; margin-top:4px">
            openai-compat 可对接任何兼容 OpenAI 协议的端点（new-api / one-api / Claude proxy 等）
          </div>
        </el-form-item>

        <!-- Base URL -->
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="http://localhost:3000/v1"
            style="--el-input-bg-color:#0f0f23; --el-input-text-color:#e2e8f0; --el-input-placeholder-color:#475569" />
        </el-form-item>

        <!-- API Key -->
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
            placeholder="Leave unchanged to keep current key"
            style="--el-input-bg-color:#0f0f23; --el-input-text-color:#e2e8f0; --el-input-placeholder-color:#475569" />
        </el-form-item>

        <!-- Model -->
        <el-form-item :label="'Model' + (modelHint ? ' — ' + modelHint : '')">
          <el-input v-model="form.model" placeholder="model name"
            style="--el-input-bg-color:#0f0f23; --el-input-text-color:#e2e8f0; --el-input-placeholder-color:#475569" />
        </el-form-item>

        <!-- Temperature + MaxTokens -->
        <div style="display:grid; grid-template-columns:1fr 1fr; gap:16px">
          <el-form-item label="Temperature">
            <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.05" :precision="2"
              style="width:100%" />
          </el-form-item>
          <el-form-item label="Max Tokens">
            <el-input-number v-model="form.maxTokens" :min="512" :max="128000" :step="1024"
              style="width:100%" />
          </el-form-item>
        </div>

        <!-- Enable toggle -->
        <el-form-item label="Enable for Pipeline">
          <el-switch v-model="form.enabled"
            active-text="Use DB config" inactive-text="Use application.yml fallback"
            style="--el-switch-on-color:#22c55e" />
        </el-form-item>

        <!-- Actions -->
        <div style="display:flex; gap:12px; margin-top:8px">
          <el-button type="primary" :loading="saving" @click="save" style="flex:1">
            Save
          </el-button>
          <el-button :loading="testing" @click="test">
            Test Connection
          </el-button>
        </div>
      </el-form>

      <!-- Test Result -->
      <div v-if="testResult" style="margin-top:16px">
        <el-alert v-if="testResult.ok" type="success" :closable="false" show-icon>
          <template #title>
            <span>Connected — <b>{{ testResult.model }}</b></span>
          </template>
          <div style="font-family:monospace; margin-top:4px">
            Response: {{ testResult.response }} &nbsp;·&nbsp; Tokens: {{ testResult.tokens }}
          </div>
        </el-alert>
        <el-alert v-else type="error" :closable="false" show-icon
          :title="'Connection failed: ' + testResult.error" />
      </div>
    </el-card>

    <!-- Config hints -->
    <el-card style="background:#1e1e3a; border:1px solid #2a2a4a; margin-top:20px">
      <template #header>
        <span style="color:#94a3b8; font-size:13px">Quick Reference</span>
      </template>
      <div style="font-size:12px; color:#64748b; line-height:2">
        <div><b style="color:#94a3b8">Anthropic (Claude 官方)：</b>Base URL 填 <code>https://api.anthropic.com</code>，API Key 填 <code>sk-ant-...</code>，Model 填 <code>claude-sonnet-4-6</code></div>
        <div><b style="color:#94a3b8">Ollama (本地)：</b>先运行 <code style="color:#60a5fa">ollama serve</code>，Base URL 填 <code>http://localhost:11434/v1</code>，API Key 留空</div>
        <div><b style="color:#94a3b8">new-api / one-api 中转：</b>Base URL 填中转地址，API Key 填中转 Token，Model 填目标模型名</div>
        <div><b style="color:#94a3b8">DeepSeek API：</b>Base URL 填 <code>https://api.deepseek.com/v1</code>，Model 填 <code>deepseek-chat</code></div>
        <div><b style="color:#94a3b8">禁用后</b>将使用 application.yml 中的 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL 环境变量</div>
      </div>
    </el-card>
  </div>
</template>

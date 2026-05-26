<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, type LlmConfig } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const configs = ref<LlmConfig[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const editingId = ref<number | null>(null)
const testingId = ref<number | null>(null)
const activatingId = ref<number | null>(null)
const testResults = ref<Record<number, { ok: boolean; model?: string; response?: string; tokens?: string; error?: string }>>({})

const emptyForm = (): Omit<LlmConfig, 'id' | 'active'> => ({
  name: '',
  providerType: 'openai-compat',
  baseUrl: '',
  apiKey: '',
  model: '',
  temperature: 0.1,
  maxTokens: 8192,
})

const form = ref(emptyForm())
const saving = ref(false)
const modelHint = ref('')

const providerPresets: Record<string, { baseUrl: string; modelHint: string }> = {
  'openai-compat': { baseUrl: '', modelHint: 'e.g. gpt-4o / claude-sonnet-4-6 (via proxy) / deepseek-chat' },
  'anthropic':     { baseUrl: 'https://api.anthropic.com', modelHint: 'e.g. claude-opus-4-7 / claude-sonnet-4-6 / claude-haiku-4-5-20251001' },
  'ollama':        { baseUrl: 'http://localhost:11434/v1', modelHint: 'e.g. deepseek-coder:6.7b / llama3.2' },
  'openai':        { baseUrl: 'https://api.openai.com/v1', modelHint: 'e.g. gpt-4o / gpt-4o-mini' },
  'deepseek':      { baseUrl: 'https://api.deepseek.com/v1', modelHint: 'e.g. deepseek-chat / deepseek-reasoner' },
}

const onProviderChange = (type: string) => {
  const preset = providerPresets[type]
  if (preset) {
    if (preset.baseUrl && !form.value.baseUrl) form.value.baseUrl = preset.baseUrl
    modelHint.value = preset.modelHint
  }
}

const loadConfigs = async () => {
  loading.value = true
  try {
    configs.value = await api.listLlmConfigs()
  } catch {
    ElMessage.error('Failed to load configurations')
  } finally {
    loading.value = false
  }
}

onMounted(loadConfigs)

const openAdd = () => {
  dialogMode.value = 'add'
  editingId.value = null
  form.value = emptyForm()
  modelHint.value = providerPresets['openai-compat']?.modelHint ?? ''
  dialogVisible.value = true
}

const openEdit = (cfg: LlmConfig) => {
  dialogMode.value = 'edit'
  editingId.value = cfg.id!
  form.value = {
    name: cfg.name ?? '',
    providerType: cfg.providerType,
    baseUrl: cfg.baseUrl ?? '',
    apiKey: cfg.apiKey ?? '',
    model: cfg.model ?? '',
    temperature: cfg.temperature ?? 0.1,
    maxTokens: cfg.maxTokens ?? 8192,
  }
  modelHint.value = providerPresets[cfg.providerType]?.modelHint ?? ''
  dialogVisible.value = true
}

const saveForm = async () => {
  if (!form.value.baseUrl || !form.value.model) {
    ElMessage.error('Base URL and Model are required')
    return
  }
  saving.value = true
  try {
    if (dialogMode.value === 'add') {
      await api.createLlmConfig(form.value)
      ElMessage.success('Configuration added')
    } else {
      await api.updateLlmConfig(editingId.value!, form.value)
      ElMessage.success('Configuration updated')
    }
    dialogVisible.value = false
    await loadConfigs()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error ?? 'Save failed')
  } finally {
    saving.value = false
  }
}

const activate = async (cfg: LlmConfig) => {
  activatingId.value = cfg.id!
  try {
    await api.activateLlmConfig(cfg.id!)
    ElMessage.success(`"${cfg.name || cfg.model}" set as active`)
    await loadConfigs()
  } catch {
    ElMessage.error('Failed to activate')
  } finally {
    activatingId.value = null
  }
}

const testConfig = async (cfg: LlmConfig) => {
  testingId.value = cfg.id!
  delete testResults.value[cfg.id!]
  try {
    const result = await api.testLlmConfig(cfg.id!)
    testResults.value = { ...testResults.value, [cfg.id!]: result }
  } catch (e: any) {
    testResults.value = { ...testResults.value, [cfg.id!]: { ok: false, error: e.response?.data?.error ?? e.message } }
  } finally {
    testingId.value = null
  }
}

const deleteConfig = async (cfg: LlmConfig) => {
  try {
    await ElMessageBox.confirm(
      `Delete "${cfg.name || cfg.model}"?`,
      'Confirm Delete',
      { confirmButtonText: 'Delete', cancelButtonText: 'Cancel', type: 'warning' }
    )
    await api.deleteLlmConfig(cfg.id!)
    ElMessage.success('Deleted')
    await loadConfigs()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('Delete failed')
  }
}

const providerLabel = (type: string) => {
  const labels: Record<string, string> = {
    'openai-compat': 'OpenAI-Compat',
    'anthropic': 'Anthropic',
    'ollama': 'Ollama',
    'openai': 'OpenAI',
    'deepseek': 'DeepSeek',
  }
  return labels[type] ?? type
}
</script>

<template>
  <div style="max-width:900px; margin:0 auto">
    <h2 style="margin:0 0 24px; font-family:var(--font-mono); font-size:18px">Settings</h2>

    <!-- LLM Configurations -->
    <el-card>
      <template #header>
        <div style="display:flex; align-items:center; justify-content:space-between">
          <span style="font-family:var(--font-mono); font-size:11px; color:var(--text-disabled); letter-spacing:1px">
            LLM CONFIGURATIONS
          </span>
          <el-button type="primary" size="small" @click="openAdd">+ Add New</el-button>
        </div>
      </template>

      <el-table :data="configs" v-loading="loading" style="width:100%"
        :row-class-name="(row: any) => row.row.active ? 'active-row' : ''">

        <el-table-column label="Name" min-width="140">
          <template #default="{ row }">
            <div style="display:flex; align-items:center; gap:8px">
              <span>{{ row.name || '(unnamed)' }}</span>
              <span v-if="row.active" class="jv-tag jv-tag-completed">Active</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="Provider" width="140">
          <template #default="{ row }">
            <span class="jv-tag jv-tag-pending">{{ providerLabel(row.providerType) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="Model" min-width="180">
          <template #default="{ row }">
            <span style="font-family:var(--font-mono); font-size:12px; color:var(--text-muted)">
              {{ row.model || '—' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="Base URL" min-width="200">
          <template #default="{ row }">
            <span style="font-family:var(--font-mono); font-size:11px; color:var(--text-disabled)">
              {{ row.baseUrl ? (row.baseUrl.length > 35 ? row.baseUrl.substring(0, 35) + '…' : row.baseUrl) : '—' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="" width="280" align="right">
          <template #default="{ row }">
            <div style="display:flex; gap:6px; justify-content:flex-end">
              <el-button v-if="!row.active" size="small" type="success"
                :loading="activatingId === row.id" @click="activate(row)">
                Activate
              </el-button>
              <el-button size="small" :loading="testingId === row.id" @click="testConfig(row)">Test</el-button>
              <el-button size="small" @click="openEdit(row)">Edit</el-button>
              <el-button size="small" type="danger" plain @click="deleteConfig(row)">Delete</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <!-- Test results inline below table -->
      <div v-for="cfg in configs" :key="'tr-' + cfg.id">
        <div v-if="testResults[cfg.id!]" class="jv-test-result"
          :class="testResults[cfg.id!].ok ? 'jv-test-ok' : 'jv-test-fail'">
          <span class="jv-test-label">
            {{ cfg.name || cfg.model }}:
            {{ testResults[cfg.id!].ok ? '✓ Connected' : '✗ Failed' }}
          </span>
          <span v-if="testResults[cfg.id!].ok" style="font-family:var(--font-mono); font-size:12px; color:var(--text-secondary)">
            {{ testResults[cfg.id!].model }} · "{{ testResults[cfg.id!].response }}" · {{ testResults[cfg.id!].tokens }} tokens
          </span>
          <span v-else style="font-size:12px; color:var(--critical)">{{ testResults[cfg.id!].error }}</span>
        </div>
      </div>

      <div v-if="configs.length === 0 && !loading" style="text-align:center; color:var(--text-disabled); padding:32px 0; font-size:13px">
        No configurations yet. Click <b style="color:var(--text-muted)">+ Add New</b> to get started.
      </div>
    </el-card>

    <!-- Add / Edit Dialog -->
    <el-dialog v-model="dialogVisible"
      :title="dialogMode === 'add' ? 'Add LLM Configuration' : 'Edit LLM Configuration'"
      width="540px">

      <el-form :model="form" label-position="top">
        <el-form-item label="Config Name (optional)">
          <el-input v-model="form.name" placeholder="e.g. new-api claude / local ollama" />
        </el-form-item>

        <el-form-item label="Provider Type">
          <el-select v-model="form.providerType" @change="onProviderChange" style="width:100%">
            <el-option label="OpenAI-Compatible (通用，推荐)" value="openai-compat" />
            <el-option label="Anthropic (Claude 官方 API)" value="anthropic" />
            <el-option label="Ollama (本地模型)" value="ollama" />
            <el-option label="OpenAI (官方)" value="openai" />
            <el-option label="DeepSeek" value="deepseek" />
          </el-select>
        </el-form-item>

        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="http://localhost:3000/v1" />
        </el-form-item>

        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
            placeholder="Leave unchanged to keep current key" />
        </el-form-item>

        <el-form-item :label="'Model' + (modelHint ? ' — ' + modelHint : '')">
          <el-input v-model="form.model" placeholder="model name" />
        </el-form-item>

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
      </el-form>

      <template #footer>
        <div style="display:flex; gap:12px; justify-content:flex-end">
          <el-button @click="dialogVisible = false">Cancel</el-button>
          <el-button type="primary" :loading="saving" @click="saveForm">Save</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- Quick Reference -->
    <el-card style="margin-top:20px">
      <template #header>
        <span style="font-family:var(--font-mono); font-size:11px; color:var(--text-disabled); letter-spacing:1px">
          QUICK REFERENCE
        </span>
      </template>
      <div class="jv-ref-table">
        <div class="jv-ref-row">
          <span class="jv-ref-label">Anthropic (Claude 官方)</span>
          <span class="jv-ref-val">Base URL: <code>https://api.anthropic.com</code> · Model: <code>claude-sonnet-4-6</code></span>
        </div>
        <div class="jv-ref-row">
          <span class="jv-ref-label">Ollama (本地)</span>
          <span class="jv-ref-val">先运行 <code>ollama serve</code> · Base URL: <code>http://localhost:11434/v1</code> · API Key 留空</span>
        </div>
        <div class="jv-ref-row">
          <span class="jv-ref-label">new-api / one-api 中转</span>
          <span class="jv-ref-val">Base URL 填中转地址，API Key 填中转 Token，Model 填目标模型名</span>
        </div>
        <div class="jv-ref-row">
          <span class="jv-ref-label">DeepSeek API</span>
          <span class="jv-ref-val">Base URL: <code>https://api.deepseek.com/v1</code> · Model: <code>deepseek-chat</code></span>
        </div>
        <div class="jv-ref-row" style="border-bottom:none">
          <span class="jv-ref-label">Active 配置</span>
          <span class="jv-ref-val">将被 Pipeline AI 阶段使用；未激活的配置可用 Test 验证连通性</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
:deep(.active-row td) {
  background: #1c3a29 !important;
}

.jv-test-result {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 8px 0 4px;
  padding: 10px 12px;
  border-left: 3px solid;
}
.jv-test-ok   { background: rgba(66,190,101,.08); border-color: var(--success); }
.jv-test-fail { background: rgba(250,77,86,.08);  border-color: var(--critical); }
.jv-test-label {
  font-weight: 600;
  font-size: 13px;
  font-family: var(--font-mono);
}
.jv-test-ok   .jv-test-label { color: var(--success); }
.jv-test-fail .jv-test-label { color: var(--critical); }

.jv-ref-table { font-size: 12px; }
.jv-ref-row {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 16px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border-subtle);
  align-items: start;
}
.jv-ref-label {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-muted);
  font-weight: 500;
  padding-top: 1px;
}
.jv-ref-val { color: var(--text-disabled); line-height: 1.8; }
.jv-ref-val code {
  color: var(--accent-light);
  font-family: var(--font-mono);
  font-size: 11px;
  background: rgba(15,98,254,.1);
  padding: 1px 5px;
}
</style>

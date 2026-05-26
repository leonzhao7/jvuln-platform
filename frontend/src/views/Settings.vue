<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, type LlmConfig } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from '../i18n'

const { t } = useI18n()
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
    ElMessage.error(t('settings.loadFailed'))
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
    ElMessage.error(t('settings.baseUrlAndModelRequired'))
    return
  }
  saving.value = true
  try {
    if (dialogMode.value === 'add') {
      await api.createLlmConfig(form.value)
      ElMessage.success(t('settings.addSuccess'))
    } else {
      await api.updateLlmConfig(editingId.value!, form.value)
      ElMessage.success(t('settings.updateSuccess'))
    }
    dialogVisible.value = false
    await loadConfigs()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error ?? t('settings.saveFailed'))
  } finally {
    saving.value = false
  }
}

const activate = async (cfg: LlmConfig) => {
  activatingId.value = cfg.id!
  try {
    await api.activateLlmConfig(cfg.id!)
    ElMessage.success(t('settings.activateSuccess', { name: cfg.name || cfg.model }))
    await loadConfigs()
  } catch {
    ElMessage.error(t('settings.activateFailed'))
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
      t('settings.deleteConfirm', { name: cfg.name || cfg.model }),
      t('settings.confirmDelete'),
      { confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel'), type: 'warning' }
    )
    await api.deleteLlmConfig(cfg.id!)
    ElMessage.success(t('settings.deleteSuccess'))
    await loadConfigs()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(t('settings.deleteFailed'))
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
    <h2 style="margin:0 0 24px; font-family:var(--font-mono); font-size:18px">{{ t('settings.title') }}</h2>

    <!-- LLM Configurations -->
    <el-card>
      <template #header>
        <div style="display:flex; align-items:center; justify-content:space-between">
          <span style="font-family:var(--font-mono); font-size:11px; color:var(--text-disabled); letter-spacing:1px">
            {{ t('settings.llmConfigurations') }}
          </span>
          <el-button type="primary" size="small" @click="openAdd">{{ t('settings.addNew') }}</el-button>
        </div>
      </template>

      <el-table :data="configs" v-loading="loading" style="width:100%"
        :row-class-name="(row: any) => row.row.active ? 'active-row' : ''">

        <el-table-column :label="t('settings.name')" min-width="140">
          <template #default="{ row }">
            <div style="display:flex; align-items:center; gap:8px">
              <span>{{ row.name || t('settings.unnamed') }}</span>
              <span v-if="row.active" class="jv-tag jv-tag-completed">{{ t('common.active') }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="t('settings.provider')" width="140">
          <template #default="{ row }">
            <span class="jv-tag jv-tag-pending">{{ providerLabel(row.providerType) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('settings.model')" min-width="180">
          <template #default="{ row }">
            <span style="font-family:var(--font-mono); font-size:12px; color:var(--text-muted)">
              {{ row.model || '—' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column :label="t('settings.baseUrl')" min-width="200">
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
                {{ t('common.activate') }}
              </el-button>
              <el-button size="small" :loading="testingId === row.id" @click="testConfig(row)">{{ t('common.test') }}</el-button>
              <el-button size="small" @click="openEdit(row)">{{ t('common.edit') }}</el-button>
              <el-button size="small" type="danger" plain @click="deleteConfig(row)">{{ t('common.delete') }}</el-button>
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
            {{ testResults[cfg.id!].ok ? t('settings.connected') : t('settings.failed') }}
          </span>
          <span v-if="testResults[cfg.id!].ok" style="font-family:var(--font-mono); font-size:12px; color:var(--text-secondary)">
            {{ testResults[cfg.id!].model }} · "{{ testResults[cfg.id!].response }}" · {{ testResults[cfg.id!].tokens }} tokens
          </span>
          <span v-else style="font-size:12px; color:var(--critical)">{{ testResults[cfg.id!].error }}</span>
        </div>
      </div>

      <div v-if="configs.length === 0 && !loading" style="text-align:center; color:var(--text-disabled); padding:32px 0; font-size:13px">
        {{ t('settings.empty') }}
      </div>
    </el-card>

    <!-- Add / Edit Dialog -->
    <el-dialog v-model="dialogVisible"
      :title="dialogMode === 'add' ? t('settings.addTitle') : t('settings.editTitle')"
      width="540px">

      <el-form :model="form" label-position="top">
        <el-form-item :label="t('settings.configName')">
          <el-input v-model="form.name" :placeholder="t('settings.configNamePlaceholder')" />
        </el-form-item>

        <el-form-item :label="t('settings.providerType')">
          <el-select v-model="form.providerType" @change="onProviderChange" style="width:100%">
            <el-option :label="t('settings.providerOptions.openaiCompat')" value="openai-compat" />
            <el-option :label="t('settings.providerOptions.anthropic')" value="anthropic" />
            <el-option :label="t('settings.providerOptions.ollama')" value="ollama" />
            <el-option :label="t('settings.providerOptions.openai')" value="openai" />
            <el-option :label="t('settings.providerOptions.deepseek')" value="deepseek" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('settings.baseUrl')">
          <el-input v-model="form.baseUrl" placeholder="http://localhost:3000/v1" />
        </el-form-item>

        <el-form-item :label="t('settings.apiKey')">
          <el-input v-model="form.apiKey" type="password" show-password
            :placeholder="t('settings.apiKeyPlaceholder')" />
        </el-form-item>

        <el-form-item :label="t('settings.model') + (modelHint ? ' — ' + modelHint : '')">
          <el-input v-model="form.model" :placeholder="t('settings.modelPlaceholder')" />
        </el-form-item>

        <div style="display:grid; grid-template-columns:1fr 1fr; gap:16px">
          <el-form-item :label="t('settings.temperature')">
            <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.05" :precision="2"
              style="width:100%" />
          </el-form-item>
          <el-form-item :label="t('settings.maxTokens')">
            <el-input-number v-model="form.maxTokens" :min="512" :max="128000" :step="1024"
              style="width:100%" />
          </el-form-item>
        </div>
      </el-form>

      <template #footer>
        <div style="display:flex; gap:12px; justify-content:flex-end">
          <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" @click="saveForm">{{ t('common.save') }}</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- Quick Reference -->
    <el-card style="margin-top:20px">
      <template #header>
        <span style="font-family:var(--font-mono); font-size:11px; color:var(--text-disabled); letter-spacing:1px">
          {{ t('settings.quickReference') }}
        </span>
      </template>
      <div class="jv-ref-table">
        <div class="jv-ref-row">
          <span class="jv-ref-label">{{ t('settings.refs.anthropicLabel') }}</span>
          <span class="jv-ref-val">{{ t('settings.refs.anthropicText', { baseUrl: 'https://api.anthropic.com', model: 'claude-sonnet-4-6' }) }}</span>
        </div>
        <div class="jv-ref-row">
          <span class="jv-ref-label">{{ t('settings.refs.ollamaLabel') }}</span>
          <span class="jv-ref-val">{{ t('settings.refs.ollamaText', { command: 'ollama serve', baseUrl: 'http://localhost:11434/v1' }) }}</span>
        </div>
        <div class="jv-ref-row">
          <span class="jv-ref-label">{{ t('settings.refs.proxyLabel') }}</span>
          <span class="jv-ref-val">{{ t('settings.refs.proxyText') }}</span>
        </div>
        <div class="jv-ref-row">
          <span class="jv-ref-label">{{ t('settings.refs.deepseekLabel') }}</span>
          <span class="jv-ref-val">{{ t('settings.refs.deepseekText', { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' }) }}</span>
        </div>
        <div class="jv-ref-row" style="border-bottom:none">
          <span class="jv-ref-label">{{ t('settings.refs.activeLabel') }}</span>
          <span class="jv-ref-val">{{ t('settings.refs.activeText') }}</span>
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

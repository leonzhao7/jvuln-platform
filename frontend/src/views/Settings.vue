<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, type LlmConfig, type JavaProfile, type ProxySettings } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from '../i18n'
import {
  BrainCircuit, Coffee, Network, Plus, Plug, Pencil, Trash2,
  CheckCircle2, XCircle, SlidersHorizontal,
} from 'lucide-vue-next'

const router = useRouter()
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
  baseUrl: '',
  apiKey: '',
  model: '',
  endpoint: '/v1/chat/completions',
  userAgent: '',
})

const form = ref(emptyForm())
const saving = ref(false)

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

const openAdd = () => {
  dialogMode.value = 'add'
  editingId.value = null
  form.value = emptyForm()
  dialogVisible.value = true
}

const openEdit = (cfg: LlmConfig) => {
  dialogMode.value = 'edit'
  editingId.value = cfg.id!
  form.value = {
    name: cfg.name ?? '',
    baseUrl: cfg.baseUrl ?? '',
    apiKey: cfg.apiKey ?? '',
    model: cfg.model ?? '',
    endpoint: cfg.endpoint ?? '/v1/chat/completions',
    userAgent: cfg.userAgent ?? '',
  }
  dialogVisible.value = true
}

const saveForm = async () => {
  if (!form.value.baseUrl || !form.value.model || !form.value.endpoint) {
    ElMessage.error(t('settings.baseUrlModelEndpointRequired'))
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

/* ── Java Profiles ── */
const javaProfiles = ref<JavaProfile[]>([])
const jpLoading = ref(false)
const jpDialogVisible = ref(false)
const jpDialogMode = ref<'add' | 'edit'>('add')
const jpEditingId = ref<number | null>(null)
const jpSaving = ref(false)

const javaVersionOptions = ['8', '11', '17', '21']

const emptyJpForm = (): Omit<JavaProfile, 'id' | 'isDefault'> => ({
  name: '',
  javaVersion: '8',
  javaHome: '',
  springBootVersion: '',
  mavenJavaVersion: '',
  syntaxConstraints: '',
})

const jpForm = ref(emptyJpForm())

const mavenVersionMap: Record<string, string> = { '8': '1.8', '11': '11', '17': '17', '21': '21' }

const onJavaVersionChange = (v: string) => {
  jpForm.value.mavenJavaVersion = mavenVersionMap[v] ?? v
}

const loadJavaProfiles = async () => {
  jpLoading.value = true
  try {
    javaProfiles.value = await api.listJavaProfiles()
  } catch {
    ElMessage.error(t('javaProfiles.loadFailed'))
  } finally {
    jpLoading.value = false
  }
}

const openJpAdd = () => {
  jpDialogMode.value = 'add'
  jpEditingId.value = null
  jpForm.value = emptyJpForm()
  jpDialogVisible.value = true
}

const openJpEdit = (p: JavaProfile) => {
  jpDialogMode.value = 'edit'
  jpEditingId.value = p.id!
  jpForm.value = {
    name: p.name,
    javaVersion: p.javaVersion,
    javaHome: p.javaHome,
    springBootVersion: p.springBootVersion,
    mavenJavaVersion: p.mavenJavaVersion,
    syntaxConstraints: p.syntaxConstraints ?? '',
  }
  jpDialogVisible.value = true
}

const saveJpForm = async () => {
  if (!jpForm.value.name) {
    ElMessage.error(t('javaProfiles.nameRequired'))
    return
  }
  if (!jpForm.value.javaHome) {
    ElMessage.error(t('javaProfiles.javaHomeRequired'))
    return
  }
  jpSaving.value = true
  try {
    if (jpDialogMode.value === 'add') {
      await api.createJavaProfile(jpForm.value)
      ElMessage.success(t('javaProfiles.addSuccess'))
    } else {
      await api.updateJavaProfile(jpEditingId.value!, jpForm.value)
      ElMessage.success(t('javaProfiles.updateSuccess'))
    }
    jpDialogVisible.value = false
    await loadJavaProfiles()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error ?? t('javaProfiles.saveFailed'))
  } finally {
    jpSaving.value = false
  }
}

const deleteProfile = async (p: JavaProfile) => {
  try {
    await ElMessageBox.confirm(
      t('javaProfiles.deleteConfirm', { name: p.name }),
      t('javaProfiles.confirmDelete'),
      { confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel'), type: 'warning' }
    )
    await api.deleteJavaProfile(p.id!)
    ElMessage.success(t('javaProfiles.deleteSuccess'))
    await loadJavaProfiles()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(t('javaProfiles.deleteFailed'))
  }
}

/* ── Proxy & Timeouts ── */
const emptyProxyForm = (): ProxySettings => ({
  proxyType: 'NONE',
  proxyHost: '',
  proxyPort: null,
  proxyScope: 'url',
  urlConnectTimeout: 5000,
  urlReadTimeout: 8000,
  llmTimeout: 300000,
})

const proxyForm = ref<ProxySettings>(emptyProxyForm())
const proxyScopeList = ref<string[]>(['url'])
const proxySaving = ref(false)
const proxyTesting = ref(false)
const proxyTestResult = ref<{ ok: boolean; message?: string; error?: string } | null>(null)

const loadProxy = async () => {
  try {
    const s = await api.getProxySettings()
    proxyForm.value = { ...emptyProxyForm(), ...s }
    proxyScopeList.value = (proxyForm.value.proxyScope || '')
      .split(',').map(x => x.trim()).filter(Boolean)
  } catch {
    ElMessage.error(t('proxy.loadFailed'))
  }
}

const saveProxy = async () => {
  if (proxyForm.value.proxyType !== 'NONE'
      && (!proxyForm.value.proxyHost || !proxyForm.value.proxyPort)) {
    ElMessage.error(t('proxy.hostPortRequired'))
    return
  }
  proxySaving.value = true
  try {
    proxyForm.value.proxyScope = proxyScopeList.value.join(',')
    const s = await api.updateProxySettings(proxyForm.value)
    proxyForm.value = { ...emptyProxyForm(), ...s }
    proxyScopeList.value = (proxyForm.value.proxyScope || '')
      .split(',').map(x => x.trim()).filter(Boolean)
    ElMessage.success(t('proxy.saveSuccess'))
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error ?? t('proxy.saveFailed'))
  } finally {
    proxySaving.value = false
  }
}

const testProxy = async () => {
  if (proxyForm.value.proxyType !== 'NONE'
      && (!proxyForm.value.proxyHost || !proxyForm.value.proxyPort)) {
    ElMessage.error(t('proxy.hostPortRequired'))
    return
  }
  proxyTesting.value = true
  proxyTestResult.value = null
  try {
    proxyForm.value.proxyScope = proxyScopeList.value.join(',')
    proxyTestResult.value = await api.testProxy(proxyForm.value)
  } catch (e: any) {
    proxyTestResult.value = { ok: false, error: e.response?.data?.error ?? e.message }
  } finally {
    proxyTesting.value = false
  }
}

onMounted(() => {
  loadConfigs()
  loadJavaProfiles()
  loadProxy()
})
</script>

<template>
  <div class="jv-settings">
    <div class="jv-settings-header">
      <div>
        <h2 class="jv-settings-title">{{ t('settings.title') }}</h2>
      </div>
      <div class="jv-panel-head-spacer" />
      <span class="jv-settings-chip">
        <SlidersHorizontal :size="11" :stroke-width="2.2" />
        {{ configs.length }} LLM / {{ javaProfiles.length }} JDK
      </span>
    </div>

    <!-- LLM Configurations -->
    <el-card>
      <template #header>
        <div class="jv-card-head">
          <span class="jv-panel-head-icon"><BrainCircuit :size="14" :stroke-width="2" /></span>
          <span class="jv-panel-head-title">{{ t('settings.llmConfigurations') }}</span>
          <div class="jv-panel-head-spacer" />
          <button class="jv-bar-cta" style="height: 32px; padding: 0 14px; font-size: 12px;" @click="openAdd">
            <Plus :size="13" :stroke-width="2.4" />
            {{ t('settings.addNew') }}
          </button>
        </div>
      </template>

      <el-table :data="configs" v-loading="loading" style="width:100%"
        :row-class-name="(row: any) => row.row.active ? 'active-row' : ''">

        <el-table-column label="" width="64" align="center">
          <template #default="{ row }">
            <el-radio
              :model-value="row.active"
              :value="true"
              :disabled="activatingId === row.id"
              @change="!row.active && activate(row)"
            ><span></span></el-radio>
          </template>
        </el-table-column>

        <el-table-column :label="t('settings.name')" min-width="140">
          <template #default="{ row }">
            <span>{{ row.name || t('settings.unnamed') }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('settings.endpoint')" min-width="190">
          <template #default="{ row }">
            <span class="jv-cell-mono">{{ row.endpoint || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('settings.model')" min-width="180">
          <template #default="{ row }">
            <span class="jv-cell-mono is-strong">{{ row.model || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('settings.baseUrl')" min-width="200">
          <template #default="{ row }">
            <span class="jv-cell-mono is-dim">
              {{ row.baseUrl ? (row.baseUrl.length > 35 ? row.baseUrl.substring(0, 35) + '…' : row.baseUrl) : '—' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="" width="200" align="right">
          <template #default="{ row }">
            <div class="jv-row-actions">
              <el-button size="small" :loading="testingId === row.id" @click="testConfig(row)">
                <Plug v-if="testingId !== row.id" :size="12" :stroke-width="2.2" style="margin-right:5px" />
                {{ t('common.test') }}
              </el-button>
              <el-button size="small" @click="openEdit(row)">
                <Pencil :size="12" :stroke-width="2.2" style="margin-right:5px" />
                {{ t('common.edit') }}
              </el-button>
              <el-button size="small" type="danger" plain @click="deleteConfig(row)">
                <Trash2 :size="12" :stroke-width="2.2" style="margin-right:5px" />
                {{ t('common.delete') }}
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <!-- Test results inline below table -->
      <div v-for="cfg in configs" :key="'tr-' + cfg.id">
        <div v-if="testResults[cfg.id!]" class="jv-test-result"
          :class="testResults[cfg.id!].ok ? 'jv-test-ok' : 'jv-test-fail'">
          <span class="jv-test-label">
            <CheckCircle2 v-if="testResults[cfg.id!].ok" :size="13" :stroke-width="2.2" />
            <XCircle v-else :size="13" :stroke-width="2.2" />
            {{ cfg.name || cfg.model }}:
            {{ testResults[cfg.id!].ok ? t('settings.connected') : t('settings.failed') }}
          </span>
          <span v-if="testResults[cfg.id!].ok" class="jv-test-detail">
            {{ testResults[cfg.id!].model }} · "{{ testResults[cfg.id!].response }}" · {{ testResults[cfg.id!].tokens }} tokens
          </span>
          <span v-else class="jv-test-detail is-error">{{ testResults[cfg.id!].error }}</span>
        </div>
      </div>

      <div v-if="configs.length === 0 && !loading" class="jv-settings-empty">
        {{ t('settings.empty') }}
      </div>
    </el-card>

    <!-- Add / Edit Dialog -->
    <el-dialog v-model="dialogVisible"
      :title="dialogMode === 'add' ? t('settings.addTitle') : t('settings.editTitle')"
      width="680px">

      <el-form :model="form" label-position="top">
        <el-form-item :label="t('settings.configName')">
          <el-input v-model="form.name" :placeholder="t('settings.configNamePlaceholder')" />
        </el-form-item>

        <el-form-item :label="t('settings.baseUrl')">
          <el-input v-model="form.baseUrl" placeholder="http://localhost:3000/v1" />
        </el-form-item>

        <el-form-item :label="t('settings.apiKey')">
          <el-input v-model="form.apiKey" type="password" show-password
            :placeholder="t('settings.apiKeyPlaceholder')" />
        </el-form-item>

        <div class="jv-field-grid is-2">
          <el-form-item :label="t('settings.model')">
            <el-input v-model="form.model" :placeholder="t('settings.modelPlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('settings.endpoint')">
            <el-select v-model="form.endpoint" style="width:100%" :teleported="false">
              <el-option label="/v1/chat/completions" value="/v1/chat/completions" />
              <el-option label="/v1/responses" value="/v1/responses" />
              <el-option label="/v1/messages" value="/v1/messages" />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item :label="t('settings.userAgent')">
          <el-input v-model="form.userAgent" :placeholder="t('settings.userAgentPlaceholder')" />
        </el-form-item>

      </el-form>

      <template #footer>
        <div class="jv-dialog-actions">
          <button class="jv-btn-secondary" @click="dialogVisible = false">{{ t('common.cancel') }}</button>
          <button class="jv-bar-cta" :disabled="saving" @click="saveForm">{{ t('common.save') }}</button>
        </div>
      </template>
    </el-dialog>

    <!-- Java Profiles -->
    <el-card class="jv-settings-card">
      <template #header>
        <div class="jv-card-head">
          <span class="jv-panel-head-icon"><Coffee :size="14" :stroke-width="2" /></span>
          <span class="jv-panel-head-title">{{ t('javaProfiles.title') }}</span>
          <div class="jv-panel-head-spacer" />
          <button class="jv-bar-cta" style="height: 32px; padding: 0 14px; font-size: 12px;" @click="openJpAdd">
            <Plus :size="13" :stroke-width="2.4" />
            {{ t('javaProfiles.addNew') }}
          </button>
        </div>
      </template>

      <el-table :data="javaProfiles" v-loading="jpLoading" style="width:100%"
        :row-class-name="(row: any) => row.row.isDefault ? 'active-row' : ''">

        <el-table-column :label="t('javaProfiles.name')" min-width="120">
          <template #default="{ row }">
            <div class="jv-cell-flex">
              <span>{{ row.name }}</span>
              <span v-if="row.isDefault" class="jv-tag jv-tag-completed">{{ t('javaProfiles.isDefault') }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="t('javaProfiles.javaVersion')" width="110">
          <template #default="{ row }">
            <span class="jv-tag jv-tag-pending">Java {{ row.javaVersion }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('javaProfiles.javaHome')" min-width="220">
          <template #default="{ row }">
            <span class="jv-cell-mono is-dim">
              {{ row.javaHome ? (row.javaHome.length > 40 ? row.javaHome.substring(0, 40) + '…' : row.javaHome) : '—' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="" width="160" align="right">
          <template #default="{ row }">
            <div class="jv-row-actions">
              <el-button size="small" @click="openJpEdit(row)">
                <Pencil :size="12" :stroke-width="2.2" style="margin-right:5px" />
                {{ t('common.edit') }}
              </el-button>
              <el-button size="small" type="danger" plain @click="deleteProfile(row)">
                <Trash2 :size="12" :stroke-width="2.2" style="margin-right:5px" />
                {{ t('common.delete') }}
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="javaProfiles.length === 0 && !jpLoading" class="jv-settings-empty">
        {{ t('javaProfiles.empty') }}
      </div>
    </el-card>

    <!-- Java Profile Add / Edit Dialog -->
    <el-dialog v-model="jpDialogVisible"
      :title="jpDialogMode === 'add' ? t('javaProfiles.addTitle') : t('javaProfiles.editTitle')"
      width="680px">

      <el-form :model="jpForm" label-position="top">
        <el-form-item :label="t('javaProfiles.name')">
          <el-input v-model="jpForm.name" placeholder="Java 17" />
        </el-form-item>

        <el-form-item :label="t('javaProfiles.javaVersion')">
          <el-select v-model="jpForm.javaVersion" @change="onJavaVersionChange" style="width:100%" :teleported="false">
            <el-option v-for="v in javaVersionOptions" :key="v" :label="'Java ' + v" :value="v" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('javaProfiles.javaHome')">
          <el-input v-model="jpForm.javaHome" :placeholder="t('javaProfiles.javaHomePlaceholder')" />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="jv-dialog-actions">
          <el-button @click="jpDialogVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="jpSaving" @click="saveJpForm">{{ t('common.save') }}</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- Proxy & Timeouts -->
    <el-card class="jv-settings-card">
      <template #header>
        <div class="jv-card-head">
          <span class="jv-panel-head-icon"><Network :size="14" :stroke-width="2" /></span>
          <span class="jv-panel-head-title">{{ t('proxy.title') }}</span>
        </div>
      </template>

      <el-form :model="proxyForm" label-position="top" class="jv-proxy-form">
        <div class="jv-field-grid">
          <el-form-item :label="t('proxy.proxyType')">
            <el-select v-model="proxyForm.proxyType" style="width:100%" :teleported="false">
              <el-option label="NONE" value="NONE" />
              <el-option label="SOCKS5" value="SOCKS5" />
              <el-option label="SOCKS4" value="SOCKS4" />
              <el-option label="HTTP" value="HTTP" />
            </el-select>
          </el-form-item>

          <el-form-item :label="t('proxy.proxyHost')">
            <el-input v-model="proxyForm.proxyHost" :placeholder="t('proxy.proxyHostPlaceholder')"
              :disabled="proxyForm.proxyType === 'NONE'" />
          </el-form-item>

          <el-form-item :label="t('proxy.proxyPort')">
            <el-input v-model.number="proxyForm.proxyPort" type="number" :placeholder="t('proxy.proxyPortPlaceholder')"
              :disabled="proxyForm.proxyType === 'NONE'" />
          </el-form-item>
        </div>

        <el-form-item :label="t('proxy.proxyScope')">
          <el-checkbox-group v-model="proxyScopeList" :disabled="proxyForm.proxyType === 'NONE'">
            <el-checkbox label="llm">{{ t('proxy.scopeLlm') }}</el-checkbox>
            <el-checkbox label="url">{{ t('proxy.scopeUrl') }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <div class="jv-field-grid">
          <el-form-item :label="t('proxy.urlConnectTimeout')">
            <el-input v-model.number="proxyForm.urlConnectTimeout" type="number" />
          </el-form-item>

          <el-form-item :label="t('proxy.urlReadTimeout')">
            <el-input v-model.number="proxyForm.urlReadTimeout" type="number" />
          </el-form-item>

          <el-form-item :label="t('proxy.llmTimeout')">
            <el-input v-model.number="proxyForm.llmTimeout" type="number" />
          </el-form-item>
        </div>

        <div class="jv-proxy-actions">
          <el-button type="primary" :loading="proxySaving" @click="saveProxy">{{ t('proxy.save') }}</el-button>
          <el-button :loading="proxyTesting" @click="testProxy">
            <Plug v-if="!proxyTesting" :size="13" :stroke-width="2.2" style="margin-right:6px" />
            {{ t('common.test') }}
          </el-button>
        </div>
      </el-form>

      <div v-if="proxyTestResult" class="jv-test-result"
        :class="proxyTestResult.ok ? 'jv-test-ok' : 'jv-test-fail'">
        <span class="jv-test-label">
          <CheckCircle2 v-if="proxyTestResult.ok" :size="13" :stroke-width="2.2" />
          <XCircle v-else :size="13" :stroke-width="2.2" />
          {{ proxyTestResult.ok ? t('proxy.testOk') : t('proxy.testFail') }}
        </span>
        <span v-if="proxyTestResult.message" class="jv-test-detail">
          {{ proxyTestResult.message }}
        </span>
        <span v-if="proxyTestResult.error" class="jv-test-detail is-error">
          {{ proxyTestResult.error }}
        </span>
      </div>
    </el-card>
  </div>
</template>

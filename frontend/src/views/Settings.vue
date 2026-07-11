<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, type LlmConfig, type JavaProfile } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from '../i18n'

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

const setDefaultProfile = async (p: JavaProfile) => {
  try {
    await api.setDefaultJavaProfile(p.id!)
    ElMessage.success(t('javaProfiles.setDefaultSuccess', { name: p.name }))
    await loadJavaProfiles()
  } catch {
    ElMessage.error(t('javaProfiles.setDefaultFailed'))
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

onMounted(() => {
  loadConfigs()
  loadJavaProfiles()
})
</script>

<template>
  <div style="max-width:900px; margin:0 auto">
    <div class="jv-settings-header">
      <span class="jv-back-btn" @click="router.back()">{{ t('common.back') }}</span>
      <h2 style="margin:0; font-family:var(--font-mono); font-size:18px">{{ t('settings.title') }}</h2>
    </div>

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

        <el-table-column :label="t('settings.endpoint')" min-width="190">
          <template #default="{ row }">
            <span style="font-family:var(--font-mono); font-size:11px; color:var(--text-muted)">
              {{ row.endpoint || '—' }}
            </span>
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

        <el-form-item :label="t('settings.endpoint')">
          <el-select v-model="form.endpoint" style="width:100%">
            <el-option label="/v1/chat/completions" value="/v1/chat/completions" />
            <el-option label="/v1/responses" value="/v1/responses" />
            <el-option label="/v1/messages" value="/v1/messages" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('settings.baseUrl')">
          <el-input v-model="form.baseUrl" placeholder="http://localhost:3000/v1" />
        </el-form-item>

        <el-form-item :label="t('settings.apiKey')">
          <el-input v-model="form.apiKey" type="password" show-password
            :placeholder="t('settings.apiKeyPlaceholder')" />
        </el-form-item>

        <el-form-item :label="t('settings.model')">
          <el-input v-model="form.model" :placeholder="t('settings.modelPlaceholder')" />
        </el-form-item>

      </el-form>

      <template #footer>
        <div style="display:flex; gap:12px; justify-content:flex-end">
          <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="saving" @click="saveForm">{{ t('common.save') }}</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- Java Profiles -->
    <el-card style="margin-top:20px">
      <template #header>
        <div style="display:flex; align-items:center; justify-content:space-between">
          <span style="font-family:var(--font-mono); font-size:11px; color:var(--text-disabled); letter-spacing:1px">
            {{ t('javaProfiles.title') }}
          </span>
          <el-button type="primary" size="small" @click="openJpAdd">{{ t('javaProfiles.addNew') }}</el-button>
        </div>
      </template>

      <el-table :data="javaProfiles" v-loading="jpLoading" style="width:100%"
        :row-class-name="(row: any) => row.row.isDefault ? 'active-row' : ''">

        <el-table-column :label="t('javaProfiles.name')" min-width="120">
          <template #default="{ row }">
            <div style="display:flex; align-items:center; gap:8px">
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

        <el-table-column :label="t('javaProfiles.springBootVersion')" width="130">
          <template #default="{ row }">
            <span style="font-family:var(--font-mono); font-size:12px; color:var(--text-muted)">
              {{ row.springBootVersion || '—' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column :label="t('javaProfiles.javaHome')" min-width="220">
          <template #default="{ row }">
            <span style="font-family:var(--font-mono); font-size:11px; color:var(--text-disabled)">
              {{ row.javaHome ? (row.javaHome.length > 40 ? row.javaHome.substring(0, 40) + '…' : row.javaHome) : '—' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="" width="240" align="right">
          <template #default="{ row }">
            <div style="display:flex; gap:6px; justify-content:flex-end">
              <el-button v-if="!row.isDefault" size="small" type="success" @click="setDefaultProfile(row)">
                {{ t('common.activate') }}
              </el-button>
              <el-button size="small" @click="openJpEdit(row)">{{ t('common.edit') }}</el-button>
              <el-button size="small" type="danger" plain @click="deleteProfile(row)">{{ t('common.delete') }}</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="javaProfiles.length === 0 && !jpLoading" style="text-align:center; color:var(--text-disabled); padding:32px 0; font-size:13px">
        {{ t('javaProfiles.empty') }}
      </div>
    </el-card>

    <!-- Java Profile Add / Edit Dialog -->
    <el-dialog v-model="jpDialogVisible"
      :title="jpDialogMode === 'add' ? t('javaProfiles.addTitle') : t('javaProfiles.editTitle')"
      width="540px">

      <el-form :model="jpForm" label-position="top">
        <el-form-item :label="t('javaProfiles.name')">
          <el-input v-model="jpForm.name" placeholder="Java 17" />
        </el-form-item>

        <div style="display:grid; grid-template-columns:1fr 1fr; gap:16px">
          <el-form-item :label="t('javaProfiles.javaVersion')">
            <el-select v-model="jpForm.javaVersion" @change="onJavaVersionChange" style="width:100%">
              <el-option v-for="v in javaVersionOptions" :key="v" :label="'Java ' + v" :value="v" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="jpDialogMode === 'edit'" :label="t('javaProfiles.mavenJavaVersion')">
            <el-input v-model="jpForm.mavenJavaVersion" placeholder="17" />
          </el-form-item>
        </div>

        <el-form-item :label="t('javaProfiles.javaHome')">
          <el-input v-model="jpForm.javaHome" :placeholder="t('javaProfiles.javaHomePlaceholder')" />
        </el-form-item>

        <el-form-item v-if="jpDialogMode === 'edit'" :label="t('javaProfiles.springBootVersion')">
          <el-input v-model="jpForm.springBootVersion" :placeholder="t('javaProfiles.springBootPlaceholder')" />
        </el-form-item>

        <el-form-item v-if="jpDialogMode === 'edit'" :label="t('javaProfiles.syntaxConstraints')">
          <el-input v-model="jpForm.syntaxConstraints" type="textarea" :rows="3"
            :placeholder="t('javaProfiles.syntaxPlaceholder')" />
        </el-form-item>
      </el-form>

      <template #footer>
        <div style="display:flex; gap:12px; justify-content:flex-end">
          <el-button @click="jpDialogVisible = false">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" :loading="jpSaving" @click="saveJpForm">{{ t('common.save') }}</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
:deep(.active-row td) {
  background: #1c3a29 !important;
}

.jv-settings-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.jv-back-btn {
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  font-family: var(--font-mono);
}

.jv-back-btn:hover { color: var(--text-primary); }

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

</style>

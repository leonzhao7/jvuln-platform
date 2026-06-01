<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { ElMessage } from 'element-plus'
import { useI18n } from '../i18n'

const router = useRouter()
const { t } = useI18n()
const cveId = ref('')
const loading = ref(false)

const submit = async () => {
  const id = cveId.value.trim().toUpperCase()
  if (!id.match(/^CVE-\d{4}-\d{4,}$/)) {
    ElMessage.error(t('newAnalysis.invalidCve'))
    return
  }
  loading.value = true
  try {
    await api.submitTask(id)
    ElMessage.success(t('newAnalysis.started'))
    router.push(`/analysis/${id}`)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error ?? t('newAnalysis.failed'))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div style="max-width:520px; margin:60px auto">
    <el-card>
      <template #header>
        <div class="jv-new-header">
          <span style="font-family:var(--font-mono); font-size:13px; color:var(--text-disabled); letter-spacing:1px">
            {{ t('newAnalysis.title') }}
          </span>
        </div>
      </template>

      <el-form @submit.prevent="submit" label-position="top">
        <el-form-item :label="t('newAnalysis.cveId')">
          <el-input
            v-model="cveId"
            :placeholder="t('newAnalysis.placeholder')"
            size="large"
            clearable
            @keyup.enter="submit"
          />
        </el-form-item>

        <p style="color:var(--text-disabled); font-size:12px; margin-bottom:20px; line-height:1.8; font-family:var(--font-mono)">
          {{ t('newAnalysis.description') }}
        </p>

        <div style="display:flex; gap:12px">
          <el-button @click="router.back()" style="flex:1">
            {{ t('common.cancel') }}
          </el-button>
          <el-button type="primary" :loading="loading" @click="submit" style="flex:2">
            {{ t('newAnalysis.start') }}
          </el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

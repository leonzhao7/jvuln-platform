<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { ElMessage } from 'element-plus'

const router = useRouter()
const cveId = ref('')
const loading = ref(false)

const submit = async () => {
  const id = cveId.value.trim().toUpperCase()
  if (!id.match(/^CVE-\d{4}-\d{4,}$/)) {
    ElMessage.error('Invalid CVE ID format (e.g. CVE-2025-24813)')
    return
  }
  loading.value = true
  try {
    await api.submitTask(id)
    ElMessage.success('Analysis started')
    router.push(`/analysis/${id}`)
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error ?? 'Failed to start analysis')
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
            NEW ANALYSIS
          </span>
        </div>
      </template>

      <el-form @submit.prevent="submit" label-position="top">
        <el-form-item label="CVE ID">
          <el-input
            v-model="cveId"
            placeholder="CVE-2025-24813"
            size="large"
            clearable
            @keyup.enter="submit"
          />
        </el-form-item>

        <p style="color:var(--text-disabled); font-size:12px; margin-bottom:20px; line-height:1.8; font-family:var(--font-mono)">
          The pipeline will collect CVE intelligence, locate the fix commit,
          analyze the vulnerable code, and generate educational artifacts.
        </p>

        <el-button type="primary" :loading="loading" @click="submit" style="width:100%">
          Start Analysis
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

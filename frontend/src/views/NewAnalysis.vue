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
    <el-card style="background:#1e1e3a; border:1px solid #2a2a4a">
      <template #header>
        <h3 style="color:#e2e8f0">New CVE Analysis</h3>
      </template>

      <el-form @submit.prevent="submit" label-position="top">
        <el-form-item label="CVE ID" style="--el-text-color-regular:#94a3b8">
          <el-input
            v-model="cveId"
            placeholder="CVE-2025-24813"
            size="large"
            clearable
            @keyup.enter="submit"
            style="--el-input-bg-color:#0f0f23; --el-input-text-color:#e2e8f0"
          />
        </el-form-item>

        <p style="color:#64748b; font-size:13px; margin-bottom:16px">
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

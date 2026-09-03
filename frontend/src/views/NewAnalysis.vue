<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { ElMessage } from 'element-plus'
import { useI18n } from '../i18n'
import { ScanLine, Rocket, ArrowLeft } from 'lucide-vue-next'

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
  <div class="jv-launch">
    <div class="jv-launch-aura" />

    <div class="jv-launch-card">
      <div class="jv-launch-head">
        <span class="jv-launch-glyph"><ScanLine :size="18" :stroke-width="2" /></span>
        <div>
          <div class="jv-eyebrow">NEW PIPELINE RUN</div>
          <h1 class="jv-launch-title">{{ t('newAnalysis.title') }}</h1>
        </div>
      </div>

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

        <div class="jv-launch-actions">
          <el-button @click="router.back()">
            <ArrowLeft :size="14" :stroke-width="2.2" style="margin-right:6px" />
            {{ t('common.cancel') }}
          </el-button>
          <el-button type="primary" :loading="loading" @click="submit">
            <Rocket v-if="!loading" :size="14" :stroke-width="2.2" style="margin-right:7px" />
            {{ t('newAnalysis.start') }}
          </el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

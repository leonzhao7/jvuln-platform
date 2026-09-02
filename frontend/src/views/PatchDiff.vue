<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { ElMessage } from 'element-plus'
import DiffViewer from '../components/DiffViewer.vue'
import { useI18n } from '../i18n'
import { ArrowLeft, GitCompare } from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const cveId = route.params.cveId as string

const diffContent = ref('')
const loading = ref(true)

onMounted(async () => {
  try {
    const data = await api.getDiff(cveId)
    diffContent.value = data.diff
  } catch {
    ElMessage.error(t('diff.loadFailed'))
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <div class="jv-diff-header">
      <div class="jv-diff-header-left">
        <span class="jv-back-btn" @click="router.push(`/analysis/${cveId}`)">
          <ArrowLeft :size="14" :stroke-width="2.2" />
          {{ t('common.back') }}
        </span>
        <h2 class="jv-detail-id">{{ cveId }}</h2>
        <span class="jv-diff-badge">
          <GitCompare :size="11" :stroke-width="2.2" />
          {{ t('diff.patchDiff') }}
        </span>
      </div>
    </div>

    <DiffViewer
      :diff-content="diffContent"
      :loading="loading"
      :empty-text="t('diff.empty')"
    />
  </div>
</template>

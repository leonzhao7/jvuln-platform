<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { ElMessage } from 'element-plus'
import DiffViewer from '../components/DiffViewer.vue'
import { useI18n } from '../i18n'

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
        <span class="jv-back-btn" @click="router.push(`/analysis/${cveId}`)">{{ t('common.back') }}</span>
        <h2 style="margin:0; font-family:var(--font-mono); font-size:18px">
          {{ cveId }}
        </h2>
        <span class="jv-tag jv-tag-pending">{{ t('diff.patchDiff') }}</span>
      </div>
    </div>

    <DiffViewer
      :diff-content="diffContent"
      :loading="loading"
      :empty-text="t('diff.empty')"
    />
  </div>
</template>

<style scoped>
.jv-diff-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
  gap: 12px;
}
.jv-diff-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.jv-back-btn {
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  font-family: var(--font-mono);
}
.jv-back-btn:hover { color: var(--text-primary); }
</style>

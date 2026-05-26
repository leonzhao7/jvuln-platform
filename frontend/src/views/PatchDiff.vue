<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { ElMessage } from 'element-plus'
import { html as diff2htmlHtml } from 'diff2html'
import 'diff2html/bundles/css/diff2html.min.css'

const route = useRoute()
const router = useRouter()
const cveId = route.params.cveId as string

const diffContent = ref('')
const loading = ref(true)
const viewType = ref<'side-by-side' | 'line-by-line'>('side-by-side')
const diffHtml = ref('')

const render = () => {
  if (!diffContent.value) return
  diffHtml.value = diff2htmlHtml(diffContent.value, {
    drawFileList: true,
    matching: 'lines',
    outputFormat: viewType.value,
    renderNothingWhenEmpty: false,
  })
}

onMounted(async () => {
  try {
    const data = await api.getDiff(cveId)
    diffContent.value = data.diff
    render()
  } catch {
    ElMessage.error('Failed to load diff')
  } finally {
    loading.value = false
  }
})

const toggleView = () => {
  viewType.value = viewType.value === 'side-by-side' ? 'line-by-line' : 'side-by-side'
  render()
}
</script>

<template>
  <div>
    <div style="display:flex; align-items:center; gap:12px; margin-bottom:24px">
      <span class="jv-back-btn" @click="router.push(`/analysis/${cveId}`)">← Back</span>
      <h2 style="margin:0; font-family:var(--font-mono); font-size:18px">
        {{ cveId }} — Patch Diff
      </h2>
      <div style="flex:1"/>
      <el-button size="small" @click="toggleView">
        {{ viewType === 'side-by-side' ? 'Unified View' : 'Side-by-Side' }}
      </el-button>
    </div>

    <el-skeleton v-if="loading" :rows="10" animated />

    <div v-else-if="diffHtml" class="diff-wrapper" v-html="diffHtml" />

    <el-empty v-else description="No diff available for this CVE" />
  </div>
</template>

<style>
.jv-back-btn {
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  font-family: var(--font-mono);
}
.jv-back-btn:hover { color: var(--text-primary); }

.diff-wrapper {
  border: 1px solid var(--border-subtle);
  overflow: hidden;
  font-size: 12px;
}
.diff-wrapper .d2h-wrapper {
  background: var(--bg-surface);
}
.diff-wrapper .d2h-file-header {
  background: var(--bg-base);
  color: var(--text-muted);
  border-bottom: 1px solid var(--border-subtle);
  font-family: var(--font-mono);
  font-size: 12px;
}
.diff-wrapper .d2h-code-line,
.diff-wrapper .d2h-code-side-line {
  background: var(--bg-code);
  color: var(--text-secondary);
}
.diff-wrapper .d2h-del {
  background: #2a0000 !important;
  color: #ffb3b8 !important;
}
.diff-wrapper .d2h-ins {
  background: #001a0a !important;
  color: #a7f0ba !important;
}
.diff-wrapper .d2h-cntx {
  background: var(--bg-code) !important;
  color: var(--text-disabled) !important;
}
.diff-wrapper .d2h-info {
  background: var(--bg-base);
  color: var(--accent-light);
  font-family: var(--font-mono);
}
.diff-wrapper .d2h-file-list-wrapper {
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border-subtle);
}
.diff-wrapper .d2h-file-list-line {
  color: var(--text-muted);
  font-family: var(--font-mono);
}
.diff-wrapper .d2h-tag {
  background: var(--bg-elevated);
  color: var(--text-muted);
  border-radius: 0;
}
.diff-wrapper td, .diff-wrapper th {
  border-color: var(--border-subtle) !important;
}
.diff-wrapper .d2h-file-stats .d2h-additions {
  color: var(--success);
}
.diff-wrapper .d2h-file-stats .d2h-deletions {
  color: var(--critical);
}
</style>

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
    <div style="display:flex; align-items:center; gap:12px; margin-bottom:20px">
      <el-button text @click="router.push(`/analysis/${cveId}`)">← Back</el-button>
      <h2 style="color:#e2e8f0">{{ cveId }} — Patch Diff</h2>
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
.diff-wrapper {
  border-radius: 8px;
  overflow: hidden;
  font-size: 12px;
}
.diff-wrapper .d2h-wrapper {
  background: #1e1e3a;
}
.diff-wrapper .d2h-file-header {
  background: #2a2a4a;
  color: #94a3b8;
  border-bottom: 1px solid #3a3a5a;
}
.diff-wrapper .d2h-code-line,
.diff-wrapper .d2h-code-side-line {
  background: #0f0f23;
  color: #e2e8f0;
}
.diff-wrapper .d2h-del {
  background: #2d0000 !important;
  color: #fca5a5 !important;
}
.diff-wrapper .d2h-ins {
  background: #002d00 !important;
  color: #86efac !important;
}
.diff-wrapper .d2h-cntx {
  background: #0f0f23 !important;
  color: #64748b !important;
}
.diff-wrapper .d2h-info {
  background: #1e293b;
  color: #60a5fa;
}
.diff-wrapper .d2h-file-list-wrapper {
  background: #1e1e3a;
  border: 1px solid #2a2a4a;
}
.diff-wrapper .d2h-file-list-line {
  color: #94a3b8;
}
.diff-wrapper .d2h-tag {
  background: #2a2a4a;
  color: #94a3b8;
}
.diff-wrapper td, .diff-wrapper th {
  border-color: #2a2a4a !important;
}
</style>

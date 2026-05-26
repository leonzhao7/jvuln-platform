<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
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
    colorScheme: 'dark',
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

const fileCount = computed(() => {
  if (!diffContent.value) return 0
  return (diffContent.value.match(/^diff --git/gm) || []).length
})
</script>

<template>
  <div>
    <div class="jv-diff-header">
      <div class="jv-diff-header-left">
        <span class="jv-back-btn" @click="router.push(`/analysis/${cveId}`)">← Back</span>
        <h2 style="margin:0; font-family:var(--font-mono); font-size:18px">
          {{ cveId }}
        </h2>
        <span class="jv-tag jv-tag-pending">PATCH DIFF</span>
        <span v-if="fileCount" style="font-family:var(--font-mono); font-size:12px; color:var(--text-disabled)">
          {{ fileCount }} file{{ fileCount > 1 ? 's' : '' }}
        </span>
      </div>
      <el-button size="small" @click="toggleView">
        {{ viewType === 'side-by-side' ? 'Unified View' : 'Side-by-Side' }}
      </el-button>
    </div>

    <el-skeleton v-if="loading" :rows="10" animated />

    <div v-else-if="diffHtml" class="diff-wrapper d2h-dark-color-scheme" v-html="diffHtml" />

    <el-empty v-else description="No diff available for this CVE" />
  </div>
</template>

<style>
/* ── diff2html Carbon override ─────────────────────────────────────── */

.diff-wrapper.d2h-dark-color-scheme {
  /* Override dark scheme variables to match Carbon palette */
  --d2h-dark-color:                     var(--text-secondary);
  --d2h-dark-bg-color:                  #1c1c1c;
  --d2h-dark-border-color:              var(--border-subtle);
  --d2h-dark-dim-color:                 var(--text-disabled);
  --d2h-dark-line-border-color:         #2a2a2a;

  /* File header */
  --d2h-dark-file-header-bg-color:      var(--bg-base);
  --d2h-dark-file-header-border-color:  var(--border-subtle);

  /* Placeholder */
  --d2h-dark-empty-placeholder-bg-color:    rgba(82,82,82,.1);
  --d2h-dark-empty-placeholder-border-color:var(--border-subtle);

  /* Selection */
  --d2h-dark-selected-color:            rgba(15,98,254,.15);

  /* Insertions — Carbon green */
  --d2h-dark-ins-bg-color:              rgba(66,190,101,.12);
  --d2h-dark-ins-border-color:          rgba(66,190,101,.3);
  --d2h-dark-ins-highlight-bg-color:    rgba(66,190,101,.28);
  --d2h-dark-ins-label-color:           #42be65;

  /* Deletions — Carbon red */
  --d2h-dark-del-bg-color:              rgba(250,77,86,.1);
  --d2h-dark-del-border-color:          rgba(250,77,86,.3);
  --d2h-dark-del-highlight-bg-color:    rgba(250,77,86,.28);
  --d2h-dark-del-label-color:           #fa4d56;

  /* Changes */
  --d2h-dark-change-del-color:          rgba(241,194,27,.12);
  --d2h-dark-change-ins-color:          rgba(66,190,101,.18);

  /* Info — IBM blue */
  --d2h-dark-info-bg-color:             rgba(15,98,254,.08);
  --d2h-dark-info-border-color:         rgba(15,98,254,.25);

  /* Labels */
  --d2h-dark-change-label-color:        #f1c21b;
  --d2h-dark-moved-label-color:         var(--accent-light);

  font-size: 13px;
  border: 1px solid var(--border-subtle);
  overflow: hidden;
}

/* Font — use IBM Plex Mono for code */
.diff-wrapper .d2h-diff-table {
  font-family: var(--font-mono) !important;
  font-size: 12px;
}
.diff-wrapper .d2h-file-header {
  font-family: var(--font-mono) !important;
  font-size: 12px;
  padding: 8px 12px;
  height: auto;
}
.diff-wrapper .d2h-file-name {
  font-family: var(--font-mono);
  font-size: 12px;
}

/* Square corners — no border-radius anywhere */
.diff-wrapper .d2h-file-wrapper { border-radius: 0; }
.diff-wrapper .d2h-lines-added  { border-radius: 0; }
.diff-wrapper .d2h-lines-deleted { border-radius: 0; }
.diff-wrapper .d2h-code-line del,
.diff-wrapper .d2h-code-line ins,
.diff-wrapper .d2h-code-side-line del,
.diff-wrapper .d2h-code-side-line ins {
  border-radius: 0;
}

/* File list panel */
.diff-wrapper .d2h-file-list-wrapper {
  border-bottom: 1px solid var(--border-subtle);
  margin-bottom: 0;
}
.diff-wrapper .d2h-file-list > li {
  padding: 6px 12px;
  font-family: var(--font-mono);
  font-size: 12px;
}
.diff-wrapper .d2h-file-list-wrapper a,
.diff-wrapper .d2h-file-list-wrapper a:visited {
  color: var(--accent-light);
}
.diff-wrapper .d2h-file-list-wrapper a:hover {
  color: var(--accent);
}

/* File stats badges */
.diff-wrapper .d2h-file-stats {
  font-family: var(--font-mono);
  font-size: 12px;
}

/* Tag badges */
.diff-wrapper .d2h-tag {
  border-radius: 0;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .3px;
  text-transform: uppercase;
}

/* Inline code highlight — sharper contrast */
.diff-wrapper .d2h-code-line-ctn {
  font-family: var(--font-mono);
  font-size: 12px;
}

/* Side-by-side separator */
.diff-wrapper .d2h-file-side-diff {
  border-right: 1px solid var(--border-subtle);
}
.diff-wrapper .d2h-file-side-diff:last-child {
  border-right: none;
}

/* Table cells */
.diff-wrapper td,
.diff-wrapper th {
  border-color: var(--border-subtle) !important;
}
</style>

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

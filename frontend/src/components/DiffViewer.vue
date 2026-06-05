<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElSkeleton } from 'element-plus'
import { html as diff2htmlHtml } from 'diff2html'
import { ColorSchemeType } from 'diff2html/lib/types'
import { useI18n } from '../i18n'
import 'diff2html/bundles/css/diff2html.min.css'

const props = withDefaults(defineProps<{
  diffContent: string
  loading?: boolean
  title?: string
  emptyText?: string
}>(), {
  loading: false,
  title: '',
  emptyText: '',
})

const { t } = useI18n()
const viewType = ref<'side-by-side' | 'line-by-line'>('side-by-side')
const diffHtml = ref('')

const render = () => {
  if (!props.diffContent) {
    diffHtml.value = ''
    return
  }
  diffHtml.value = diff2htmlHtml(props.diffContent, {
    drawFileList: true,
    matching: 'lines',
    outputFormat: viewType.value,
    renderNothingWhenEmpty: false,
    colorScheme: ColorSchemeType.DARK,
  })
}

watch(() => [props.diffContent, viewType.value], render, { immediate: true })

const toggleView = () => {
  viewType.value = viewType.value === 'side-by-side' ? 'line-by-line' : 'side-by-side'
}

const fileCount = computed(() => {
  if (!props.diffContent) return 0
  return (props.diffContent.match(/^diff --git/gm) || []).length
})

const resolvedEmptyText = computed(() => props.emptyText || t('diff.empty'))
</script>

<template>
  <div class="jv-diff-viewer">
    <div class="jv-diff-toolbar">
      <div class="jv-diff-toolbar-left">
        <span v-if="title" class="jv-section-label">{{ title }}</span>
        <span v-if="fileCount" class="jv-diff-file-count">
          {{ t('diff.fileCount', { count: fileCount }) }}
        </span>
      </div>
      <el-button size="small" :disabled="!diffContent" @click="toggleView">
        {{ viewType === 'side-by-side' ? t('diff.unifiedView') : t('diff.sideBySide') }}
      </el-button>
    </div>

    <el-skeleton v-if="loading" :rows="10" animated />

    <div v-else-if="diffHtml" class="diff-wrapper d2h-dark-color-scheme" v-html="diffHtml" />

    <el-empty v-else :description="resolvedEmptyText" />
  </div>
</template>

<style>
.diff-wrapper.d2h-dark-color-scheme {
  --d2h-dark-color:                     var(--text-secondary);
  --d2h-dark-bg-color:                  #1c1c1c;
  --d2h-dark-border-color:              var(--border-subtle);
  --d2h-dark-dim-color:                 var(--text-disabled);
  --d2h-dark-line-border-color:         #2a2a2a;
  --d2h-dark-file-header-bg-color:      var(--bg-base);
  --d2h-dark-file-header-border-color:  var(--border-subtle);
  --d2h-dark-empty-placeholder-bg-color: rgba(82,82,82,.1);
  --d2h-dark-empty-placeholder-border-color: var(--border-subtle);
  --d2h-dark-selected-color:            rgba(15,98,254,.15);
  --d2h-dark-ins-bg-color:              rgba(66,190,101,.12);
  --d2h-dark-ins-border-color:          rgba(66,190,101,.3);
  --d2h-dark-ins-highlight-bg-color:    rgba(66,190,101,.28);
  --d2h-dark-ins-label-color:           #42be65;
  --d2h-dark-del-bg-color:              rgba(250,77,86,.1);
  --d2h-dark-del-border-color:          rgba(250,77,86,.3);
  --d2h-dark-del-highlight-bg-color:    rgba(250,77,86,.28);
  --d2h-dark-del-label-color:           #fa4d56;
  --d2h-dark-change-del-color:          rgba(241,194,27,.12);
  --d2h-dark-change-ins-color:          rgba(66,190,101,.18);
  --d2h-dark-info-bg-color:             rgba(15,98,254,.08);
  --d2h-dark-info-border-color:         rgba(15,98,254,.25);
  --d2h-dark-change-label-color:        #f1c21b;
  --d2h-dark-moved-label-color:         var(--accent-light);

  font-size: 13px;
  border: 1px solid var(--border-subtle);
  overflow: hidden;
}

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
.diff-wrapper .d2h-file-wrapper { border-radius: 0; }
.diff-wrapper .d2h-lines-added  { border-radius: 0; }
.diff-wrapper .d2h-lines-deleted { border-radius: 0; }
.diff-wrapper .d2h-code-line del,
.diff-wrapper .d2h-code-line ins,
.diff-wrapper .d2h-code-side-line del,
.diff-wrapper .d2h-code-side-line ins {
  border-radius: 0;
}
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
.diff-wrapper .d2h-file-stats {
  font-family: var(--font-mono);
  font-size: 12px;
}
.diff-wrapper .d2h-tag {
  border-radius: 0;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .3px;
  text-transform: uppercase;
}
.diff-wrapper .d2h-code-line-ctn {
  font-family: var(--font-mono);
  font-size: 12px;
}
.diff-wrapper .d2h-file-side-diff {
  border-right: 1px solid var(--border-subtle);
}
.diff-wrapper .d2h-file-side-diff:last-child {
  border-right: none;
}
.diff-wrapper td,
.diff-wrapper th {
  border-color: var(--border-subtle) !important;
}
</style>

<style scoped>
.jv-diff-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.jv-diff-toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.jv-section-label {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
  letter-spacing: 1px;
  text-transform: uppercase;
}
.jv-diff-file-count {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-disabled);
}
</style>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElSkeleton } from 'element-plus'
import { html as diff2htmlHtml } from 'diff2html'
import { ColorSchemeType } from 'diff2html/lib/types'
import { useI18n } from '../i18n'
import 'diff2html/bundles/css/diff2html.min.css'

interface FileDecision {
  fileName: string
  relevant: boolean
  causal?: boolean
  reason: string
  layer?: string
}

const props = withDefaults(defineProps<{
  diffContent: string
  loading?: boolean
  title?: string
  emptyText?: string
  fileDecisions?: FileDecision[]
}>(), {
  loading: false,
  title: '',
  emptyText: '',
})

const { t } = useI18n()
const viewType = ref<'side-by-side' | 'line-by-line'>('side-by-side')
const expandedFile = ref<string | null>(null)
const showRelevantOnly = ref(false)

interface FileDiff {
  fileName: string
  diffContent: string
  stats: {
    additions: number
    deletions: number
  }
}

const fileDiffs = computed<FileDiff[]>(() => {
  if (!props.diffContent) return []

  // 按文件分割diff
  const fileBlocks = props.diffContent.split(/(?=^diff --git)/gm).filter(block => block.trim())

  return fileBlocks.map(block => {
    // 提取文件名
    const fileMatch = block.match(/^diff --git a\/(.*?) b\//m)
    const fileName = fileMatch ? fileMatch[1] : 'unknown'

    // 统计增删行数
    const additions = (block.match(/^\+(?!\+)/gm) || []).length
    const deletions = (block.match(/^-(?!-)/gm) || []).length

    return {
      fileName,
      diffContent: block,
      stats: { additions, deletions }
    }
  })
})

const hasDecisions = computed(() => !!props.fileDecisions?.length)

const relevantCount = computed(() =>
  fileDiffs.value.filter(f => decisionFor(f.fileName)?.causal).length)

const displayedFileDiffs = computed<FileDiff[]>(() =>
  showRelevantOnly.value
    ? fileDiffs.value.filter(f => decisionFor(f.fileName)?.causal)
    : fileDiffs.value)

const toggleFile = (fileName: string) => {
  expandedFile.value = expandedFile.value === fileName ? null : fileName
}

const renderFileDiff = (diffContent: string) => {
  return diff2htmlHtml(diffContent, {
    drawFileList: false,
    matching: 'lines',
    outputFormat: viewType.value,
    renderNothingWhenEmpty: false,
    colorScheme: ColorSchemeType.DARK,
  })
}

const toggleView = () => {
  viewType.value = viewType.value === 'side-by-side' ? 'line-by-line' : 'side-by-side'
}

const fileCount = computed(() => fileDiffs.value.length)

const resolvedEmptyText = computed(() => props.emptyText || t('diff.empty'))

const decisionFor = (fileName: string): FileDecision | undefined => {
  if (!props.fileDecisions?.length) return undefined
  const short = fileName.includes('/') ? fileName.substring(fileName.lastIndexOf('/') + 1) : fileName
  return props.fileDecisions.find(d => d.fileName === fileName || d.fileName === short
    || (d.fileName.includes('/') ? d.fileName.substring(d.fileName.lastIndexOf('/') + 1) : d.fileName) === short)
}
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
      <div class="jv-diff-toolbar-right">
        <el-button
          v-if="hasDecisions"
          size="small"
          :type="showRelevantOnly ? 'primary' : 'default'"
          @click="showRelevantOnly = !showRelevantOnly"
        >
          {{ showRelevantOnly ? t('diff.showAll') : t('diff.relevantOnly', { count: relevantCount }) }}
        </el-button>
        <el-button size="small" :disabled="!diffContent" @click="toggleView">
          {{ viewType === 'side-by-side' ? t('diff.unifiedView') : t('diff.sideBySide') }}
        </el-button>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="10" animated />

    <div v-else-if="displayedFileDiffs.length" class="jv-file-diffs">
      <div v-for="file in displayedFileDiffs" :key="file.fileName" class="jv-file-diff-block">
        <div class="jv-file-diff-header" @click="toggleFile(file.fileName)">
          <div class="jv-file-diff-info">
            <span class="jv-file-diff-icon">{{ expandedFile === file.fileName ? '▼' : '▶' }}</span>
            <span class="jv-file-diff-name">{{ file.fileName }}</span>
            <span
              v-if="decisionFor(file.fileName)"
              class="jv-file-relevance"
              :class="decisionFor(file.fileName)!.relevant
                ? (decisionFor(file.fileName)!.causal ? 'is-causal' : 'is-suspected')
                : 'is-excluded'"
            >
              {{ !decisionFor(file.fileName)!.relevant
                ? t('analysis.patch.excluded')
                : decisionFor(file.fileName)!.causal
                  ? t('analysis.patch.relevant')
                  : t('analysis.patch.suspected') }}
            </span>
            <span class="jv-file-diff-stats">
              <span class="additions">+{{ file.stats.additions }}</span>
              <span class="deletions">-{{ file.stats.deletions }}</span>
            </span>
          </div>
          <div
            v-if="decisionFor(file.fileName)?.relevant && decisionFor(file.fileName)?.reason"
            class="jv-file-relevance-reason"
          >
            {{ decisionFor(file.fileName)!.reason }}
          </div>
        </div>

        <div v-if="expandedFile === file.fileName" class="jv-file-diff-content">
          <div class="diff-wrapper d2h-dark-color-scheme" v-html="renderFileDiff(file.diffContent)" />
        </div>
      </div>
    </div>

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
.jv-diff-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
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

/* 文件列表 */
.jv-file-diffs {
  border: 1px solid var(--border-subtle);
  border-radius: 4px;
  overflow: hidden;
}

.jv-file-diff-block {
  border-bottom: 1px solid var(--border-subtle);
}
.jv-file-diff-block:last-child {
  border-bottom: none;
}

.jv-file-diff-header {
  background: var(--bg-base);
  padding: 10px 14px;
  cursor: pointer;
  transition: background .15s;
  border-left: 3px solid var(--accent);
}
.jv-file-diff-header:hover {
  background: var(--bg-hover);
}

.jv-file-diff-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.jv-file-diff-icon {
  font-size: 10px;
  color: var(--text-disabled);
  width: 12px;
  transition: transform .2s;
}

.jv-file-diff-name {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--accent-light);
}

.jv-file-relevance {
  margin-left: 16px;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: .5px;
  text-transform: uppercase;
  padding: 1px 8px;
  border: 1px solid transparent;
}
.jv-file-relevance.is-causal {
  color: #42be65;
  border-color: rgba(66, 190, 101, .4);
  background: rgba(66, 190, 101, .1);
}
.jv-file-relevance.is-suspected {
  color: #f1c21b;
  border-color: rgba(241, 194, 27, .35);
  background: rgba(241, 194, 27, .08);
  border-style: dashed;
}
.jv-file-relevance.is-excluded {
  color: var(--text-disabled);
  border-color: var(--border-subtle);
  background: rgba(130, 130, 130, .08);
}

.jv-file-diff-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 11px;
  margin-left: auto;
}
.jv-file-diff-stats .additions {
  color: #42be65;
}
.jv-file-diff-stats .deletions {
  color: #fa4d56;
}

.jv-file-relevance-reason {
  margin-top: 6px;
  padding-left: 22px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
}

.jv-file-diff-content {
  animation: slideDown .2s ease-out;
}

@keyframes slideDown {
  from {
    opacity: 0;
    transform: translateY(-10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>

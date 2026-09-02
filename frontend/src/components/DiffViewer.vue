<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElSkeleton } from 'element-plus'
import { ChevronDown, ChevronRight, Columns2, Rows3, Filter, FileDiff } from 'lucide-vue-next'
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
          <FileDiff :size="11" :stroke-width="2" />
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
          <Filter :size="13" :stroke-width="2" style="margin-right:6px" />
          {{ showRelevantOnly ? t('diff.showAll') : t('diff.relevantOnly', { count: relevantCount }) }}
        </el-button>
        <el-button size="small" :disabled="!diffContent" @click="toggleView">
          <component
            :is="viewType === 'side-by-side' ? Rows3 : Columns2"
            :size="13" :stroke-width="2" style="margin-right:6px"
          />
          {{ viewType === 'side-by-side' ? t('diff.unifiedView') : t('diff.sideBySide') }}
        </el-button>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="10" animated />

    <div v-else-if="displayedFileDiffs.length" class="jv-file-diffs">
      <div v-for="file in displayedFileDiffs" :key="file.fileName" class="jv-file-diff-block">
        <div class="jv-file-diff-header" @click="toggleFile(file.fileName)">
          <span class="jv-file-diff-icon">
            <ChevronDown v-if="expandedFile === file.fileName" :size="14" :stroke-width="2.2" />
            <ChevronRight v-else :size="14" :stroke-width="2.2" />
          </span>
          <div class="jv-file-diff-info">
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
          </div>
          <span class="jv-file-diff-stats">
            <span class="add">+{{ file.stats.additions }}</span>
            <span class="del">-{{ file.stats.deletions }}</span>
          </span>
        </div>

        <div
          v-if="decisionFor(file.fileName)?.relevant && decisionFor(file.fileName)?.reason"
          class="jv-file-relevance-reason"
        >
          {{ decisionFor(file.fileName)!.reason }}
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
  --d2h-dark-bg-color:                  #060810;
  --d2h-dark-border-color:              var(--border-subtle);
  --d2h-dark-dim-color:                 var(--text-disabled);
  --d2h-dark-line-border-color:         var(--hairline-lo);
  --d2h-dark-file-header-bg-color:      var(--stratum-1);
  --d2h-dark-file-header-border-color:  var(--border-subtle);
  --d2h-dark-empty-placeholder-bg-color: rgba(82,82,82,.1);
  --d2h-dark-empty-placeholder-border-color: var(--border-subtle);
  --d2h-dark-selected-color:            rgba(124,92,255,.15);
  --d2h-dark-ins-bg-color:              rgba(52,224,161,.11);
  --d2h-dark-ins-border-color:          rgba(52,224,161,.28);
  --d2h-dark-ins-highlight-bg-color:    rgba(52,224,161,.26);
  --d2h-dark-ins-label-color:           var(--sev-low);
  --d2h-dark-del-bg-color:              rgba(255,77,109,.1);
  --d2h-dark-del-border-color:          rgba(255,77,109,.28);
  --d2h-dark-del-highlight-bg-color:    rgba(255,77,109,.26);
  --d2h-dark-del-label-color:           var(--sev-critical);
  --d2h-dark-change-del-color:          rgba(255,216,77,.11);
  --d2h-dark-change-ins-color:          rgba(52,224,161,.16);
  --d2h-dark-info-bg-color:             rgba(124,92,255,.09);
  --d2h-dark-info-border-color:         rgba(124,92,255,.24);
  --d2h-dark-change-label-color:        var(--sev-medium);
  --d2h-dark-moved-label-color:         var(--accent-light);

  font-size: 13px;
  border: none;
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
.diff-wrapper .d2h-file-wrapper { border-radius: 0; border: none; }
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

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, type TaskDetail, type TranscriptEvent } from '../api'
import { ElMessage } from 'element-plus'
import DiffViewer from '../components/DiffViewer.vue'
import { useI18n } from '../i18n'

const route = useRoute()
const router = useRouter()
const { t, array } = useI18n()
const cveId = route.params.cveId as string

const detail = ref<TaskDetail | null>(null)
const selectedStage = ref(1)
const stageData = ref<Record<number, any>>({})
const diffContent = ref('')
const diffLoading = ref(false)
const reportMarkdown = ref('')
const sseActive = ref(false)
const transcriptEvents = ref<TranscriptEvent[]>([])
const transcriptExpanded = ref(false)
interface TerminalEntry {
  type: string
  stageNum: number
  message: string
  formatted: string
}
interface StageGroup {
  stageNum: number
  label: string
  entries: TerminalEntry[]
  hasError: boolean
}
const sseMessages = ref<TerminalEntry[]>([])
const terminalVisible = ref(true)
const collapsedStages = ref(new Set<number>())
let evtSource: EventSource | null = null

const dpFilter = ref('all')

const dpCountByType = (type: string) => {
  const points = stageData.value[3]?.detection_points
  if (!Array.isArray(points)) return 0
  return points.filter((dp: any) => dp.type === type).length
}

const filteredDetectionPoints = computed(() => {
  const points = stageData.value[3]?.detection_points
  if (!Array.isArray(points)) return []
  if (dpFilter.value === 'all') return points
  return points.filter((dp: any) => dp.type === dpFilter.value)
})

const task = computed(() => detail.value?.task)
const stages = computed(() => detail.value?.stages ?? [])

const stageIcons = ['01', '02', '03', '04']
const stageNames = computed(() => array<string>('analysis.stageNames'))

const selectedStageRecord = computed(() =>
  stages.value.find(s => s.stageNum === selectedStage.value)
)
const stage4Data = computed(() => stageData.value[4] ?? null)
const isMavenSourceDiff = computed(() => {
  const p = stageData.value[2]?.patchInfo
  return (p?.strategy || p?.strategyName) === 'maven-source-diff'
})
const stage4PocFiles = computed<any[]>(() =>
  (stage4Data.value?.files ?? []).filter((item: any) => item.type === 'poc')
)
const stage4ValidationArtifacts = computed(() => {
  const artifacts = stage4Data.value?.validation?.artifacts
  if (!artifacts || typeof artifacts !== 'object') return []
  return Object.entries(artifacts).map(([key, value]) => ({ key, value }))
})
const stageClass = (status?: string) => {
  const map: Record<string, string> = {
    COMPLETED: 'jv-stage jv-stage-completed',
    RUNNING:   'jv-stage jv-stage-running',
    FAILED:    'jv-stage jv-stage-failed',
  }
  return map[status ?? ''] ?? 'jv-stage jv-stage-pending'
}

const selectStage = (stage: number) => {
  selectedStage.value = stage
}

const taskStatusClass = (s: string) => ({
  COMPLETED: 'jv-tag jv-tag-completed',
  RUNNING:   'jv-tag jv-tag-running',
  FAILED:    'jv-tag jv-tag-failed',
  PENDING:   'jv-tag jv-tag-pending',
  SKIPPED:   'jv-tag jv-tag-pending',
}[s] ?? 'jv-tag jv-tag-pending')

const articlesByCategory = (articles: any[], category: string) => {
  if (!Array.isArray(articles)) return []
  return articles.filter(a => (a.category || 'other') === category)
}

const load = async () => {
  try {
    detail.value = await api.getTask(cveId)
    await loadStageData()
  } catch {
    ElMessage.error(t('analysis.loadFailed'))
  }
}

const loadStageData = async () => {
  stageData.value = {}
  diffContent.value = ''
  diffLoading.value = false
  reportMarkdown.value = ''

  try { stageData.value[1] = await api.getIntelligence(cveId) } catch {}
  try { stageData.value[2] = await api.getPatch(cveId) } catch {}
  try { stageData.value[3] = await api.getReasoning(cveId) } catch {}
  try {
    stageData.value[4] = await api.getArtifacts(cveId)
    try { const r = await api.getReport(cveId); reportMarkdown.value = r.markdown } catch {}
    try { transcriptEvents.value = await api.getTranscript(cveId) } catch { transcriptEvents.value = [] }
  } catch {}

  diffLoading.value = true
  try {
    const d = await api.getDiff(cveId)
    diffContent.value = d.diff
  } catch {
    diffContent.value = ''
  } finally {
    diffLoading.value = false
  }
}

const stageLabel = (num: number) => {
  const name = stageNames.value[num - 1]
  const prefix = t('analysis.log.stagePrefix', { num })
  return name ? `${prefix} ${name}` : prefix
}

const formatSseEntry = (type: string, stageNum: number, message: string): TerminalEntry => {
  let formatted: string
  switch (type) {
    case 'stage_start':
      formatted = `${stageLabel(stageNum)} ${t('analysis.log.start')}`
      break
    case 'stage_done':
      formatted = `✔ ${stageLabel(stageNum)} · ${message}`
      break
    case 'error':
      formatted = `✖ ${stageNum ? stageLabel(stageNum) + ' · ' : ''}${message}`
      break
    case 'pipeline_done':
      formatted = `✔ ${message}`
      break
    default:
      formatted = stageNum ? `${stageLabel(stageNum)} · ${message}` : message
  }
  return { type, stageNum, message, formatted }
}

const terminalGroups = computed<StageGroup[]>(() => {
  const groups: StageGroup[] = []
  let current: StageGroup | null = null
  for (const entry of sseMessages.value) {
    if (entry.type === 'stage_start' && entry.stageNum > 0) {
      current = { stageNum: entry.stageNum, label: entry.formatted, entries: [], hasError: false }
      groups.push(current)
    } else if (current !== null && entry.stageNum === current.stageNum) {
      current.entries.push(entry)
      if (entry.type === 'error') current.hasError = true
    } else if (entry.stageNum > 0) {
      current = { stageNum: entry.stageNum, label: stageLabel(entry.stageNum), entries: [entry], hasError: entry.type === 'error' }
      groups.push(current)
    } else {
      groups.push({ stageNum: 0, label: '', entries: [entry], hasError: entry.type === 'error' })
      current = null
    }
  }
  return groups
})

const toggleStageGroup = (stageNum: number) => {
  const s = new Set(collapsedStages.value)
  if (s.has(stageNum)) s.delete(stageNum)
  else s.add(stageNum)
  collapsedStages.value = s
}

const startStream = () => {
  if (evtSource) evtSource.close()
  sseMessages.value = []
  collapsedStages.value = new Set()
  sseActive.value = true
  evtSource = new EventSource(`/api/analysis/${cveId}/stream`)

  const handleEvent = (type: string) => (e: MessageEvent) => {
    let message = e.data ?? ''
    let stageNum = 0
    try {
      const data = JSON.parse(e.data)
      message = data.message ?? ''
      stageNum = data.stageNum ?? 0
    } catch {}
    sseMessages.value.push(formatSseEntry(type, stageNum, message))
    if (type === 'pipeline_done' || type === 'error') {
      sseActive.value = false
      evtSource?.close()
      load()
    } else if (type.startsWith('stage_')) {
      load()
    }
  }

  evtSource.addEventListener('stage_start', handleEvent('stage_start'))
  evtSource.addEventListener('stage_done', handleEvent('stage_done'))
  evtSource.addEventListener('progress', handleEvent('progress'))
  evtSource.addEventListener('pipeline_done', handleEvent('pipeline_done'))
  evtSource.addEventListener('error', handleEvent('error'))
  evtSource.onerror = () => {
    sseActive.value = false
    evtSource?.close()
  }
}

const rerun = async (fromStage?: number) => {
  await api.rerunTask(cveId, fromStage)
  ElMessage.success(t('analysis.rerunStarted'))
  startStream()
}

onMounted(async () => {
  await load()
  if (!sseMessages.value.length) {
    try {
      const log = await api.getPipelineLog(cveId)
      sseMessages.value = log.map(e => formatSseEntry(e.type, e.stageNum, e.message))
    } catch {}
  }
  startStream()
})

onUnmounted(() => evtSource?.close())


const hasEvidenceList = (value: unknown) =>
  Array.isArray(value) && value.every(item => typeof item === 'string' || typeof item === 'number')

const formatEvidenceValue = (value: unknown) => {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value, null, 2)
}

const cvssTag = (score: number) => {
  if (score >= 9) return 'jv-tag jv-tag-critical'
  if (score >= 7) return 'jv-tag jv-tag-high'
  if (score >= 4) return 'jv-tag jv-tag-medium'
  return 'jv-tag jv-tag-low'
}

const renderMarkdown = (md: string) => {
  return md
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/^### (.+)$/gm, '<h4 style="color:var(--text-primary);margin:16px 0 8px">$1</h4>')
    .replace(/^## (.+)$/gm, '<h3 style="color:var(--text-primary);margin:20px 0 10px">$1</h3>')
    .replace(/^# (.+)$/gm, '<h2 style="color:var(--text-primary);margin:24px 0 12px">$1</h2>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code style="font-family:var(--font-mono);font-size:12px;background:var(--bg-code);padding:1px 5px">$1</code>')
    .replace(/^- (.+)$/gm, '<li style="margin-left:20px;color:var(--text-secondary)">$1</li>')
    .replace(/\n\n/g, '<br/><br/>')
    .replace(/\n/g, '<br/>')
}
</script>

<template>
  <div v-if="detail">
    <!-- Page header -->
    <div class="jv-detail-header">
      <div class="jv-detail-header-left">
        <span class="jv-back-btn" @click="router.push('/')">{{ t('common.back') }}</span>
        <h2 style="margin:0; font-family:var(--font-mono); font-size:18px">{{ cveId }}</h2>
        <span :class="taskStatusClass(task!.status)">{{ t(`status.${task!.status}`) }}</span>
      </div>
      <div class="jv-detail-header-right">
        <el-button size="small" :disabled="!sseMessages.length" @click="terminalVisible = !terminalVisible">
          {{ terminalVisible ? t('analysis.hideLog') : t('analysis.showLog') }}
        </el-button>
        <el-button size="small" :loading="sseActive" @click="rerun()">{{ t('analysis.rerunAll') }}</el-button>
      </div>
    </div>

    <!-- Stage Pipeline -->
    <div class="jv-pipeline-row">
      <div v-for="(name, i) in stageNames" :key="i"
        :class="[stageClass(stages[i]?.status), { 'jv-stage-selected': selectedStage === i + 1 }]"
        @click="selectStage(i + 1)"
        :title="t('analysis.viewStageResult', { stage: i + 1 })">
        <div class="jv-stage-num">{{ stageIcons[i] }}</div>
        <div class="jv-stage-name">{{ name }}</div>
        <div class="jv-stage-status">{{ t(`status.${stages[i]?.status ?? 'PENDING'}`) }}</div>
      </div>
    </div>

    <!-- SSE Terminal -->
    <div v-if="sseMessages.length && terminalVisible" class="jv-terminal" style="margin-bottom:20px">
      <template v-for="(group, gi) in terminalGroups" :key="gi">
        <template v-if="group.stageNum > 0">
          <div class="jv-term-stage-header" :class="{ 'has-error': group.hasError }"
               @click="toggleStageGroup(group.stageNum)">
            <span class="jv-term-caret">{{ collapsedStages.has(group.stageNum) ? '▶' : '▼' }}</span>
            {{ group.label }}
          </div>
          <template v-if="!collapsedStages.has(group.stageNum)">
            <div v-for="(entry, ei) in group.entries" :key="ei"
                 :class="{ 'jv-term-error': entry.type === 'error', 'jv-term-done': entry.type === 'stage_done' }">
              {{ entry.formatted }}
            </div>
          </template>
        </template>
        <template v-else>
          <div v-for="(entry, ei) in group.entries" :key="ei"
               :class="{ 'jv-term-error': entry.type === 'error', 'jv-term-done': entry.type === 'pipeline_done' }">
            {{ entry.formatted }}
          </div>
        </template>
      </template>
    </div>

    <!-- Selected stage result -->
    <el-card>
      <div class="jv-result-header">
        <div class="jv-result-title-row">
          <div class="jv-result-title">
            {{ stageIcons[selectedStage - 1] }} {{ stageNames[selectedStage - 1] }}
          </div>
          <div v-if="selectedStageRecord" class="jv-result-meta">
            <span :class="taskStatusClass(selectedStageRecord.status)">{{ t(`status.${selectedStageRecord.status}`) }}</span>
            <span>{{ t('analysis.startedAt') }}: {{ selectedStageRecord.startedAt?.replace('T', ' ').slice(0, 19) ?? '—' }}</span>
            <span v-if="selectedStageRecord.finishedAt">{{ t('analysis.finishedAt') }}: {{ selectedStageRecord.finishedAt.replace('T', ' ').slice(0, 19) }}</span>
          </div>
        </div>
        <div style="display:flex; align-items:center; gap:12px">
          <el-button
            v-if="selectedStageRecord && selectedStageRecord.status === 'FAILED'"
            type="warning" size="small" :loading="sseActive"
            @click="rerun(selectedStage)">
            {{ t('analysis.continueStage') }}
          </el-button>
          <el-button
            v-else-if="selectedStageRecord && selectedStageRecord.status === 'COMPLETED'"
            size="small" :loading="sseActive"
            @click="rerun(selectedStage)">
            {{ t('analysis.rerunStage') }}
          </el-button>
        </div>
      </div>
      <div v-if="selectedStageRecord?.errorMsg" class="jv-stage-error">
        {{ selectedStageRecord.errorMsg }}
      </div>
      <div class="jv-stage-result">

        <!-- Overview -->
        <div v-if="selectedStage === 1">
          <div v-if="stageData[1]">
            <el-descriptions :column="2" border size="small" label-width="140px">
              <el-descriptions-item :label="t('analysis.fields.cveId')">
                <span style="font-family:var(--font-mono)">{{ stageData[1].cveId }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="CVSS">
                <span v-if="stageData[1].cvss?.score"
                  :class="cvssTag(stageData[1].cvss.score)">
                  {{ stageData[1].cvss.score }} {{ stageData[1].cvss.severity }}
                </span>
                <span v-else style="color:var(--text-disabled)">—</span>
              </el-descriptions-item>
              <el-descriptions-item label="CWE">
                <span style="font-family:var(--font-mono)">{{ stageData[1].cweId ?? '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item :label="t('analysis.fields.fixedVersion')">
                <span style="font-family:var(--font-mono)">{{ stageData[1].fixedVersion || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item :label="t('analysis.fields.artifact')" :span="2">
                <span style="font-family:var(--font-mono); font-size:13px">
                  {{ stageData[1].artifact?.groupId }}:{{ stageData[1].artifact?.artifactId }}
                </span>
              </el-descriptions-item>
              <el-descriptions-item :label="t('analysis.fields.sourceRepo')" :span="2">
                <a :href="stageData[1].sourceRepo" target="_blank">{{ stageData[1].sourceRepo }}</a>
              </el-descriptions-item>
              <el-descriptions-item :label="t('analysis.fields.description')" :span="2">
                {{ stageData[1].description }}
              </el-descriptions-item>
            </el-descriptions>

            <div v-if="stageData[1].fixCommits?.length" style="margin-top:20px">
              <div class="jv-section-label">{{ t('analysis.fixCommits') }}</div>
              <div v-for="c in stageData[1].fixCommits" :key="c" style="margin-top:4px">
                <a :href="c" target="_blank" style="font-family:var(--font-mono); font-size:12px">{{ c }}</a>
              </div>
            </div>

            <!-- References by Category -->
            <div v-if="stageData[1].articles?.length" style="margin-top:20px">
              <div class="jv-section-label">{{ t('analysis.references') }}</div>

              <!-- Advisory -->
              <div v-if="articlesByCategory(stageData[1].articles, 'advisory').length" class="jv-ref-category">
                <div class="jv-ref-category-title">
                  <span class="jv-ref-icon">📋</span>
                  {{ t('analysis.refCategories.advisory') }}
                </div>
                <div v-for="article in articlesByCategory(stageData[1].articles, 'advisory')" :key="article.url" class="jv-ref-item">
                  <a :href="article.url" target="_blank" class="jv-ref-link">
                    {{ article.url }}
                  </a>
                  <span v-if="article.source" class="jv-ref-source">{{ article.source }}</span>
                </div>
              </div>

              <!-- Analysis -->
              <div v-if="articlesByCategory(stageData[1].articles, 'analysis').length" class="jv-ref-category">
                <div class="jv-ref-category-title">
                  <span class="jv-ref-icon">🔍</span>
                  {{ t('analysis.refCategories.analysis') }}
                </div>
                <div v-for="article in articlesByCategory(stageData[1].articles, 'analysis')" :key="article.url" class="jv-ref-item">
                  <a :href="article.url" target="_blank" class="jv-ref-link">
                    {{ article.url }}
                  </a>
                  <span v-if="article.source" class="jv-ref-source">{{ article.source }}</span>
                </div>
              </div>

              <!-- Patch -->
              <div v-if="articlesByCategory(stageData[1].articles, 'patch').length" class="jv-ref-category">
                <div class="jv-ref-category-title">
                  <span class="jv-ref-icon">🔧</span>
                  {{ t('analysis.refCategories.patch') }}
                </div>
                <div v-for="article in articlesByCategory(stageData[1].articles, 'patch')" :key="article.url" class="jv-ref-item">
                  <a :href="article.url" target="_blank" class="jv-ref-link">
                    {{ article.url }}
                  </a>
                  <span v-if="article.source" class="jv-ref-source">{{ article.source }}</span>
                </div>
              </div>

              <!-- PoC -->
              <div v-if="articlesByCategory(stageData[1].articles, 'poc').length" class="jv-ref-category">
                <div class="jv-ref-category-title">
                  <span class="jv-ref-icon">💥</span>
                  {{ t('analysis.refCategories.poc') }}
                </div>
                <div v-for="article in articlesByCategory(stageData[1].articles, 'poc')" :key="article.url" class="jv-ref-item">
                  <a :href="article.url" target="_blank" class="jv-ref-link">
                    {{ article.url }}
                  </a>
                  <span v-if="article.source" class="jv-ref-source">{{ article.source }}</span>
                </div>
              </div>

              <!-- Other -->
              <div v-if="articlesByCategory(stageData[1].articles, 'other').length" class="jv-ref-category">
                <div class="jv-ref-category-title">
                  <span class="jv-ref-icon">📄</span>
                  {{ t('analysis.refCategories.other') }}
                </div>
                <div v-for="article in articlesByCategory(stageData[1].articles, 'other')" :key="article.url" class="jv-ref-item">
                  <a :href="article.url" target="_blank" class="jv-ref-link">
                    {{ article.url }}
                  </a>
                  <span v-if="article.source" class="jv-ref-source">{{ article.source }}</span>
                </div>
              </div>
            </div>

          </div>
          <el-empty v-else :description="t('analysis.intelligenceUnavailable')" />
        </div>

        <!-- Patch Analysis (Combined Stage 2 & 3) -->
        <div v-else-if="selectedStage === 2">
          <div v-if="stageData[2]">
            <!-- Patch Info -->
            <el-descriptions :column="2" border size="small" label-width="140px">
              <el-descriptions-item :label="isMavenSourceDiff ? t('analysis.patch.fixedVersion') : t('analysis.patch.commitHash')">
                <span style="font-family:var(--font-mono)">{{ stageData[2].patchInfo?.commitHash || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item :label="t('analysis.patch.strategy')">
                <span style="font-family:var(--font-mono)">{{ stageData[2].patchInfo?.strategy || stageData[2].patchInfo?.strategyName || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item :label="isMavenSourceDiff ? t('analysis.patch.locateNote') : t('analysis.patch.commitMessage')" :span="2">
                {{ stageData[2].patchInfo?.commitMessage || '—' }}
              </el-descriptions-item>
            </el-descriptions>

            <!-- Diff Viewer -->
            <div v-if="diffContent || diffLoading" class="jv-stage3-section jv-diff-section">
              <DiffViewer
                :diff-content="diffContent"
                :loading="diffLoading"
                :file-decisions="stageData[2].fileDecisions"
                :title="t('analysis.codeDiff')"
                :empty-text="t('diff.empty')"
              />
            </div>

          </div>
          <el-empty v-else :description="t('analysis.patchUnavailable')" />
        </div>

        <!-- AI Reasoning -->
        <div v-else-if="selectedStage === 3">
          <div v-if="stageData[3]" class="jv-reasoning">

            <!-- Trigger Chain (Flow) -->
            <div v-if="stageData[3].trigger_chain" class="rs-section rs-section--trigger">
              <div class="rs-section-header">
                <span class="rs-section-num">01</span>
                <span class="rs-section-title">{{ t('analysis.reasoning.triggerChain') }}</span>
                <span class="rs-section-badge" v-if="stageData[3].trigger_chain.steps">{{ stageData[3].trigger_chain.steps.length }} STEPS</span>
              </div>

              <div class="rs-trigger-summary">{{ stageData[3].trigger_chain.summary }}</div>

              <div class="rs-flow">
                <div class="rs-flow-line"></div>
                <div v-for="step in stageData[3].trigger_chain.steps" :key="step.seq" class="rs-flow-step">
                  <div class="rs-flow-dot"></div>
                  <div>
                    <span class="rs-flow-seq">{{ String(step.seq).padStart(2, '0') }}</span>
                    <span class="rs-flow-class">{{ step.class }}.{{ step.method }}()</span>
                  </div>
                  <div class="rs-flow-desc">{{ step.description }}</div>
                </div>
              </div>

            </div>

            <!-- Impact Assessment -->
            <div v-if="stageData[3].impact" class="rs-section rs-section--impact">
              <div class="rs-section-header">
                <span class="rs-section-num">02</span>
                <span class="rs-section-title">{{ t('analysis.reasoning.impact') }}</span>
              </div>

              <div v-if="stageData[3].impact.attack_vector" class="rs-impact-vector">
                <div class="rs-impact-vector-icon">⚡</div>
                <div class="rs-impact-vector-body">
                  <div class="rs-impact-vector-label">{{ t('analysis.reasoning.attackVector') }}</div>
                  <div class="rs-impact-vector-value">{{ stageData[3].impact.attack_vector }}</div>
                </div>
              </div>

              <div class="rs-impact-grid">
                <div v-if="stageData[3].impact.prerequisites?.length" class="rs-impact-card rs-impact-card--prereq">
                  <div class="rs-impact-card-head">{{ t('analysis.reasoning.prerequisites') }}</div>
                  <ul class="rs-impact-list">
                    <li v-for="p in stageData[3].impact.prerequisites" :key="p">{{ p }}</li>
                  </ul>
                </div>

                <div v-if="stageData[3].impact.consequences?.length" class="rs-impact-card rs-impact-card--conseq">
                  <div class="rs-impact-card-head">{{ t('analysis.reasoning.consequences') }}</div>
                  <ul class="rs-impact-list">
                    <li v-for="c in stageData[3].impact.consequences" :key="c">{{ c }}</li>
                  </ul>
                </div>

                <div v-if="stageData[3].impact.real_world_scenarios?.length" class="rs-impact-card rs-impact-card--scenario">
                  <div class="rs-impact-card-head">{{ t('analysis.reasoning.realWorldScenarios') }}</div>
                  <ul class="rs-impact-list">
                    <li v-for="s in stageData[3].impact.real_world_scenarios" :key="s">{{ s }}</li>
                  </ul>
                </div>
              </div>
            </div>

            <!-- Vulnerability Fix -->
            <div v-if="stageData[3].code_analysis?.fix_description || stageData[3].secure_coding?.recommendations?.length" class="rs-section rs-section--fix">
              <div class="rs-section-header">
                <span class="rs-section-num">03</span>
                <span class="rs-section-title">{{ t('analysis.reasoning.vulnFix') }}</span>
              </div>
              <div v-if="stageData[3].code_analysis?.fix_description" class="rs-fix-desc">
                <div class="rs-fix-desc-icon">✓</div>
                <div class="rs-fix-desc-body">
                  <div class="rs-fix-desc-label">{{ t('analysis.reasoning.fixDescription') }}</div>
                  <div class="rs-fix-desc-text">{{ stageData[3].code_analysis.fix_description }}</div>
                </div>
              </div>
              <div v-if="stageData[3].secure_coding?.recommendations?.length" class="rs-fix-recos">
                <div class="rs-fix-recos-label">{{ t('analysis.reasoning.recommendations') }}</div>
                <ul class="rs-fix-reco-list">
                  <li v-for="r in stageData[3].secure_coding.recommendations" :key="r">{{ r }}</li>
                </ul>
              </div>
            </div>

            <!-- Detection Points -->
            <div v-if="stageData[3].detection_points?.length" class="rs-section rs-section--detect">
              <div class="rs-section-header">
                <span class="rs-section-num">04</span>
                <span class="rs-section-title">{{ t('analysis.reasoning.detectionPoints') }}</span>
                <span class="rs-section-badge">{{ stageData[3].detection_points.length }} POINTS</span>
              </div>

              <div class="rs-dp-filters">
                <div class="rs-dp-filter" :class="{ active: dpFilter === 'all' }" @click="dpFilter = 'all'">
                  全部 <span class="count">{{ stageData[3].detection_points.length }}</span>
                </div>
                <div class="rs-dp-filter" :class="{ active: dpFilter === 'dependency' }" @click="dpFilter = 'dependency'">
                  {{ t('analysis.reasoning.dpTypes.dependency') }} <span class="count">{{ dpCountByType('dependency') }}</span>
                </div>
                <div class="rs-dp-filter" :class="{ active: dpFilter === 'api_usage' }" @click="dpFilter = 'api_usage'">
                  {{ t('analysis.reasoning.dpTypes.api_usage') }} <span class="count">{{ dpCountByType('api_usage') }}</span>
                </div>
                <div class="rs-dp-filter" :class="{ active: dpFilter === 'code_pattern' }" @click="dpFilter = 'code_pattern'">
                  {{ t('analysis.reasoning.dpTypes.code_pattern') }} <span class="count">{{ dpCountByType('code_pattern') }}</span>
                </div>
                <div class="rs-dp-filter" :class="{ active: dpFilter === 'config_risk' }" @click="dpFilter = 'config_risk'">
                  {{ t('analysis.reasoning.dpTypes.config_risk') }} <span class="count">{{ dpCountByType('config_risk') }}</span>
                </div>
              </div>

              <div v-for="dp in filteredDetectionPoints" :key="dp.id" class="rs-dp-card">
                <div class="rs-dp-card-header">
                  <span class="rs-dp-id">{{ dp.id }}</span>
                  <span class="rs-dp-type-tag" :class="'rs-dp-type-' + dp.type">{{ t(`analysis.reasoning.dpTypes.${dp.type}`) }}</span>
                </div>
                <div class="rs-dp-desc">{{ dp.description }}</div>

                <!-- dependency -->
                <div v-if="dp.type === 'dependency'" class="rs-dp-meta">
                  <div class="rs-dp-meta-item">
                    <div class="label">{{ t('analysis.reasoning.dpArtifact') }}</div>
                    <code>{{ dp.artifact }}</code>
                  </div>
                  <div class="rs-dp-meta-item">
                    <div class="label">{{ t('analysis.reasoning.dpAffectedRange') }}</div>
                    <code>{{ dp.affectedVersionRange }}</code>
                  </div>
                  <div class="rs-dp-meta-item">
                    <div class="label">{{ t('analysis.reasoning.dpFixedVersion') }}</div>
                    <code>{{ dp.fixedVersion }}</code>
                  </div>
                </div>

                <!-- code_pattern -->
                <div v-if="dp.type === 'code_pattern'" class="rs-dp-meta">
                  <div v-if="dp.cweId" class="rs-dp-meta-item">
                    <div class="label">CWE</div>
                    <code>{{ dp.cweId }}</code>
                  </div>
                  <div v-if="dp.className" class="rs-dp-meta-item">
                    <div class="label">{{ t('analysis.reasoning.dpClass') }}</div>
                    <code>{{ dp.className }}</code>
                  </div>
                  <div v-if="dp.methodName" class="rs-dp-meta-item">
                    <div class="label">{{ t('analysis.reasoning.dpMethod') }}</div>
                    <code>{{ dp.methodName }}</code>
                  </div>
                  <div v-if="dp.pattern" class="rs-dp-meta-item" style="grid-column: 1 / -1">
                    <div class="label">{{ t('analysis.reasoning.dpPattern') }}</div>
                    <code>{{ dp.pattern }}</code>
                  </div>
                </div>

                <!-- config_risk -->
                <div v-if="dp.type === 'config_risk' && dp.configKeys?.length">
                  <div class="rs-list-label">{{ t('analysis.reasoning.dpConfigKeys') }}</div>
                  <div v-for="ck in dp.configKeys" :key="ck.key" class="rs-dp-config-row">
                    <code>{{ ck.key }}</code> = <span class="risky">{{ ck.riskyValue }}</span>
                  </div>
                </div>

                <!-- api_usage -->
                <div v-if="dp.type === 'api_usage'">
                  <div v-if="dp.dangerousApis?.length" style="margin-top:8px">
                    <div class="rs-list-label">{{ t('analysis.reasoning.dpDangerousApis') }}</div>
                    <code v-for="a in dp.dangerousApis" :key="a" class="rs-dp-api-tag">{{ a }}</code>
                  </div>
                  <div v-if="dp.safeAlternatives?.length" style="margin-top:10px">
                    <div class="rs-list-label">{{ t('analysis.reasoning.dpSafeAlternatives') }}</div>
                    <span v-for="s in dp.safeAlternatives" :key="s" class="rs-dp-safe-tag">{{ s }}</span>
                  </div>
                </div>
              </div>
            </div>

          </div>
          <div v-else style="text-align:center; padding:40px">
            <div v-if="stages.find(s => s.stageNum === 3 && s.status === 'FAILED')"
              style="color:var(--critical)">
              {{ t('analysis.stage4Failed', { error: stages.find(s => s.stageNum === 3)?.errorMsg ?? '' }) }}
              <br/>
              <el-button style="margin-top:12px" @click="rerun(3)">{{ t('analysis.retryReasoning') }}</el-button>
            </div>
            <div v-else style="color:var(--text-disabled)">{{ t('analysis.reasoningUnavailable') }}</div>
          </div>
        </div>

        <!-- Artifacts / Education Lab -->
        <div v-else-if="selectedStage === 4">
          <div v-if="stageData[4] && (stageData[4].status === 'generated' || stageData[4].status === 'paused')" class="jv-stage4-sections">

            <!-- Paused banner -->
            <div v-if="stageData[4].status === 'paused'" class="jv-paused-banner">
              <div class="jv-paused-title">{{ t('analysis.artifacts.pausedTitle') }}</div>
              <div class="jv-paused-reason">{{ stageData[4].pauseReason }}</div>
              <div style="margin-top:8px; font-size:12px; color:var(--text-secondary)">
                {{ t('analysis.artifacts.pausedAt', { turn: stageData[4].pausedAtTurn }) }}
              </div>
              <el-button type="primary" size="small" style="margin-top:10px" @click="rerun(4)">
                {{ t('analysis.artifacts.continueAgent') }}
              </el-button>
            </div>

            <div class="jv-artifacts-summary">
              <span>{{ t('analysis.artifacts.fileCount', { count: stageData[4].fileCount ?? 0 }) }}</span>
              <span v-if="stageData[4].agentTurns != null">{{ t('analysis.artifacts.agentTurns') }}: {{ stageData[4].agentTurns }}</span>
              <span v-if="stageData[4].reviewRevisions != null">{{ t('analysis.artifacts.reviewRevisions') }}: {{ stageData[4].reviewRevisions }}</span>
              <span v-if="stageData[4].javaProfile">{{ t('analysis.artifacts.javaProfile') }}: {{ stageData[4].javaProfile.name }} (Java {{ stageData[4].javaProfile.javaVersion }}, Spring Boot {{ stageData[4].javaProfile.springBootVersion }})</span>
            </div>

            <div v-if="stageData[4].validation" class="jv-reasoning-section s4-c1">
              <div class="jv-section-label">{{ t('analysis.artifacts.backendValidation') }}</div>
              <div class="jv-stage4-validation-grid">
                <div class="jv-stage4-plan-card">
                  <div class="jv-stage4-card-label">{{ t('analysis.artifacts.validationFocus') }}</div>
                  <div class="jv-stage4-card-text">{{ stageData[4].validation.focus || 'full' }}</div>
                </div>
                <div class="jv-stage4-plan-card">
                  <div class="jv-stage4-card-label">{{ t('analysis.artifacts.validationCompile') }}</div>
                  <div class="jv-stage4-card-text" :class="stageData[4].validation.compileOk ? 'jv-stage4-verdict-ok' : 'jv-stage4-verdict-fail'">{{ stageData[4].validation.compileOk ? t('analysis.artifacts.verified') : t('analysis.artifacts.failed') }}</div>
                </div>
                <div class="jv-stage4-plan-card">
                  <div class="jv-stage4-card-label">{{ t('analysis.artifacts.validationStartup') }}</div>
                  <div class="jv-stage4-card-text" :class="stageData[4].validation.startupOk ? 'jv-stage4-verdict-ok' : 'jv-stage4-verdict-fail'">{{ stageData[4].validation.startupOk ? t('analysis.artifacts.verified') : t('analysis.artifacts.failed') }}</div>
                </div>
                <div class="jv-stage4-plan-card">
                  <div class="jv-stage4-card-label">{{ t('analysis.artifacts.validationPoc') }}</div>
                  <div class="jv-stage4-card-text" :class="stageData[4].validation.pocVerified ? 'jv-stage4-verdict-ok' : 'jv-stage4-verdict-fail'">{{ stageData[4].validation.pocVerified ? t('analysis.artifacts.verified') : t('analysis.artifacts.failed') }}</div>
                </div>
              </div>
              <div class="jv-stage4-validation-messages">
                <div v-if="stageData[4].validation.compileMessage" class="jv-stage4-plan-card">
                  <div class="jv-stage4-card-label">{{ t('analysis.artifacts.validationCompileMessage') }}</div>
                  <pre class="jv-stage4-pre">{{ stageData[4].validation.compileMessage }}</pre>
                </div>
                <div v-if="stageData[4].validation.startupMessage" class="jv-stage4-plan-card">
                  <div class="jv-stage4-card-label">{{ t('analysis.artifacts.validationStartupMessage') }}</div>
                  <pre class="jv-stage4-pre">{{ stageData[4].validation.startupMessage }}</pre>
                </div>
                <div v-if="stageData[4].validation.pocMessage" class="jv-stage4-plan-card">
                  <div class="jv-stage4-card-label">{{ t('analysis.artifacts.validationPocMessage') }}</div>
                  <pre class="jv-stage4-pre">{{ stageData[4].validation.pocMessage }}</pre>
                </div>
              </div>
              <div v-if="stage4ValidationArtifacts.length" class="jv-stage4-validation-artifacts">
                <div class="jv-stage4-card-label">{{ t('analysis.artifacts.validationEvidence') }}</div>
                <div class="jv-stage4-evidence-grid">
                  <div v-for="item in stage4ValidationArtifacts" :key="item.key" class="jv-stage4-evidence-card">
                    <div class="jv-stage4-evidence-key">{{ item.key }}</div>
                    <ul v-if="hasEvidenceList(item.value)" class="jv-stage4-list">
                      <li v-for="entry in item.value as Array<string | number>" :key="String(entry)">{{ entry }}</li>
                    </ul>
                    <pre v-else class="jv-stage4-pre">{{ formatEvidenceValue(item.value) }}</pre>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="stageData[4].failureReason || stageData[4].verification?.reason || stageData[4].poc?.reason" class="jv-reasoning-section s4-c2">
              <div class="jv-section-label">{{ t('analysis.artifacts.verificationReview') }}</div>
              <div class="jv-artifacts-review">
                <div v-if="stageData[4].verification?.verdict" class="jv-artifacts-review-verdict">
                  {{ t('analysis.artifacts.reviewVerdict') }}: {{ stageData[4].verification?.verdict }}
                </div>
                <div class="jv-artifacts-review-reason">
                  {{ stageData[4].verification?.reason || stageData[4].poc?.reason || stageData[4].failureReason }}
                </div>
                <div v-if="stageData[4].verification?.matchedSignals?.length" class="jv-artifacts-review-actions">
                  <div class="jv-artifacts-review-actions-title">{{ t('analysis.artifacts.matchedSignals') }}</div>
                  <ul>
                    <li v-for="signal in stageData[4].verification.matchedSignals" :key="signal">{{ signal }}</li>
                  </ul>
                </div>
                <div v-if="stageData[4].verification?.missingEvidence?.length" class="jv-artifacts-review-actions">
                  <div class="jv-artifacts-review-actions-title">{{ t('analysis.artifacts.missingEvidence') }}</div>
                  <ul>
                    <li v-for="item in stageData[4].verification.missingEvidence" :key="item">{{ item }}</li>
                  </ul>
                </div>
                <div v-if="stageData[4].verification?.falsePositiveRisks?.length" class="jv-artifacts-review-actions">
                  <div class="jv-artifacts-review-actions-title">{{ t('analysis.artifacts.falsePositiveRisks') }}</div>
                  <ul>
                    <li v-for="risk in stageData[4].verification.falsePositiveRisks" :key="risk">{{ risk }}</li>
                  </ul>
                </div>
                <div v-if="stageData[4].verification?.nextActions?.length" class="jv-artifacts-review-actions">
                  <div class="jv-artifacts-review-actions-title">{{ t('analysis.artifacts.nextActions') }}</div>
                  <ul>
                    <li v-for="action in stageData[4].verification.nextActions" :key="action">{{ action }}</li>
                  </ul>
                </div>
                <el-button v-if="stages.find(s => s.stageNum === 4 && s.status === 'FAILED')" style="margin-top:12px" @click="rerun(4)">
                  {{ t('analysis.retryArtifacts') }}
                </el-button>
              </div>
            </div>

            <!-- File list -->
            <div class="jv-reasoning-section s4-c3">
              <div class="jv-section-label">{{ t('analysis.artifacts.fileList') }}</div>
              <div class="jv-artifacts-files">
                <div v-for="f in stageData[4].files" :key="f.path" class="jv-artifact-file">
                  <span :class="'jv-artifact-type jv-artifact-type-' + f.type">{{ f.type }}</span>
                  <code>{{ f.path }}</code>
                </div>
              </div>
            </div>

            <!-- Report preview -->
            <div v-if="reportMarkdown" class="jv-reasoning-section s4-c4">
              <div class="jv-section-label">{{ t('analysis.artifacts.reportPreview') }}</div>
              <div class="jv-report-preview" v-html="renderMarkdown(reportMarkdown)"></div>
            </div>
            <div v-else-if="stageData[4].report?.status === 'generated'" class="jv-reasoning-section s4-c4">
              <div class="jv-section-label">{{ t('analysis.artifacts.reportPreview') }}</div>
              <div style="color:var(--text-disabled); font-size:13px">{{ t('analysis.artifacts.noReport') }}</div>
            </div>

            <!-- PoC files -->
            <div v-if="stageData[4].poc?.files?.length" class="jv-reasoning-section s4-c5">
              <div class="jv-section-label">{{ t('analysis.artifacts.pocPreview') }}</div>
              <div v-for="f in stage4PocFiles" :key="f.path" class="jv-poc-block">
                <div class="jv-file-header">
                  <span style="font-family:var(--font-mono); font-size:13px; color:var(--accent-light)">{{ f.path }}</span>
                </div>
              </div>
            </div>

            <!-- Reproduction Steps -->
            <div v-if="stageData[4].reproductionSteps?.length" class="jv-reasoning-section s4-c6">
              <div class="jv-section-label">{{ t('analysis.artifacts.reproductionSteps') }}</div>
              <div class="jv-reproduction-steps">
                <div v-for="s in stageData[4].reproductionSteps" :key="s.step" class="jv-repro-step">
                  <div class="jv-repro-step-header">
                    <span class="jv-repro-step-num">{{ s.step }}</span>
                    <span class="jv-repro-step-title">{{ s.title }}</span>
                  </div>
                  <code class="jv-repro-step-cmd">{{ s.command }}</code>
                  <div class="jv-repro-step-desc">{{ s.description }}</div>
                </div>
              </div>
            </div>

            <!-- Agent Transcript -->
            <div v-if="transcriptEvents.length" class="jv-reasoning-section s4-c7">
              <div class="jv-section-label" style="cursor:pointer; user-select:none" @click="transcriptExpanded = !transcriptExpanded">
                Agent Transcript ({{ transcriptEvents.length }} events)
                <span style="font-size:11px; margin-left:8px; color:var(--text-muted)">{{ transcriptExpanded ? '▼' : '▶' }}</span>
              </div>
              <div v-if="transcriptExpanded" class="jv-transcript-list">
                <div v-for="(evt, idx) in transcriptEvents" :key="idx" class="jv-transcript-event">
                  <div class="jv-transcript-header">
                    <span class="jv-transcript-turn">Turn {{ evt.turn }}</span>
                    <span class="jv-transcript-type" :class="'jv-type-' + evt.type">{{ evt.type }}</span>
                    <span class="jv-transcript-phase">{{ evt.phase }}</span>
                  </div>
                  <pre class="jv-transcript-payload">{{ JSON.stringify(evt.payload, null, 2) }}</pre>
                </div>
              </div>
            </div>

          </div>
          <div v-else style="text-align:center; padding:40px">
            <div v-if="stages.find(s => s.stageNum === 4 && s.status === 'FAILED')"
              style="color:var(--critical)">
              {{ t('analysis.stage4Failed', { error: stages.find(s => s.stageNum === 4)?.errorMsg ?? '' }) }}
              <br/>
              <el-button style="margin-top:12px" @click="rerun(4)">{{ t('analysis.retryArtifacts') }}</el-button>
            </div>
            <div v-else style="color:var(--text-disabled)">{{ t('analysis.artifactsUnavailable') }}</div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
  <el-skeleton v-else :rows="8" animated style="padding:20px" />
</template>

<style scoped>
.jv-detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
  gap: 12px;
}
.jv-detail-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.jv-detail-header-right {
  display: flex;
  gap: 8px;
}
.jv-back-btn {
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  font-family: var(--font-mono);
}
.jv-back-btn:hover { color: var(--text-primary); }

/* Pipeline */
.jv-pipeline-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1px;
  background: var(--border-subtle);
  border: 1px solid var(--border-subtle);
  margin-bottom: 20px;
}
.jv-stage {
  padding: 14px 10px;
  text-align: center;
  cursor: pointer;
  transition: filter .15s;
  user-select: none;
}
.jv-stage:hover { filter: brightness(1.15); }
.jv-stage-selected {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
  filter: brightness(1.12);
}
.jv-stage-completed { background: #1c3a29; border-top: 2px solid #42be65; }
.jv-stage-running   { background: #2a1f00; border-top: 2px solid #f1c21b; }
.jv-stage-failed    { background: #2a0000; border-top: 2px solid #fa4d56; }
.jv-stage-pending   { background: var(--bg-surface); border-top: 2px solid var(--border); }
.jv-stage-num {
  font-family: var(--font-mono);
  font-size: 18px;
  font-weight: 300;
  color: var(--text-disabled);
  line-height: 1;
}
.jv-stage-completed .jv-stage-num { color: #42be65; }
.jv-stage-running   .jv-stage-num { color: #f1c21b; }
.jv-stage-failed    .jv-stage-num { color: #fa4d56; }
.jv-stage-name {
  font-size: 11px;
  color: var(--text-secondary);
  margin-top: 6px;
  letter-spacing: .3px;
}
.jv-stage-status {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
  margin-top: 2px;
  text-transform: uppercase;
  letter-spacing: .5px;
}
.jv-stage-completed .jv-stage-status { color: #42be65; }
.jv-stage-running   .jv-stage-status { color: #f1c21b; }
.jv-stage-failed    .jv-stage-status { color: #fa4d56; }

/* Result panel */
.jv-result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 16px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--border-subtle);
}
.jv-result-title-row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  min-width: 0;
}
.jv-result-title {
  font-family: var(--font-mono);
  font-size: 16px;
  color: var(--text-primary);
  white-space: nowrap;
}
.jv-result-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  color: var(--text-disabled);
  font-family: var(--font-mono);
  font-size: 11px;
}
.jv-stage-error {
  color: var(--critical);
  background: rgba(250,77,86,.08);
  border: 1px solid rgba(250,77,86,.25);
  padding: 10px 12px;
  margin-bottom: 16px;
  font-size: 12px;
  font-family: var(--font-mono);
  white-space: pre-wrap;
}
/* Patch locate view */
.jv-patch-files {
  margin-top: 20px;
}
.jv-patch-files .jv-section-label {
  margin-bottom: 10px;
}
.jv-patch-file {
  background: var(--bg-base);
  border: 1px solid var(--border-subtle);
  margin-bottom: 10px;
}
.jv-patch-file-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-left: 3px solid var(--accent);
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent-light);
}
.jv-patch-change-type {
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 2px 8px;
  border: 1px solid transparent;
}
.jv-patch-change-added {
  color: #42be65;
  background: rgba(66,190,101,.12);
  border-color: rgba(66,190,101,.28);
}
.jv-patch-change-modified {
  color: #78a9ff;
  background: rgba(120,169,255,.12);
  border-color: rgba(120,169,255,.28);
}
.jv-patch-change-deleted {
  color: #fa4d56;
  background: rgba(250,77,86,.12);
  border-color: rgba(250,77,86,.28);
}
/* Section label */
.jv-section-label {
  position: relative;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  letter-spacing: .5px;
  padding: 0 0 12px 14px;
  margin-bottom: 16px;
  border-bottom: 1px solid rgba(15,98,254,.3);
}
.jv-section-label::before {
  content: '';
  position: absolute;
  left: 0; top: 1px;
  width: 4px; height: 17px;
  background: var(--accent);
  border-radius: 2px;
}

/* Stage 4 — numbered section headers (like stage 3) */
.jv-stage4-sections { counter-reset: s4section; }
.jv-stage4-sections .jv-reasoning-section { counter-increment: s4section; }
.jv-stage4-sections .jv-section-label {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 0 12px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.jv-stage4-sections .jv-section-label::before {
  content: counter(s4section, decimal-leading-zero);
  position: static;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px; height: 34px;
  flex-shrink: 0;
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 700;
  color: var(--accent-light);
  background: rgba(15,98,254,.12);
  border: 1px solid rgba(15,98,254,.35);
  border-radius: 8px;
  transform: none;
}
.jv-stage4-sections .s4-c1 .jv-section-label::before { color: var(--accent-light); background: rgba(15,98,254,.12); border-color: rgba(15,98,254,.35); }
.jv-stage4-sections .s4-c2 .jv-section-label::before { color: var(--critical); background: rgba(250,77,86,.12); border-color: rgba(250,77,86,.35); }
.jv-stage4-sections .s4-c3 .jv-section-label::before { color: var(--success); background: rgba(66,190,101,.12); border-color: rgba(66,190,101,.35); }
.jv-stage4-sections .s4-c4 .jv-section-label::before { color: var(--medium); background: rgba(241,194,27,.12); border-color: rgba(241,194,27,.35); }
.jv-stage4-sections .s4-c5 .jv-section-label::before { color: #a06eff; background: rgba(160,110,255,.12); border-color: rgba(160,110,255,.35); }
.jv-stage4-sections .s4-c6 .jv-section-label::before { color: #33b1ff; background: rgba(51,177,255,.12); border-color: rgba(51,177,255,.35); }
.jv-stage4-sections .s4-c7 .jv-section-label::before { color: #ff7eb6; background: rgba(255,126,182,.12); border-color: rgba(255,126,182,.35); }

.jv-stage3-section {
  margin-bottom: 28px;
}
.jv-diff-section {
  margin-top: 28px;
}
.jv-stage3-summary-meta {
  margin-bottom: 12px;
  color: var(--text-secondary);
  font-size: 12px;
}
.jv-stage3-summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.jv-stage3-summary-card {
  border: 1px solid var(--border-subtle);
  background: var(--bg-base);
  padding: 12px;
}
.jv-stage3-summary-title {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-secondary);
  text-transform: uppercase;
}
.jv-stage3-summary-count {
  margin: 8px 0 6px;
  font-family: var(--font-mono);
  font-size: 24px;
  color: var(--text-primary);
}
.jv-stage3-summary-desc {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
}
.jv-stage3-layer-group {
  margin-bottom: 24px;
}
.jv-stage3-layer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  margin-bottom: 12px;
  background: var(--bg-base);
  border: 1px solid var(--border-subtle);
}
.jv-stage3-layer-title {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-primary);
}
.jv-stage3-layer-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}
.jv-stage3-layer-badge {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--accent-light);
  border: 1px solid rgba(120,169,255,.3);
  background: rgba(120,169,255,.08);
  padding: 2px 6px;
  white-space: nowrap;
}
.jv-stage3-file-reason {
  margin: -10px 0 14px;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}

/* File block */
.jv-file-block {
  margin-bottom: 28px;
  padding-bottom: 28px;
  border-bottom: 1px solid var(--border-subtle);
}
.jv-file-block:last-child { border-bottom: none; }
.jv-file-header {
  background: var(--bg-base);
  border-left: 3px solid var(--accent);
  padding: 8px 12px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

/* Call chain */
.jv-chain-item {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent-light);
  background: rgba(15,98,254,.1);
  padding: 3px 10px;
  border: 1px solid rgba(15,98,254,.25);
}

/* Reasoning view - Redesigned UI */

/* Legacy classes kept for Stage 4 */
.jv-reasoning-section {
  margin-bottom: 28px;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--border-subtle);
}
.jv-reasoning-section:last-of-type { border-bottom: none; }
.jv-reasoning-section .jv-section-label { margin-bottom: 12px; }
.jv-reasoning-field {
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 6px;
}
.jv-field-label {
  color: var(--text-disabled);
  font-size: 12px;
  font-family: var(--font-mono);
}

/* Stage 3 redesigned styles */
.rs-section { margin-bottom: 32px; }
.rs-section-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 18px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-subtle);
}
.rs-section-num {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px; height: 34px;
  flex-shrink: 0;
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 700;
  color: var(--accent-light);
  background: rgba(15,98,254,.12);
  border: 1px solid rgba(15,98,254,.35);
  border-radius: 8px;
}
.rs-section-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--text-primary);
  letter-spacing: .5px;
}
.rs-section-badge {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--accent-light);
  background: rgba(15,98,254,.1);
  border: 1px solid rgba(15,98,254,.25);
  padding: 2px 8px;
}
/* per-section accent colors */
.rs-section--trigger .rs-section-num { color: var(--accent-light); background: rgba(15,98,254,.12); border-color: rgba(15,98,254,.35); }
.rs-section--trigger .rs-section-header { border-bottom-color: rgba(15,98,254,.3); }
.rs-section--impact .rs-section-num { color: var(--critical); background: rgba(250,77,86,.12); border-color: rgba(250,77,86,.35); }
.rs-section--impact .rs-section-header { border-bottom-color: rgba(250,77,86,.3); }
.rs-section--fix .rs-section-num { color: var(--success); background: rgba(66,190,101,.12); border-color: rgba(66,190,101,.35); }
.rs-section--fix .rs-section-header { border-bottom-color: rgba(66,190,101,.3); }
.rs-section--detect .rs-section-num { color: var(--medium); background: rgba(241,194,27,.12); border-color: rgba(241,194,27,.35); }
.rs-section--detect .rs-section-header { border-bottom-color: rgba(241,194,27,.3); }
.rs-trigger-summary {
  color: var(--text-secondary);
  font-size: 13px;
  margin-bottom: 16px;
  line-height: 1.7;
}
.rs-flow { position: relative; padding-left: 32px; }
.rs-flow-line {
  position: absolute;
  left: 11px; top: 0; bottom: 0;
  width: 2px;
  background: linear-gradient(180deg, var(--accent) 0%, var(--critical) 100%);
}
.rs-flow-step {
  position: relative;
  padding: 10px 16px;
  margin-bottom: 8px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-left: none;
  transition: border-color .15s;
}
.rs-flow-step:hover { border-color: rgba(120,169,255,.4); }
.rs-flow-step:last-child { margin-bottom: 0; }
.rs-flow-step:last-child .rs-flow-dot {
  border-color: var(--critical);
  background: rgba(250,77,86,.2);
}
.rs-flow-dot {
  position: absolute;
  left: -27px; top: 14px;
  width: 12px; height: 12px;
  border-radius: 50%;
  background: var(--bg-body);
  border: 2px solid var(--accent);
}
.rs-flow-seq {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--accent-light);
  margin-right: 8px;
}
.rs-flow-class {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent-light);
}
.rs-flow-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}
.rs-list-label {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
  letter-spacing: .5px;
  margin-bottom: 8px;
}

/* Impact Assessment — attack vector banner */
.rs-impact-vector {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 14px 16px;
  margin-bottom: 16px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-left: 2px solid rgba(250,77,86,.5);
}
.rs-impact-vector-icon {
  font-size: 16px;
  line-height: 1.4;
  flex-shrink: 0;
  opacity: .8;
}
.rs-impact-vector-label {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
  letter-spacing: .5px;
  text-transform: uppercase;
  margin-bottom: 5px;
}
.rs-impact-vector-value {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.6;
}

/* Impact Assessment — card grid */
.rs-impact-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}
.rs-impact-card {
  padding: 14px 16px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-top: 2px solid var(--border);
  transition: border-color .15s, transform .15s;
}
.rs-impact-card:hover {
  transform: translateY(-2px);
}
.rs-impact-card--prereq { border-top-color: var(--accent); }
.rs-impact-card--prereq:hover { border-color: rgba(120,169,255,.4); }
.rs-impact-card--conseq { border-top-color: var(--critical); }
.rs-impact-card--conseq:hover { border-color: rgba(250,77,86,.4); }
.rs-impact-card--scenario { border-top-color: var(--medium); }
.rs-impact-card--scenario:hover { border-color: rgba(241,194,27,.4); }
.rs-impact-card-head {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
  letter-spacing: .5px;
  text-transform: uppercase;
  margin-bottom: 10px;
}
.rs-impact-list { list-style: none; padding: 0; margin: 0; }
.rs-impact-list li {
  position: relative;
  padding: 5px 0 5px 16px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.55;
}
.rs-impact-list li::before {
  content: '›';
  position: absolute;
  left: 0;
  color: var(--text-disabled);
  font-family: var(--font-mono);
}

/* Vulnerability Fix — description */
.rs-fix-desc {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 14px 16px;
  margin-bottom: 16px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-left: 2px solid rgba(66,190,101,.5);
}
.rs-fix-desc-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px; height: 20px;
  color: var(--success);
  font-size: 13px;
  flex-shrink: 0;
  opacity: .8;
}
.rs-fix-desc-label {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
  letter-spacing: .5px;
  text-transform: uppercase;
  margin-bottom: 5px;
}
.rs-fix-desc-text {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.7;
}

/* Vulnerability Fix — recommendations */
.rs-fix-recos {
  padding: 14px 16px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
}
.rs-fix-recos-label {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
  letter-spacing: .5px;
  text-transform: uppercase;
  margin-bottom: 10px;
}
.rs-fix-reco-list { list-style: none; padding: 0; margin: 0; counter-reset: reco; }
.rs-fix-reco-list li {
  position: relative;
  padding: 7px 0 7px 28px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.55;
  counter-increment: reco;
}
.rs-fix-reco-list li::before {
  content: counter(reco);
  position: absolute;
  left: 0; top: 7px;
  width: 18px; height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--success);
  background: rgba(66,190,101,.12);
  border: 1px solid rgba(66,190,101,.35);
  border-radius: 50%;
}
.rs-dp-filters {
  display: flex;
  gap: 6px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.rs-dp-filter {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  font-family: var(--font-mono);
  font-size: 11px;
  cursor: pointer;
  border: 1px solid var(--border-subtle);
  background: var(--bg-surface);
  color: var(--text-secondary);
  transition: all .15s;
}
.rs-dp-filter:hover { border-color: rgba(120,169,255,.4); }
.rs-dp-filter.active {
  background: rgba(15,98,254,.12);
  border-color: rgba(15,98,254,.4);
  color: var(--accent-light);
}
.rs-dp-filter .count { font-size: 10px; color: var(--text-disabled); }
.rs-dp-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  margin-bottom: 8px;
  padding: 14px 16px;
}
.rs-dp-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.rs-dp-id {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-disabled);
}
.rs-dp-type-tag {
  font-family: var(--font-mono);
  font-size: 10px;
  padding: 2px 8px;
  letter-spacing: .3px;
}
.rs-dp-type-dependency   { background: rgba(15,98,254,.15); color: var(--accent-light); border: 1px solid rgba(15,98,254,.3); }
.rs-dp-type-code_pattern { background: rgba(250,77,86,.12);  color: #fa4d56; border: 1px solid rgba(250,77,86,.3); }
.rs-dp-type-config_risk  { background: rgba(241,194,27,.12); color: #f1c21b; border: 1px solid rgba(241,194,27,.3); }
.rs-dp-type-api_usage    { background: rgba(190,98,255,.12); color: #be62ff; border: 1px solid rgba(190,98,255,.3); }
.rs-dp-desc {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.5;
  margin-bottom: 10px;
}
.rs-dp-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  font-size: 12px;
}
.rs-dp-meta-item .label {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
}
.rs-dp-meta-item code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent-light);
  background: rgba(15,98,254,.08);
  padding: 1px 6px;
}
.rs-dp-config-row {
  padding: 4px 0;
  font-family: var(--font-mono);
  font-size: 12px;
}
.rs-dp-config-row code { color: var(--text-primary); }
.rs-dp-config-row .risky { color: var(--critical); }
.rs-dp-api-tag {
  display: inline-block;
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 2px 8px;
  margin: 2px 4px 2px 0;
  background: rgba(250,77,86,.08);
  color: var(--critical);
  border: 1px solid rgba(250,77,86,.2);
}
.rs-dp-safe-tag {
  display: inline-block;
  font-size: 11px;
  padding: 2px 8px;
  margin: 2px 4px 2px 0;
  background: rgba(66,190,101,.08);
  color: var(--success);
  border: 1px solid rgba(66,190,101,.2);
}

/* Artifacts tab */
.jv-artifacts-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 20px;
  padding: 10px 14px;
  background: var(--bg-base);
}
.jv-stage4-plan-grid,
.jv-stage4-validation-grid,
.jv-stage4-evidence-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}
.jv-stage4-plan-lists,
.jv-stage4-validation-messages,
.jv-stage4-validation-artifacts {
  margin-top: 14px;
}
.jv-stage4-plan-list + .jv-stage4-plan-list {
  margin-top: 12px;
}
.jv-stage4-plan-card,
.jv-stage4-evidence-card {
  background: var(--bg-base);
  border: 1px solid var(--border-subtle);
  padding: 12px 14px;
}
.jv-stage4-card-label,
.jv-stage4-evidence-key {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-disabled);
  text-transform: uppercase;
  letter-spacing: .5px;
  margin-bottom: 8px;
}
.jv-stage4-card-text {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.jv-stage4-verdict-ok   { color: #42be65; }
.jv-stage4-verdict-fail { color: #fa4d56; }
.jv-stage4-list {
  margin: 0;
  padding-left: 18px;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.6;
}
.jv-stage4-pre {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
  font-family: var(--font-mono);
  white-space: pre-wrap;
  word-break: break-word;
}
.jv-artifacts-review {
  border: 1px solid rgba(250,77,86,.22);
  background: rgba(250,77,86,.08);
  border-radius: 8px;
  padding: 12px 14px;
}
.jv-artifacts-review-verdict {
  color: var(--critical);
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 6px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.jv-artifacts-review-reason {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.jv-artifacts-review-actions {
  margin-top: 10px;
}
.jv-artifacts-review-actions-title {
  color: var(--text-secondary);
  font-size: 12px;
  margin-bottom: 6px;
}
.jv-artifacts-review-actions ul {
  margin: 0;
  padding-left: 18px;
  color: var(--text-primary);
  font-size: 13px;
}
.jv-artifacts-review-actions li {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.jv-artifacts-files {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
}
.jv-artifact-file {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  background: var(--bg-base);
  font-size: 13px;
}
.jv-artifact-file code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-secondary);
}
.jv-artifact-type {
  font-family: var(--font-mono);
  font-size: 10px;
  padding: 2px 8px;
  text-transform: uppercase;
  letter-spacing: .5px;
  min-width: 80px;
  text-align: center;
}
.jv-artifact-type-vuln-demo     { background: rgba(15,98,254,.15); color: var(--accent-light); border: 1px solid rgba(15,98,254,.3); }
.jv-artifact-type-poc           { background: rgba(250,77,86,.12);  color: #fa4d56; border: 1px solid rgba(250,77,86,.3); }
.jv-artifact-type-report        { background: rgba(66,190,101,.12); color: #42be65; border: 1px solid rgba(66,190,101,.3); }
.jv-artifact-type-docker-compose { background: rgba(241,194,27,.12); color: #f1c21b; border: 1px solid rgba(241,194,27,.3); }
.jv-report-preview {
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.7;
  max-height: 600px;
  overflow-y: auto;
  padding: 16px;
  background: var(--bg-base);
  border-left: 3px solid var(--border);
}
.jv-report-preview h2, .jv-report-preview h3, .jv-report-preview h4 {
  font-family: var(--font-mono);
}
.jv-poc-block {
  margin-bottom: 10px;
}

.jv-reproduction-steps {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.jv-repro-step {
  background: rgba(0,0,0,.15);
  border: 1px solid rgba(255,255,255,.06);
  border-radius: 8px;
  padding: 14px 16px;
}
.jv-repro-step-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.jv-repro-step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}
.jv-repro-step-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}
.jv-repro-step-cmd {
  display: block;
  background: rgba(0,0,0,.3);
  border: 1px solid rgba(255,255,255,.08);
  border-radius: 6px;
  padding: 10px 14px;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--accent-light);
  margin-bottom: 6px;
  white-space: pre-wrap;
  word-break: break-all;
  user-select: all;
}
.jv-repro-step-desc {
  font-size: 12px;
  color: var(--text-secondary);
}
.jv-transcript-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 600px;
  overflow-y: auto;
}
.jv-transcript-event {
  background: rgba(0,0,0,.2);
  border: 1px solid rgba(255,255,255,.06);
  border-radius: 8px;
  padding: 12px;
}
.jv-transcript-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 12px;
}
.jv-transcript-turn {
  font-weight: 600;
  color: var(--accent);
}
.jv-transcript-type {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-family: var(--font-mono);
}
.jv-type-assistant {
  background: rgba(16,185,129,.15);
  color: #10b981;
}
.jv-type-directive {
  background: rgba(59,130,246,.15);
  color: #3b82f6;
}
.jv-type-tool_results {
  background: rgba(168,85,247,.15);
  color: #a855f7;
}
.jv-type-compact {
  background: rgba(234,179,8,.15);
  color: #eab308;
}
.jv-transcript-phase {
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}
.jv-transcript-payload {
  background: rgba(0,0,0,.3);
  border: 1px solid rgba(255,255,255,.08);
  border-radius: 6px;
  padding: 10px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-secondary);
  max-height: 400px;
  overflow: auto;
  margin: 0;
}
.jv-paused-banner {
  background: rgba(250,77,86,.08);
  border: 1px solid rgba(250,77,86,.3);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 16px;
}
.jv-paused-title {
  font-weight: 600;
  color: var(--critical);
  margin-bottom: 6px;
}
.jv-paused-reason {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}

/* References by Category */
.jv-ref-category {
  margin-top: 16px;
  background: rgba(0,0,0,.2);
  border: 1px solid rgba(255,255,255,.06);
  border-radius: 8px;
  padding: 12px 16px;
}
.jv-ref-category-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 13px;
  color: var(--accent-light);
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(255,255,255,.08);
}
.jv-ref-icon {
  font-size: 16px;
}
.jv-ref-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px solid rgba(255,255,255,.03);
}
.jv-ref-item:last-child {
  border-bottom: none;
}
.jv-ref-link {
  flex: 1;
  font-size: 13px;
  color: var(--accent);
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.jv-ref-link:hover {
  color: var(--accent-bright);
  text-decoration: underline;
}
.jv-ref-source {
  font-size: 11px;
  font-family: var(--font-mono);
  color: var(--text-disabled);
  flex-shrink: 0;
}
</style>

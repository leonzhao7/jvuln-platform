<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, type TaskDetail } from '../api'
import { ElMessage } from 'element-plus'
import DiffViewer from '../components/DiffViewer.vue'
import { useI18n } from '../i18n'
import { cweName } from '../cwe'
import { marked, Renderer } from 'marked'
import {
  ArrowLeft, ScrollText, Square, RotateCcw, Play, ChevronRight, ChevronDown,
  ClipboardList, Search, Wrench, Bomb, FileText, Zap, Check, Download,
} from 'lucide-vue-next'
import hljs from 'highlight.js/lib/core'
import java from 'highlight.js/lib/languages/java'
import xml from 'highlight.js/lib/languages/xml'
import json from 'highlight.js/lib/languages/json'
import bash from 'highlight.js/lib/languages/bash'
import diff from 'highlight.js/lib/languages/diff'
import yaml from 'highlight.js/lib/languages/yaml'
import sql from 'highlight.js/lib/languages/sql'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import python from 'highlight.js/lib/languages/python'
import properties from 'highlight.js/lib/languages/properties'

const langs: Record<string, any> = { java, xml, html: xml, json, bash, sh: bash, shell: bash,
  diff, yaml, yml: yaml, sql, javascript, js: javascript, typescript, ts: typescript, python, py: python, properties }
Object.entries(langs).forEach(([name, def]) => hljs.registerLanguage(name, def))

const route = useRoute()
const router = useRouter()
const { t, array, locale } = useI18n()
const cveId = route.params.cveId as string

const detail = ref<TaskDetail | null>(null)
const selectedStage = ref(1)
const stageData = ref<Record<number, any>>({})
const diffContent = ref('')
const diffLoading = ref(false)
const reportMarkdown = ref('')
const sseActive = ref(false)
const cancelling = ref(false)
const expandedFiles = ref<Set<string>>(new Set())
const fileContents = ref<Record<string, string>>({})
const fileLoading = ref<Set<string>>(new Set())
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
const terminalVisible = ref(false)
const collapsedStages = ref(new Set<number>())
let evtSource: EventSource | null = null

const stage4Hint = ref('')
const stage4Uploading = ref(false)
const stage4UploadFile = ref<File | null>(null)

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
const stage4Failed = computed(() =>
  stages.value.some(s => s.stageNum === 4 && s.status === 'FAILED'))

const stageIcons = ['01', '02', '03', '04', '05']
const stageNames = computed(() => array<string>('analysis.stageNames'))

const selectedStageRecord = computed(() =>
  stages.value.find(s => s.stageNum === selectedStage.value)
)
const stage4Data = computed(() => stageData.value[4] ?? null)
const isMavenSourceDiff = computed(() => {
  const p = stageData.value[2]?.patchInfo
  return (p?.strategy || p?.strategyName) === 'maven-source-diff'
})
const stage4ValidationArtifacts = computed(() => {
  const artifacts = stage4Data.value?.validation?.artifacts
  if (!artifacts || typeof artifacts !== 'object') return []
  return Object.entries(artifacts).map(([key, value]) => ({ key, value }))
})

interface PocStep {
  side: string
  phase: string
  label: string
  body: string
}
const stage4PocSteps = computed<PocStep[]>(() => {
  const steps = stage4Data.value?.validation?.pocSteps
  return Array.isArray(steps) ? steps.filter((s: PocStep) => s && (s.body?.trim() || s.label?.trim())) : []
})
const pocStepSide = (step: PocStep) => {
  // Response/startup/verify are produced on the server; only requests are client-side.
  if (step.phase === 'request') return 'client'
  if (step.phase === 'response' || step.phase === 'startup' || step.phase === 'verify') return 'server'
  return step.side === 'server' ? 'server' : 'client'
}
const pocPhaseLabel = (phase: string) => {
  const map: Record<string, string> = {
    startup: t('analysis.artifacts.pocPhaseStartup'),
    request: t('analysis.artifacts.pocPhaseRequest'),
    response: t('analysis.artifacts.pocPhaseResponse'),
    verify: t('analysis.artifacts.pocPhaseVerify'),
  }
  return map[phase] ?? phase
}
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
  try { stageData.value[4] = await api.getArtifacts(cveId) } catch {}
  try { const r = await api.getReport(cveId); reportMarkdown.value = r.markdown } catch {}

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
      formatted = `[OK] ${stageLabel(stageNum)} · ${message}`
      break
    case 'error':
      formatted = `[ERR] ${stageNum ? stageLabel(stageNum) + ' · ' : ''}${message}`
      break
    case 'pipeline_done':
      formatted = `[OK] ${message}`
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
      cancelling.value = false
      evtSource?.close()
      if (type === 'error' && stageNum === 4) {
        ElMessage.error(message || t('analysis.artifacts.uploadValidationFailed'))
      }
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
    cancelling.value = false
    evtSource?.close()
  }
}

const rerun = async (fromStage?: number, hint?: string) => {
  await api.rerunTask(cveId, fromStage, hint)
  ElMessage.success(t('analysis.rerunStarted'))
  startStream()
}

const cancelTask = async () => {
  cancelling.value = true
  try {
    await api.cancelTask(cveId)
  } catch (e: any) {
    cancelling.value = false
    ElMessage.error(e?.response?.data?.message ?? t('analysis.cancelFailed'))
  }
}

const retryStage4WithHint = async () => {
  const hint = stage4Hint.value.trim()
  if (!hint) {
    ElMessage.warning(t('analysis.artifacts.hintRequired'))
    return
  }
  await rerun(4, hint)
  stage4Hint.value = ''
}

const onStage4FileChange = (file: any) => {
  stage4UploadFile.value = file.raw ?? null
}

const uploadStage4Demo = async () => {
  const file = stage4UploadFile.value
  if (!file) {
    ElMessage.warning(t('analysis.artifacts.uploadRequired'))
    return
  }
  stage4Uploading.value = true
  try {
    await api.uploadVulnDemo(cveId, file)
    ElMessage.success(t('analysis.artifacts.uploadStarted'))
    stage4UploadFile.value = null
    startStream()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? t('analysis.artifacts.uploadFailed'))
  } finally {
    stage4Uploading.value = false
  }
}

const toggleFile = async (path: string) => {
  const set = new Set(expandedFiles.value)
  if (set.has(path)) {
    set.delete(path)
    expandedFiles.value = set
    return
  }
  set.add(path)
  expandedFiles.value = set
  if (fileContents.value[path] != null) return
  fileLoading.value = new Set([...fileLoading.value, path])
  try {
    const { content } = await api.getArtifactFile(cveId, path)
    fileContents.value = { ...fileContents.value, [path]: content }
  } catch {
    fileContents.value = { ...fileContents.value, [path]: '(failed to load)' }
  } finally {
    const next = new Set(fileLoading.value)
    next.delete(path)
    fileLoading.value = next
  }
}

const downloadAllArtifacts = async () => {
  try {
    const blob = await api.downloadArtifacts(cveId)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${cveId}-artifacts.zip`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('Download failed')
  }
}

const downloadReport = () => {
  const blob = new Blob([reportMarkdown.value], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${cveId}-report.md`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(async () => {
  await load()
  if (!sseMessages.value.length) {
    try {
      const log = await api.getPipelineLog(cveId)
      sseMessages.value = log.map(e => formatSseEntry(e.type, e.stageNum, e.message))
    } catch {}
  }
  const status = task.value?.status
  if (status === 'RUNNING' || status === 'PENDING') startStream()
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

const mdRenderer = new Renderer()
mdRenderer.code = ({ text, lang }: { text: string; lang?: string }) => {
  const language = (lang || '').trim().split(/\s+/)[0]
  const highlighted = language && hljs.getLanguage(language)
    ? hljs.highlight(text, { language }).value
    : text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return `<pre><code class="hljs">${highlighted}</code></pre>`
}
const renderMarkdown = (md: string) => {
  return marked.parse(md, { async: false, gfm: true, renderer: mdRenderer }) as string
}

const extToLang: Record<string, string> = {
  java: 'java', xml: 'xml', html: 'html', json: 'json', sh: 'bash', bash: 'bash',
  yml: 'yaml', yaml: 'yaml', sql: 'sql', js: 'javascript', ts: 'typescript',
  py: 'python', properties: 'properties', diff: 'diff', md: 'xml',
}

const highlightFile = (path: string, code: string): string => {
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  const lang = extToLang[ext]
  if (lang && hljs.getLanguage(lang)) {
    return hljs.highlight(code, { language: lang }).value
  }
  return hljs.highlightAuto(code).value
}
</script>

<template>
  <div v-if="detail">
    <!-- Page header -->
    <div class="jv-detail-header">
      <div class="jv-detail-header-left">
        <span class="jv-back-btn" @click="router.push('/')">
          <ArrowLeft :size="13" :stroke-width="2.2" />
          {{ t('common.back') }}
        </span>
        <h2 class="jv-detail-id">{{ cveId }}</h2>
        <span :class="taskStatusClass(task!.status)">{{ t(`status.${task!.status}`) }}</span>
      </div>
      <div class="jv-detail-header-right">
        <el-button size="small" :disabled="!sseMessages.length" @click="terminalVisible = !terminalVisible">
          <ScrollText :size="13" :stroke-width="2" style="margin-right:6px" />
          {{ terminalVisible ? t('analysis.hideLog') : t('analysis.showLog') }}
        </el-button>
        <el-button v-if="sseActive" size="small" type="danger" :loading="cancelling" @click="cancelTask()">
          <Square :size="12" :stroke-width="2.4" style="margin-right:6px" />
          {{ t('analysis.cancelTask') }}
        </el-button>
        <el-button size="small" :loading="sseActive" @click="rerun()">
          <RotateCcw :size="13" :stroke-width="2" style="margin-right:6px" />
          {{ t('analysis.rerunAll') }}
        </el-button>
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
            <span class="jv-term-caret">
              <ChevronRight v-if="collapsedStages.has(group.stageNum)" :size="12" :stroke-width="2.4" />
              <ChevronDown v-else :size="12" :stroke-width="2.4" />
            </span>
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
            <span class="jv-result-index">{{ stageIcons[selectedStage - 1] }}</span>
            {{ stageNames[selectedStage - 1] }}
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
            <Play :size="12" :stroke-width="2.4" style="margin-right:6px" />
            {{ t('analysis.continueStage') }}
          </el-button>
          <el-button
            v-else-if="selectedStageRecord && selectedStageRecord.status === 'COMPLETED'"
            size="small" :loading="sseActive"
            @click="rerun(selectedStage)">
            <RotateCcw :size="13" :stroke-width="2" style="margin-right:6px" />
            {{ t('analysis.rerunStage') }}
          </el-button>
        </div>
      </div>
      <div v-if="selectedStageRecord?.errorMsg && !stage4Failed" class="jv-stage-error">
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
                <template v-if="stageData[1].cweId">
                  <span style="font-family:var(--font-mono)">{{ stageData[1].cweId }}</span>
                  <span v-if="cweName(stageData[1].cweId, locale)" style="color:var(--text-muted); margin-left:8px">
                    {{ cweName(stageData[1].cweId, locale) }}
                  </span>
                </template>
                <span v-else style="color:var(--text-disabled)">—</span>
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
                  <span class="jv-ref-icon"><ClipboardList :size="12" :stroke-width="2" /></span>
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
                  <span class="jv-ref-icon"><Search :size="12" :stroke-width="2" /></span>
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
                  <span class="jv-ref-icon"><Wrench :size="12" :stroke-width="2" /></span>
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
                  <span class="jv-ref-icon"><Bomb :size="12" :stroke-width="2" /></span>
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
                  <span class="jv-ref-icon"><FileText :size="12" :stroke-width="2" /></span>
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
                <div class="rs-impact-vector-icon"><Zap :size="16" :stroke-width="2" /></div>
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
                <div class="rs-fix-desc-icon"><Check :size="16" :stroke-width="2.4" /></div>
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

          <!-- Failure recovery: hint retry + manual upload -->
          <div v-if="stage4Failed" class="jv-stage4-recovery-wrap">
            <div style="color:var(--critical); margin-bottom:12px">
              {{ t('analysis.stage4Failed', { error: stages.find(s => s.stageNum === 4)?.errorMsg ?? '' }) }}
            </div>
            <div class="jv-stage4-recovery">
              <div class="jv-stage4-recovery-card">
                <div class="jv-stage4-recovery-title">{{ t('analysis.artifacts.hintRetryTitle') }}</div>
                <div class="jv-stage4-recovery-desc">{{ t('analysis.artifacts.hintRetryDesc') }}</div>
                <el-input
                  v-model="stage4Hint"
                  type="textarea"
                  :rows="3"
                  :placeholder="t('analysis.artifacts.hintPlaceholder')"
                  :disabled="sseActive || stage4Uploading" />
                <el-button
                  type="primary" size="small" style="margin-top:10px"
                  :loading="sseActive" :disabled="stage4Uploading"
                  @click="retryStage4WithHint">
                  {{ t('analysis.artifacts.hintRetryButton') }}
                </el-button>
              </div>

              <div class="jv-stage4-recovery-card">
                <div class="jv-stage4-recovery-title">{{ t('analysis.artifacts.uploadTitle') }}</div>
                <div class="jv-stage4-recovery-desc">{{ t('analysis.artifacts.uploadDesc') }}</div>
                <el-upload
                  :auto-upload="false"
                  :show-file-list="true"
                  :limit="1"
                  accept=".zip"
                  :on-change="onStage4FileChange"
                  :disabled="sseActive || stage4Uploading">
                  <el-button size="small" :disabled="sseActive || stage4Uploading">
                    {{ t('analysis.artifacts.uploadSelect') }}
                  </el-button>
                </el-upload>
                <el-button
                  type="primary" size="small" style="margin-top:10px"
                  :loading="stage4Uploading" :disabled="sseActive || !stage4UploadFile"
                  @click="uploadStage4Demo">
                  {{ t('analysis.artifacts.uploadButton') }}
                </el-button>
              </div>
            </div>
          </div>

          <div v-if="stageData[4] && (stageData[4].status === 'generated' || stageData[4].status === 'paused')" class="jv-stage4-sections">

            <!-- Paused banner -->
            <div v-if="stageData[4].status === 'paused' && !stage4Failed && !sseActive" class="jv-paused-banner">
              <div class="jv-paused-title">{{ stageData[4].pauseReason === '用户中止' ? t('analysis.artifacts.pausedByUser') : t('analysis.artifacts.pausedTitle') }}</div>
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
                <div v-if="stageData[4].validation.pocMessage && !stage4PocSteps.length" class="jv-stage4-plan-card">
                  <div class="jv-stage4-card-label">{{ t('analysis.artifacts.validationPocMessage') }}</div>
                  <pre class="jv-stage4-pre">{{ stageData[4].validation.pocMessage }}</pre>
                </div>
              </div>
              <div v-if="stage4PocSteps.length" class="jv-poc-timeline">
                <div class="jv-poc-timeline-head">
                  <span class="jv-poc-timeline-title">{{ t('analysis.artifacts.pocTimeline') }}</span>
                  <span class="jv-poc-timeline-legend">
                    <span class="jv-poc-legend-item is-client">{{ t('analysis.artifacts.pocClient') }}</span>
                    <span class="jv-poc-legend-item is-server">{{ t('analysis.artifacts.pocServer') }}</span>
                  </span>
                </div>
                <div class="jv-poc-track">
                  <div
                    v-for="(step, si) in stage4PocSteps"
                    :key="si"
                    class="jv-poc-step"
                    :class="pocStepSide(step) === 'server' ? 'is-server' : 'is-client'"
                  >
                    <div class="jv-poc-step-rail">
                      <span class="jv-poc-step-dot" :class="`phase-${step.phase}`"></span>
                    </div>
                    <div class="jv-poc-step-card">
                      <div class="jv-poc-step-meta">
                        <span class="jv-poc-step-phase" :class="`phase-${step.phase}`">{{ pocPhaseLabel(step.phase) }}</span>
                        <span v-if="step.label" class="jv-poc-step-label">{{ step.label }}</span>
                      </div>
                      <pre v-if="step.body" class="jv-stage4-pre jv-poc-step-body">{{ step.body }}</pre>
                    </div>
                  </div>
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

            <!-- File list -->
            <div class="jv-reasoning-section s4-c3">
              <div class="jv-section-label" style="display:flex; align-items:center; justify-content:space-between">
                <span>{{ t('analysis.artifacts.fileList') }}</span>
                <el-button size="small" @click="downloadAllArtifacts" style="margin-left:auto">
                  <Download :size="13" :stroke-width="2" style="margin-right:6px" />
                  {{ t('analysis.artifacts.downloadAll') }}
                </el-button>
              </div>
              <div class="jv-artifacts-files">
                <div v-for="f in stageData[4].files" :key="f.path" class="jv-artifact-file-row">
                  <div class="jv-artifact-file" @click="toggleFile(f.path)" style="cursor:pointer">
                    <span class="jv-artifact-expand">
                      <ChevronDown v-if="expandedFiles.has(f.path)" :size="13" :stroke-width="2.2" />
                      <ChevronRight v-else :size="13" :stroke-width="2.2" />
                    </span>
                    <span :class="'jv-artifact-type jv-artifact-type-' + f.type">{{ f.type }}</span>
                    <code>{{ f.path }}</code>
                    <span v-if="fileLoading.has(f.path)" class="jv-artifact-loading">LOADING</span>
                  </div>
                  <pre v-if="expandedFiles.has(f.path) && fileContents[f.path] != null" class="jv-artifact-content hljs"><code v-html="highlightFile(f.path, fileContents[f.path])"></code></pre>
                </div>
              </div>
            </div>

          </div>
          <div v-else-if="!stage4Failed" style="text-align:center; padding:40px">
            <div style="color:var(--text-disabled)">{{ t('analysis.artifactsUnavailable') }}</div>
          </div>
        </div>

        <div v-else-if="selectedStage === 5">
          <div v-if="reportMarkdown" class="jv-reasoning-section">
            <div class="jv-section-label" style="display:flex; align-items:center; justify-content:space-between">
              <span>{{ t('analysis.artifacts.reportPreview') }}</span>
              <el-button size="small" @click="downloadReport" style="margin-left:auto">
                <Download :size="13" :stroke-width="2" style="margin-right:6px" />
                {{ t('analysis.artifacts.downloadReport') }}
              </el-button>
            </div>
            <div class="jv-report-preview" v-html="renderMarkdown(reportMarkdown)"></div>
          </div>
          <div v-else style="text-align:center; padding:40px">
            <div v-if="stages.find(s => s.stageNum === 5 && s.status === 'FAILED')"
              style="color:var(--critical)">
              {{ stages.find(s => s.stageNum === 5)?.errorMsg ?? '' }}
              <br/>
              <el-button style="margin-top:12px" @click="rerun(5)">{{ t('analysis.rerunStage') }}</el-button>
            </div>
            <div v-else style="color:var(--text-disabled)">{{ t('analysis.artifacts.noReport') }}</div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
  <el-skeleton v-else :rows="8" animated style="padding:20px" />
</template>


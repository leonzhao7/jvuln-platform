<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, type TaskDetail } from '../api'
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
const sseMessages = ref<string[]>([])
let evtSource: EventSource | null = null

const task = computed(() => detail.value?.task)
const stages = computed(() => detail.value?.stages ?? [])
const stage3Files = computed<any[]>(() => stageData.value[3]?.analyzedFiles ?? [])
const hasStage3CallChains = computed(() =>
  stage3Files.value.some(file => Array.isArray(file.callChain) && file.callChain.length > 0)
)

const stageIcons = ['01', '02', '03', '04', '05']
const stageNames = computed(() => array<string>('analysis.stageNames'))

const selectedStageRecord = computed(() =>
  stages.value.find(s => s.stageNum === selectedStage.value)
)
const stage5Data = computed(() => stageData.value[5] ?? null)
const stage5PocFiles = computed<any[]>(() =>
  (stage5Data.value?.files ?? []).filter((item: any) => item.type === 'poc')
)
const stage5ValidationArtifacts = computed(() => {
  const artifacts = stage5Data.value?.validation?.artifacts
  if (!artifacts || typeof artifacts !== 'object') return []
  return Object.entries(artifacts).map(([key, value]) => ({ key, value }))
})
const stage5PlanSections = computed(() => {
  const plan = stage5Data.value?.executionPlan
  if (!plan) return []
  return [
    { key: 'firstBatchFiles', label: t('analysis.artifacts.planFirstBatchFiles'), items: plan.firstBatchFiles ?? [] },
    { key: 'minimalDeliverables', label: t('analysis.artifacts.planMinimalDeliverables'), items: plan.minimalDeliverables ?? [] },
    { key: 'validationSequence', label: t('analysis.artifacts.planValidationSequence'), items: plan.validationSequence ?? [] },
    { key: 'deferredUntilVerified', label: t('analysis.artifacts.planDeferredUntilVerified'), items: plan.deferredUntilVerified ?? [] },
    { key: 'risks', label: t('analysis.artifacts.planRisks'), items: plan.risks ?? [] },
  ].filter(section => Array.isArray(section.items) && section.items.length)
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

const load = async () => {
  try {
    detail.value = await api.getTask(cveId)
    await loadStageData()
  } catch {
    ElMessage.error(t('analysis.loadFailed'))
  }
}

const loadStageData = async () => {
  diffContent.value = ''
  diffLoading.value = false
  const stgs = detail.value?.stages ?? []
  for (const s of stgs) {
    if (s.status === 'COMPLETED' || s.status === 'FAILED') {
      try {
        if (s.stageNum === 1) stageData.value[1] = await api.getIntelligence(cveId)
        if (s.stageNum === 2) stageData.value[2] = await api.getPatch(cveId)
        if (s.stageNum === 3) stageData.value[3] = await api.getCodeAnalysis(cveId)
        if (s.stageNum === 4) stageData.value[4] = await api.getReasoning(cveId)
        if (s.stageNum === 5) {
          stageData.value[5] = await api.getArtifacts(cveId)
          try { const r = await api.getReport(cveId); reportMarkdown.value = r.markdown } catch {}
        }
      } catch { /* stage data may not exist yet */ }
    }
  }
  if (detail.value?.stages.find(s => s.stageNum === 2 && s.status === 'COMPLETED')) {
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
}

const startStream = () => {
  if (evtSource) evtSource.close()
  sseMessages.value = []
  sseActive.value = true
  evtSource = new EventSource(`/api/analysis/${cveId}/stream`)

  const handleEvent = (type: string) => (e: MessageEvent) => {
    sseMessages.value.push(`${e.data ?? ''}`)
    if (type === 'pipeline_done' || type === 'error') {
      sseActive.value = false
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
  evtSource.onerror = () => { sseActive.value = false }
}

const rerun = async (fromStage?: number) => {
  await api.rerunTask(cveId, fromStage)
  ElMessage.success(t('analysis.rerunStarted'))
  startStream()
}

onMounted(async () => {
  await load()
  if (task.value?.status === 'RUNNING') startStream()
})

onUnmounted(() => evtSource?.close())

const vstClass = (status?: string) => {
  if (!status) return 'jv-vstatus-unknown'
  if (status.includes('ok') || status === 'verified' || status === 'generated') return 'jv-vstatus-ok'
  if (status.includes('failed') || status === 'error') return 'jv-vstatus-fail'
  if (status === 'skipped' || status === 'unverified') return 'jv-vstatus-skip'
  return 'jv-vstatus-unknown'
}

const artifactCompileStatus = (artifacts?: any) => {
  const explicit = artifacts?.vulnDemo?.compileStatus
  if (explicit) return explicit
  const status = artifacts?.vulnDemo?.status
  if (status === 'startup_ok' || status === 'compile_ok' || status === 'startup_failed') return 'compile_ok'
  if (status === 'compile_failed') return 'compile_failed'
  if (status === 'not_started') return 'not_started'
  return undefined
}

const artifactStartupStatus = (artifacts?: any) => {
  const explicit = artifacts?.vulnDemo?.startupStatus
  if (explicit) return explicit
  const status = artifacts?.vulnDemo?.status
  if (status === 'startup_ok') return 'startup_ok'
  if (status === 'startup_failed') return 'startup_failed'
  if (status === 'compile_ok' || status === 'compile_failed') return 'skipped'
  if (status === 'not_started') return 'not_started'
  return undefined
}

const vstLabel = (status?: string) => {
  if (!status) return '-'
  const map: Record<string, string> = {
    compile_ok: t('analysis.artifacts.verified'),
    startup_ok: t('analysis.artifacts.verified'),
    compile_failed: t('analysis.artifacts.failed'),
    startup_failed: t('analysis.artifacts.failed'),
    verified: t('analysis.artifacts.verified'),
    unverified: t('analysis.artifacts.unverified'),
    skipped: t('analysis.artifacts.skipped'),
    not_started: t('analysis.artifacts.notStarted'),
    unknown: t('analysis.artifacts.notStarted'),
    generated: t('analysis.artifacts.verified'),
    error: t('analysis.artifacts.failed'),
  }
  return map[status] ?? status
}

const severityClass = (s: string) => {
  const v = (s || '').toUpperCase()
  if (v.includes('CRITICAL') || v.includes('严重')) return 'jv-tag jv-tag-critical'
  if (v.includes('HIGH') || v.includes('高')) return 'jv-tag jv-tag-high'
  if (v.includes('MEDIUM') || v.includes('中')) return 'jv-tag jv-tag-medium'
  return 'jv-tag jv-tag-low'
}

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
    <div v-if="sseMessages.length" class="jv-terminal" style="margin-bottom:20px">
      <div v-for="(msg, i) in sseMessages" :key="i">{{ msg }}</div>
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
            <el-descriptions :column="2" border size="small">
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

          </div>
          <el-empty v-else :description="t('analysis.intelligenceUnavailable')" />
        </div>

        <!-- Patch Locate -->
        <div v-else-if="selectedStage === 2">
          <div v-if="stageData[2]">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item :label="t('analysis.patch.commitHash')">
                <span style="font-family:var(--font-mono)">{{ stageData[2].commitHash || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item :label="t('analysis.patch.strategy')">
                <span style="font-family:var(--font-mono)">{{ stageData[2].strategy || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item :label="t('analysis.patch.commitMessage')" :span="2">
                {{ stageData[2].commitMessage || '—' }}
              </el-descriptions-item>
            </el-descriptions>

            <div v-if="stageData[2].diffs?.length" class="jv-patch-files">
              <div class="jv-section-label">
                {{ t('analysis.patch.changedFiles') }} ({{ stageData[2].diffs.length }})
              </div>
              <div v-for="file in stageData[2].diffs" :key="file.filePath" class="jv-patch-file">
                <div class="jv-patch-file-header">
                  <span>{{ file.filePath }}</span>
                  <span class="jv-patch-file-stats">
                    +{{ file.addedLines?.length ?? 0 }} / -{{ file.removedLines?.length ?? 0 }}
                  </span>
                </div>

                <div v-if="file.methodChanges?.length" class="jv-patch-methods">
                  <div class="jv-field-label">{{ t('analysis.patch.methodChanges') }}</div>
                  <div v-for="m in file.methodChanges" :key="`${file.filePath}-${m.methodName}-${m.changeType}`" class="jv-patch-method">
                    <span class="jv-patch-method-name">{{ m.methodName }}</span>
                    <span class="jv-patch-method-type">{{ m.changeType }}</span>
                  </div>
                </div>
              </div>
            </div>

          </div>
          <el-empty v-else :description="t('analysis.patchUnavailable')" />
        </div>

        <!-- Code Analysis -->
        <div v-else-if="selectedStage === 3">
          <div v-if="stage3Files.length || diffContent">
            <div v-if="diffContent || diffLoading" class="jv-stage3-section">
              <DiffViewer
                :diff-content="diffContent"
                :loading="diffLoading"
                :title="t('analysis.codeDiff')"
                :empty-text="t('diff.empty')"
              />
            </div>

            <div v-if="stage3Files.length" class="jv-stage3-section">
              <div class="jv-section-label" style="margin-bottom:12px">{{ t('analysis.cweAnalysis') }}</div>
              <div v-for="(file, fi) in stage3Files" :key="fi" class="jv-file-block">
                <div class="jv-file-header">
                  <span style="font-family:var(--font-mono); font-size:13px; color:var(--accent-light)">
                    {{ file.fileName }}
                  </span>
                </div>

                <div v-if="file.cweMatches?.length" style="margin-bottom:16px">
                  <div class="jv-section-label" style="margin-bottom:8px">
                    {{ t('analysis.cweMatches') }} ({{ file.cweMatches.length }})
                  </div>
                  <div v-for="c in file.cweMatches" :key="c.cweId + c.matchedCode" class="jv-cwe-block">
                    <div class="cwe-id">{{ c.cweId }}: {{ c.cweName }}</div>
                    <div class="cwe-code">{{ c.matchedCode }}</div>
                    <div class="cwe-expl">{{ c.explanation }}</div>
                  </div>
                </div>

                <div v-for="m in file.methods" :key="m.methodName" style="margin-bottom:16px">
                  <div style="color:var(--text-primary); font-weight:500; font-size:13px; margin-bottom:8px; font-family:var(--font-mono)">
                    {{ m.methodName }}()
                    <span style="color:var(--text-disabled); font-weight:400; font-size:11px; margin-left:8px">
                      → {{ m.calledMethods?.join(', ') }}
                    </span>
                  </div>
                  <div style="display:grid; grid-template-columns:1fr 1fr; gap:8px">
                    <div>
                      <div class="jv-code-label-vuln">{{ t('analysis.vulnerable') }}</div>
                      <pre class="jv-code-vuln">{{ m.vulnerableCode }}</pre>
                    </div>
                    <div>
                      <div class="jv-code-label-fixed">{{ t('analysis.fixed') }}</div>
                      <pre class="jv-code-fixed">{{ m.fixedCode }}</pre>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="hasStage3CallChains" class="jv-stage3-section">
              <div class="jv-section-label" style="margin-bottom:12px">{{ t('analysis.callChain') }}</div>
              <template v-for="(file, fi) in stage3Files" :key="`chain-${fi}`">
                <div v-if="file.callChain?.length" class="jv-file-block">
                  <div class="jv-file-header">
                    <span style="font-family:var(--font-mono); font-size:13px; color:var(--accent-light)">
                      {{ file.fileName }}
                    </span>
                  </div>
                  <div style="display:flex; gap:6px; flex-wrap:wrap; align-items:center">
                    <span v-for="(c, ci) in file.callChain" :key="c" class="jv-chain-item">
                      {{ c }}
                      <span v-if="Number(ci) < file.callChain.length - 1" style="color:var(--text-disabled); margin-left:6px">→</span>
                    </span>
                  </div>
                </div>
              </template>
            </div>

          </div>
          <el-empty v-else :description="t('analysis.codeAnalysisUnavailable')" />
        </div>

        <!-- AI Reasoning -->
        <div v-else-if="selectedStage === 4">
          <div v-if="stageData[4]" class="jv-reasoning">

            <!-- Trigger Chain -->
            <div v-if="stageData[4].trigger_chain" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.reasoning.triggerChain') }}</div>
              <div class="jv-reasoning-field">
                <span class="jv-field-label">{{ t('analysis.reasoning.triggerSummary') }}:</span>
                {{ stageData[4].trigger_chain.summary }}
              </div>
              <div class="jv-trigger-steps">
                <div v-for="step in stageData[4].trigger_chain.steps" :key="step.seq" class="jv-trigger-step">
                  <span class="jv-step-seq">{{ step.seq }}</span>
                  <span class="jv-step-class">{{ step.class }}.{{ step.method }}()</span>
                  <span class="jv-step-desc">{{ step.description }}</span>
                </div>
              </div>
              <div class="jv-reasoning-meta">
                <span><span class="jv-field-label">{{ t('analysis.reasoning.entryPoint') }}:</span> {{ stageData[4].trigger_chain.entry_point }}</span>
                <span><span class="jv-field-label">{{ t('analysis.reasoning.sink') }}:</span> {{ stageData[4].trigger_chain.sink }}</span>
              </div>
            </div>

            <!-- Code Analysis -->
            <div v-if="stageData[4].code_analysis" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.reasoning.rootCause') }}</div>
              <div class="jv-reasoning-text">{{ stageData[4].code_analysis.vuln_root_cause }}</div>

              <div v-if="stageData[4].code_analysis.vuln_code_walkthrough?.length" style="margin-top:12px">
                <div class="jv-field-label" style="margin-bottom:6px">{{ t('analysis.reasoning.codeWalkthrough') }}</div>
                <div v-for="(w, i) in stageData[4].code_analysis.vuln_code_walkthrough" :key="i" class="jv-walkthrough-item">
                  <code class="jv-walkthrough-line">{{ w.line }}</code>
                  <span class="jv-walkthrough-expl">{{ w.explanation }}</span>
                </div>
              </div>

              <div class="jv-reasoning-field" style="margin-top:12px">
                <span class="jv-field-label">{{ t('analysis.reasoning.fixDescription') }}:</span>
                {{ stageData[4].code_analysis.fix_description }}
              </div>
              <div class="jv-reasoning-field">
                <span class="jv-field-label">{{ t('analysis.reasoning.fixCompleteness') }}:</span>
                {{ stageData[4].code_analysis.fix_completeness }}
              </div>
            </div>

            <!-- Impact -->
            <div v-if="stageData[4].impact" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.reasoning.impact') }}</div>
              <div class="jv-reasoning-meta">
                <span><span class="jv-field-label">{{ t('analysis.reasoning.severity') }}:</span> <span :class="severityClass(stageData[4].impact.severity)">{{ stageData[4].impact.severity }}</span></span>
                <span><span class="jv-field-label">{{ t('analysis.reasoning.attackVector') }}:</span> {{ stageData[4].impact.attack_vector }}</span>
              </div>
              <div v-if="stageData[4].impact.prerequisites?.length" class="jv-reasoning-field">
                <span class="jv-field-label">{{ t('analysis.reasoning.prerequisites') }}:</span>
                <ul class="jv-inline-list"><li v-for="p in stageData[4].impact.prerequisites" :key="p">{{ p }}</li></ul>
              </div>
              <div v-if="stageData[4].impact.consequences?.length" class="jv-reasoning-field">
                <span class="jv-field-label">{{ t('analysis.reasoning.consequences') }}:</span>
                <ul class="jv-inline-list"><li v-for="c in stageData[4].impact.consequences" :key="c">{{ c }}</li></ul>
              </div>
              <div v-if="stageData[4].impact.real_world_scenarios?.length" class="jv-reasoning-field">
                <span class="jv-field-label">{{ t('analysis.reasoning.realWorldScenarios') }}:</span>
                <ul class="jv-inline-list"><li v-for="s in stageData[4].impact.real_world_scenarios" :key="s">{{ s }}</li></ul>
              </div>
            </div>

            <!-- Secure Coding -->
            <div v-if="stageData[4].secure_coding" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.reasoning.secureCoding') }}</div>
              <div v-if="stageData[4].secure_coding.violated_principles?.length" class="jv-reasoning-field">
                <span class="jv-field-label">{{ t('analysis.reasoning.violatedPrinciples') }}:</span>
                <ul class="jv-inline-list"><li v-for="v in stageData[4].secure_coding.violated_principles" :key="v">{{ v }}</li></ul>
              </div>
              <div v-if="stageData[4].secure_coding.recommendations?.length" class="jv-reasoning-field">
                <span class="jv-field-label">{{ t('analysis.reasoning.recommendations') }}:</span>
                <ul class="jv-inline-list"><li v-for="r in stageData[4].secure_coding.recommendations" :key="r">{{ r }}</li></ul>
              </div>
              <div v-if="stageData[4].secure_coding.similar_patterns?.length" class="jv-reasoning-field">
                <span class="jv-field-label">{{ t('analysis.reasoning.similarPatterns') }}:</span>
                <ul class="jv-inline-list"><li v-for="s in stageData[4].secure_coding.similar_patterns" :key="s">{{ s }}</li></ul>
              </div>
            </div>

            <!-- Detection Points -->
            <div v-if="stageData[4].detection_points?.length" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.reasoning.detectionPoints') }} ({{ stageData[4].detection_points.length }})</div>
              <div v-for="dp in stageData[4].detection_points" :key="dp.id" class="jv-dp-card">
                <div class="jv-dp-header">
                  <span class="jv-dp-id">{{ dp.id }}</span>
                  <span :class="'jv-dp-type jv-dp-type-' + dp.type">{{ t(`analysis.reasoning.dpTypes.${dp.type}`) }}</span>
                </div>
                <div class="jv-dp-desc">{{ dp.description }}</div>

                <!-- dependency -->
                <div v-if="dp.type === 'dependency'" class="jv-dp-details">
                  <span><span class="jv-field-label">{{ t('analysis.reasoning.dpArtifact') }}:</span> <code>{{ dp.artifact }}</code></span>
                  <span><span class="jv-field-label">{{ t('analysis.reasoning.dpAffectedRange') }}:</span> <code>{{ dp.affectedVersionRange }}</code></span>
                  <span><span class="jv-field-label">{{ t('analysis.reasoning.dpFixedVersion') }}:</span> <code>{{ dp.fixedVersion }}</code></span>
                </div>

                <!-- code_pattern -->
                <div v-if="dp.type === 'code_pattern'" class="jv-dp-details">
                  <span v-if="dp.cweId"><span class="jv-field-label">CWE:</span> <code>{{ dp.cweId }}</code></span>
                  <span v-if="dp.className"><span class="jv-field-label">{{ t('analysis.reasoning.dpClass') }}:</span> <code>{{ dp.className }}</code></span>
                  <span v-if="dp.methodName"><span class="jv-field-label">{{ t('analysis.reasoning.dpMethod') }}:</span> <code>{{ dp.methodName }}</code></span>
                  <div v-if="dp.pattern" style="margin-top:4px">
                    <span class="jv-field-label">{{ t('analysis.reasoning.dpPattern') }}:</span>
                    <code class="jv-dp-pattern">{{ dp.pattern }}</code>
                  </div>
                </div>

                <!-- config_risk -->
                <div v-if="dp.type === 'config_risk' && dp.configKeys?.length" class="jv-dp-details">
                  <div class="jv-field-label">{{ t('analysis.reasoning.dpConfigKeys') }}:</div>
                  <div v-for="ck in dp.configKeys" :key="ck.key" class="jv-dp-config-row">
                    <code>{{ ck.key }}</code> = <code class="jv-dp-risky">{{ ck.riskyValue }}</code>
                  </div>
                </div>

                <!-- api_usage -->
                <div v-if="dp.type === 'api_usage'" class="jv-dp-details">
                  <div v-if="dp.dangerousApis?.length">
                    <span class="jv-field-label">{{ t('analysis.reasoning.dpDangerousApis') }}:</span>
                    <code v-for="a in dp.dangerousApis" :key="a" class="jv-dp-api-tag">{{ a }}</code>
                  </div>
                  <div v-if="dp.safeAlternatives?.length" style="margin-top:4px">
                    <span class="jv-field-label">{{ t('analysis.reasoning.dpSafeAlternatives') }}:</span>
                    <span v-for="s in dp.safeAlternatives" :key="s" class="jv-dp-safe-tag">{{ s }}</span>
                  </div>
                </div>
              </div>
            </div>

          </div>
          <div v-else style="text-align:center; padding:40px">
            <div v-if="stages.find(s => s.stageNum === 4 && s.status === 'FAILED')"
              style="color:var(--critical)">
              {{ t('analysis.stage4Failed', { error: stages.find(s => s.stageNum === 4)?.errorMsg ?? '' }) }}
              <br/>
              <el-button style="margin-top:12px" @click="rerun(4)">{{ t('analysis.retryReasoning') }}</el-button>
            </div>
            <div v-else style="color:var(--text-disabled)">{{ t('analysis.reasoningUnavailable') }}</div>
          </div>
        </div>

        <!-- Artifacts / Education Lab -->
        <div v-else-if="selectedStage === 5">
          <div v-if="stageData[5] && (stageData[5].status === 'generated' || stageData[5].status === 'paused')">

            <!-- Paused banner -->
            <div v-if="stageData[5].status === 'paused'" class="jv-paused-banner">
              <div class="jv-paused-title">{{ t('analysis.artifacts.pausedTitle') }}</div>
              <div class="jv-paused-reason">{{ stageData[5].pauseReason }}</div>
              <div style="margin-top:8px; font-size:12px; color:var(--text-secondary)">
                {{ t('analysis.artifacts.pausedAt', { turn: stageData[5].pausedAtTurn }) }}
              </div>
              <el-button type="primary" size="small" style="margin-top:10px" @click="rerun(5)">
                {{ t('analysis.artifacts.continueAgent') }}
              </el-button>
            </div>

            <!-- Verification Status -->
            <div class="jv-artifacts-status">
              <span class="jv-vstatus" :class="vstClass(artifactCompileStatus(stageData[5]))">
                {{ t('analysis.artifacts.compileStatus') }}: {{ vstLabel(artifactCompileStatus(stageData[5])) }}
              </span>
              <span class="jv-vstatus" :class="vstClass(artifactStartupStatus(stageData[5]))">
                {{ t('analysis.artifacts.startupStatus') }}: {{ vstLabel(artifactStartupStatus(stageData[5])) }}
              </span>
              <span class="jv-vstatus" :class="vstClass(stageData[5].poc?.status)">
                {{ t('analysis.artifacts.pocStatus') }}: {{ vstLabel(stageData[5].poc?.status) }}
              </span>
            </div>

            <div class="jv-artifacts-summary">
              <span>{{ t('analysis.artifacts.fileCount', { count: stageData[5].fileCount ?? 0 }) }}</span>
              <span v-if="stageData[5].agentTurns != null">{{ t('analysis.artifacts.agentTurns') }}: {{ stageData[5].agentTurns }}</span>
              <span v-if="stageData[5].reviewRevisions != null">{{ t('analysis.artifacts.reviewRevisions') }}: {{ stageData[5].reviewRevisions }}</span>
            </div>

            <div v-if="stageData[5].executionPlan" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.artifacts.executionPlan') }}</div>
              <div class="jv-stage5-plan-grid">
                <div class="jv-stage5-plan-card">
                  <div class="jv-stage5-card-label">{{ t('analysis.artifacts.planGoal') }}</div>
                  <div class="jv-stage5-card-text">{{ stageData[5].executionPlan.goal }}</div>
                </div>
                <div class="jv-stage5-plan-card">
                  <div class="jv-stage5-card-label">{{ t('analysis.artifacts.planReportStrategy') }}</div>
                  <div class="jv-stage5-card-text">{{ stageData[5].executionPlan.reportStrategy }}</div>
                </div>
              </div>
              <div v-if="stage5PlanSections.length" class="jv-stage5-plan-lists">
                <div v-for="section in stage5PlanSections" :key="section.key" class="jv-stage5-plan-list">
                  <div class="jv-stage5-card-label">{{ section.label }}</div>
                  <ul class="jv-stage5-list">
                    <li v-for="item in section.items" :key="`${section.key}-${item}`">{{ item }}</li>
                  </ul>
                </div>
              </div>
            </div>

            <div v-if="stageData[5].validation" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.artifacts.backendValidation') }}</div>
              <div class="jv-stage5-validation-grid">
                <div class="jv-stage5-plan-card">
                  <div class="jv-stage5-card-label">{{ t('analysis.artifacts.validationFocus') }}</div>
                  <div class="jv-stage5-card-text">{{ stageData[5].validation.focus || 'full' }}</div>
                </div>
                <div class="jv-stage5-plan-card">
                  <div class="jv-stage5-card-label">{{ t('analysis.artifacts.validationCompile') }}</div>
                  <div class="jv-stage5-card-text">{{ stageData[5].validation.compileOk ? t('analysis.artifacts.verified') : t('analysis.artifacts.failed') }}</div>
                </div>
                <div class="jv-stage5-plan-card">
                  <div class="jv-stage5-card-label">{{ t('analysis.artifacts.validationStartup') }}</div>
                  <div class="jv-stage5-card-text">{{ stageData[5].validation.startupOk ? t('analysis.artifacts.verified') : t('analysis.artifacts.failed') }}</div>
                </div>
                <div class="jv-stage5-plan-card">
                  <div class="jv-stage5-card-label">{{ t('analysis.artifacts.validationPoc') }}</div>
                  <div class="jv-stage5-card-text">{{ stageData[5].validation.pocVerified ? t('analysis.artifacts.verified') : t('analysis.artifacts.failed') }}</div>
                </div>
              </div>
              <div class="jv-stage5-validation-messages">
                <div v-if="stageData[5].validation.compileMessage" class="jv-stage5-plan-card">
                  <div class="jv-stage5-card-label">{{ t('analysis.artifacts.validationCompileMessage') }}</div>
                  <pre class="jv-stage5-pre">{{ stageData[5].validation.compileMessage }}</pre>
                </div>
                <div v-if="stageData[5].validation.startupMessage" class="jv-stage5-plan-card">
                  <div class="jv-stage5-card-label">{{ t('analysis.artifacts.validationStartupMessage') }}</div>
                  <pre class="jv-stage5-pre">{{ stageData[5].validation.startupMessage }}</pre>
                </div>
                <div v-if="stageData[5].validation.pocMessage" class="jv-stage5-plan-card">
                  <div class="jv-stage5-card-label">{{ t('analysis.artifacts.validationPocMessage') }}</div>
                  <pre class="jv-stage5-pre">{{ stageData[5].validation.pocMessage }}</pre>
                </div>
              </div>
              <div v-if="stage5ValidationArtifacts.length" class="jv-stage5-validation-artifacts">
                <div class="jv-stage5-card-label">{{ t('analysis.artifacts.validationEvidence') }}</div>
                <div class="jv-stage5-evidence-grid">
                  <div v-for="item in stage5ValidationArtifacts" :key="item.key" class="jv-stage5-evidence-card">
                    <div class="jv-stage5-evidence-key">{{ item.key }}</div>
                    <ul v-if="hasEvidenceList(item.value)" class="jv-stage5-list">
                      <li v-for="entry in item.value as Array<string | number>" :key="String(entry)">{{ entry }}</li>
                    </ul>
                    <pre v-else class="jv-stage5-pre">{{ formatEvidenceValue(item.value) }}</pre>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="stageData[5].failureReason || stageData[5].verification?.reason || stageData[5].poc?.reason" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.artifacts.verificationReview') }}</div>
              <div class="jv-artifacts-review">
                <div v-if="stageData[5].verification?.verdict" class="jv-artifacts-review-verdict">
                  {{ t('analysis.artifacts.reviewVerdict') }}: {{ stageData[5].verification?.verdict }}
                </div>
                <div class="jv-artifacts-review-reason">
                  {{ stageData[5].verification?.reason || stageData[5].poc?.reason || stageData[5].failureReason }}
                </div>
                <div v-if="stageData[5].verification?.matchedSignals?.length" class="jv-artifacts-review-actions">
                  <div class="jv-artifacts-review-actions-title">{{ t('analysis.artifacts.matchedSignals') }}</div>
                  <ul>
                    <li v-for="signal in stageData[5].verification.matchedSignals" :key="signal">{{ signal }}</li>
                  </ul>
                </div>
                <div v-if="stageData[5].verification?.missingEvidence?.length" class="jv-artifacts-review-actions">
                  <div class="jv-artifacts-review-actions-title">{{ t('analysis.artifacts.missingEvidence') }}</div>
                  <ul>
                    <li v-for="item in stageData[5].verification.missingEvidence" :key="item">{{ item }}</li>
                  </ul>
                </div>
                <div v-if="stageData[5].verification?.falsePositiveRisks?.length" class="jv-artifacts-review-actions">
                  <div class="jv-artifacts-review-actions-title">{{ t('analysis.artifacts.falsePositiveRisks') }}</div>
                  <ul>
                    <li v-for="risk in stageData[5].verification.falsePositiveRisks" :key="risk">{{ risk }}</li>
                  </ul>
                </div>
                <div v-if="stageData[5].verification?.nextActions?.length" class="jv-artifacts-review-actions">
                  <div class="jv-artifacts-review-actions-title">{{ t('analysis.artifacts.nextActions') }}</div>
                  <ul>
                    <li v-for="action in stageData[5].verification.nextActions" :key="action">{{ action }}</li>
                  </ul>
                </div>
                <el-button v-if="stages.find(s => s.stageNum === 5 && s.status === 'FAILED')" style="margin-top:12px" @click="rerun(5)">
                  {{ t('analysis.retryArtifacts') }}
                </el-button>
              </div>
            </div>

            <!-- File list -->
            <div class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.artifacts.fileList') }}</div>
              <div class="jv-artifacts-files">
                <div v-for="f in stageData[5].files" :key="f.path" class="jv-artifact-file">
                  <span :class="'jv-artifact-type jv-artifact-type-' + f.type">{{ f.type }}</span>
                  <code>{{ f.path }}</code>
                </div>
              </div>
            </div>

            <!-- Report preview -->
            <div v-if="reportMarkdown" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.artifacts.reportPreview') }}</div>
              <div class="jv-report-preview" v-html="renderMarkdown(reportMarkdown)"></div>
            </div>
            <div v-else-if="stageData[5].report?.status === 'generated'" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.artifacts.reportPreview') }}</div>
              <div style="color:var(--text-disabled); font-size:13px">{{ t('analysis.artifacts.noReport') }}</div>
            </div>

            <!-- PoC files -->
            <div v-if="stageData[5].poc?.files?.length" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.artifacts.pocPreview') }}</div>
              <div v-for="f in stage5PocFiles" :key="f.path" class="jv-poc-block">
                <div class="jv-file-header">
                  <span style="font-family:var(--font-mono); font-size:13px; color:var(--accent-light)">{{ f.path }}</span>
                </div>
              </div>
            </div>

            <!-- Reproduction Steps -->
            <div v-if="stageData[5].reproductionSteps?.length" class="jv-reasoning-section">
              <div class="jv-section-label">{{ t('analysis.artifacts.reproductionSteps') }}</div>
              <div class="jv-reproduction-steps">
                <div v-for="s in stageData[5].reproductionSteps" :key="s.step" class="jv-repro-step">
                  <div class="jv-repro-step-header">
                    <span class="jv-repro-step-num">{{ s.step }}</span>
                    <span class="jv-repro-step-title">{{ s.title }}</span>
                  </div>
                  <code class="jv-repro-step-cmd">{{ s.command }}</code>
                  <div class="jv-repro-step-desc">{{ s.description }}</div>
                </div>
              </div>
            </div>

          </div>
          <div v-else style="text-align:center; padding:40px">
            <div v-if="stages.find(s => s.stageNum === 5 && s.status === 'FAILED')"
              style="color:var(--critical)">
              {{ t('analysis.stage5Failed', { error: stages.find(s => s.stageNum === 5)?.errorMsg ?? '' }) }}
              <br/>
              <el-button style="margin-top:12px" @click="rerun(5)">{{ t('analysis.retryArtifacts') }}</el-button>
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
  grid-template-columns: repeat(5, 1fr);
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
.jv-patch-file-stats {
  color: var(--text-disabled);
  white-space: nowrap;
}
.jv-patch-methods {
  padding: 0 12px 12px;
}
.jv-patch-method {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 6px 8px 0 0;
  font-family: var(--font-mono);
  font-size: 12px;
}
.jv-patch-method-name {
  color: var(--text-primary);
}
.jv-patch-method-type {
  color: var(--accent-light);
  background: rgba(15,98,254,.1);
  border: 1px solid rgba(15,98,254,.25);
  padding: 1px 6px;
}
/* Section label */
.jv-section-label {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
  letter-spacing: 1px;
  text-transform: uppercase;
}

.jv-stage3-section {
  margin-bottom: 28px;
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

/* Reasoning view */
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
.jv-reasoning-text {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.7;
}
.jv-reasoning-meta {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--text-secondary);
  margin-top: 8px;
}
.jv-inline-list {
  margin: 4px 0 0 18px;
  padding: 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.jv-inline-list li { margin-bottom: 2px; }

/* Trigger chain steps */
.jv-trigger-steps { margin: 10px 0; }
.jv-trigger-step {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  border-left: 2px solid var(--border-subtle);
  padding-left: 12px;
  margin-left: 8px;
}
.jv-step-seq {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent);
  background: rgba(15,98,254,.12);
  padding: 1px 6px;
  min-width: 18px;
  text-align: center;
}
.jv-step-class {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--accent-light);
  white-space: nowrap;
}
.jv-step-desc {
  color: var(--text-secondary);
}

/* Code walkthrough */
.jv-walkthrough-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 12px;
  margin-bottom: 6px;
  background: var(--bg-base);
  border-left: 2px solid var(--critical);
}
.jv-walkthrough-line {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-primary);
}
.jv-walkthrough-expl {
  font-size: 12px;
  color: var(--text-disabled);
}

/* Detection point cards */
.jv-dp-card {
  background: var(--bg-base);
  border: 1px solid var(--border-subtle);
  padding: 12px 16px;
  margin-bottom: 10px;
}
.jv-dp-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.jv-dp-id {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-disabled);
}
.jv-dp-type {
  font-family: var(--font-mono);
  font-size: 10px;
  padding: 2px 8px;
  text-transform: uppercase;
  letter-spacing: .5px;
}
.jv-dp-type-dependency   { background: rgba(15,98,254,.15); color: var(--accent-light); border: 1px solid rgba(15,98,254,.3); }
.jv-dp-type-code_pattern { background: rgba(250,77,86,.12);  color: #fa4d56; border: 1px solid rgba(250,77,86,.3); }
.jv-dp-type-config_risk  { background: rgba(241,194,27,.12); color: #f1c21b; border: 1px solid rgba(241,194,27,.3); }
.jv-dp-type-api_usage    { background: rgba(190,98,255,.12); color: #be62ff; border: 1px solid rgba(190,98,255,.3); }
.jv-dp-desc {
  color: var(--text-secondary);
  font-size: 13px;
  margin-bottom: 8px;
}
.jv-dp-details {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}
.jv-dp-details code {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent-light);
  background: rgba(15,98,254,.08);
  padding: 1px 5px;
}
.jv-dp-pattern {
  display: inline-block;
  background: var(--bg-code) !important;
  padding: 4px 8px !important;
  word-break: break-all;
}
.jv-dp-risky {
  color: #fa4d56 !important;
  background: rgba(250,77,86,.08) !important;
}
.jv-dp-config-row {
  font-family: var(--font-mono);
  font-size: 12px;
  padding: 2px 0;
}
.jv-dp-api-tag {
  display: inline-block;
  margin: 2px 4px 2px 0;
  padding: 2px 8px !important;
  background: rgba(250,77,86,.08) !important;
  color: #fa4d56 !important;
  border: 1px solid rgba(250,77,86,.2);
}
.jv-dp-safe-tag {
  display: inline-block;
  font-size: 12px;
  margin: 2px 4px 2px 0;
  padding: 2px 8px;
  background: rgba(66,190,101,.08);
  color: #42be65;
  border: 1px solid rgba(66,190,101,.2);
  font-family: var(--font-mono);
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
  border-left: 3px solid #42be65;
}
.jv-stage5-plan-grid,
.jv-stage5-validation-grid,
.jv-stage5-evidence-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}
.jv-stage5-plan-lists,
.jv-stage5-validation-messages,
.jv-stage5-validation-artifacts {
  margin-top: 14px;
}
.jv-stage5-plan-list + .jv-stage5-plan-list {
  margin-top: 12px;
}
.jv-stage5-plan-card,
.jv-stage5-evidence-card {
  background: var(--bg-base);
  border: 1px solid var(--border-subtle);
  padding: 12px 14px;
}
.jv-stage5-card-label,
.jv-stage5-evidence-key {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-disabled);
  text-transform: uppercase;
  letter-spacing: .5px;
  margin-bottom: 8px;
}
.jv-stage5-card-text {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.jv-stage5-list {
  margin: 0;
  padding-left: 18px;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.6;
}
.jv-stage5-pre {
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
}
.jv-artifacts-review-reason {
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.6;
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
.jv-artifacts-status {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.jv-vstatus {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 4px;
  font-family: var(--font-mono);
}
.jv-vstatus-ok   { background: rgba(66,190,101,.12); color: #42be65; border: 1px solid rgba(66,190,101,.3); }
.jv-vstatus-fail { background: rgba(250,77,86,.12);  color: #fa4d56; border: 1px solid rgba(250,77,86,.3); }
.jv-vstatus-skip { background: rgba(141,141,141,.12); color: #8d8d8d; border: 1px solid rgba(141,141,141,.3); }
.jv-vstatus-unknown { background: rgba(141,141,141,.08); color: #6f6f6f; border: 1px solid rgba(141,141,141,.2); }
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
</style>

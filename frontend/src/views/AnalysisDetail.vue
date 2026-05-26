<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, type TaskDetail } from '../api'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const cveId = route.params.cveId as string

const detail = ref<TaskDetail | null>(null)
const activeTab = ref('overview')
const stageData = ref<Record<number, any>>({})
const diffText = ref('')
const sseActive = ref(false)
const sseMessages = ref<string[]>([])
let evtSource: EventSource | null = null

const task = computed(() => detail.value?.task)
const stages = computed(() => detail.value?.stages ?? [])

const stageIcons = ['01', '02', '03', '04', '05']
const stageNames = ['Intelligence', 'Patch Locate', 'Code Analysis', 'AI Reasoning', 'Artifacts']

const stageClass = (status?: string) => {
  const map: Record<string, string> = {
    COMPLETED: 'jv-stage jv-stage-completed',
    RUNNING:   'jv-stage jv-stage-running',
    FAILED:    'jv-stage jv-stage-failed',
  }
  return map[status ?? ''] ?? 'jv-stage jv-stage-pending'
}

const taskStatusClass = (s: string) => ({
  COMPLETED: 'jv-tag jv-tag-completed',
  RUNNING:   'jv-tag jv-tag-running',
  FAILED:    'jv-tag jv-tag-failed',
  PENDING:   'jv-tag jv-tag-pending',
}[s] ?? 'jv-tag jv-tag-pending')

const load = async () => {
  try {
    detail.value = await api.getTask(cveId)
    await loadStageData()
  } catch {
    ElMessage.error('Failed to load task')
  }
}

const loadStageData = async () => {
  const stgs = detail.value?.stages ?? []
  for (const s of stgs) {
    if (s.status === 'COMPLETED') {
      try {
        if (s.stageNum === 1) stageData.value[1] = await api.getIntelligence(cveId)
        if (s.stageNum === 2) stageData.value[2] = await api.getPatch(cveId)
        if (s.stageNum === 3) stageData.value[3] = await api.getCodeAnalysis(cveId)
        if (s.stageNum === 4) stageData.value[4] = await api.getReasoning(cveId)
      } catch { /* stage data may not exist yet */ }
    }
  }
  if (detail.value?.stages.find(s => s.stageNum === 2 && s.status === 'COMPLETED')) {
    try { const d = await api.getDiff(cveId); diffText.value = d.diff } catch {}
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
  ElMessage.success('Rerun started')
  startStream()
}

onMounted(async () => {
  await load()
  if (task.value?.status === 'RUNNING') startStream()
})

onUnmounted(() => evtSource?.close())

const jsonStr = (obj: any) => JSON.stringify(obj, null, 2)

const cvssTag = (score: number) => {
  if (score >= 9) return 'jv-tag jv-tag-critical'
  if (score >= 7) return 'jv-tag jv-tag-high'
  if (score >= 4) return 'jv-tag jv-tag-medium'
  return 'jv-tag jv-tag-low'
}
</script>

<template>
  <div v-if="detail">
    <!-- Page header -->
    <div class="jv-detail-header">
      <div class="jv-detail-header-left">
        <span class="jv-back-btn" @click="router.push('/')">← Back</span>
        <h2 style="margin:0; font-family:var(--font-mono); font-size:18px">{{ cveId }}</h2>
        <span :class="taskStatusClass(task!.status)">{{ task!.status }}</span>
      </div>
      <div class="jv-detail-header-right">
        <el-button size="small" :loading="sseActive" @click="rerun()">↺ Rerun All</el-button>
        <el-button size="small" v-if="diffText" @click="router.push(`/analysis/${cveId}/diff`)">
          View Diff
        </el-button>
      </div>
    </div>

    <!-- Stage Pipeline -->
    <div class="jv-pipeline-row">
      <div v-for="(name, i) in stageNames" :key="i"
        :class="stageClass(stages[i]?.status)"
        @click="rerun(i + 1)"
        :title="`Rerun from Stage ${i+1}`">
        <div class="jv-stage-num">{{ stageIcons[i] }}</div>
        <div class="jv-stage-name">{{ name }}</div>
        <div class="jv-stage-status">{{ stages[i]?.status ?? 'PENDING' }}</div>
      </div>
    </div>

    <!-- SSE Terminal -->
    <div v-if="sseMessages.length" class="jv-terminal" style="margin-bottom:20px">
      <div v-for="(msg, i) in sseMessages" :key="i">{{ msg }}</div>
    </div>

    <!-- Stage Data Tabs -->
    <el-card>
      <el-tabs v-model="activeTab" type="border-card">

        <!-- Overview -->
        <el-tab-pane label="Overview" name="overview">
          <div v-if="stageData[1]">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="CVE ID">
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
              <el-descriptions-item label="Fixed Version">
                <span style="font-family:var(--font-mono)">{{ stageData[1].fixedVersion || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="Artifact" :span="2">
                <span style="font-family:var(--font-mono); font-size:13px">
                  {{ stageData[1].artifact?.groupId }}:{{ stageData[1].artifact?.artifactId }}
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="Source Repo" :span="2">
                <a :href="stageData[1].sourceRepo" target="_blank">{{ stageData[1].sourceRepo }}</a>
              </el-descriptions-item>
              <el-descriptions-item label="Description" :span="2">
                {{ stageData[1].description }}
              </el-descriptions-item>
            </el-descriptions>

            <div v-if="stageData[1].fixCommits?.length" style="margin-top:20px">
              <div class="jv-section-label">Fix Commits</div>
              <div v-for="c in stageData[1].fixCommits" :key="c" style="margin-top:4px">
                <a :href="c" target="_blank" style="font-family:var(--font-mono); font-size:12px">{{ c }}</a>
              </div>
            </div>
          </div>
          <el-empty v-else description="Intelligence data not available yet" />
        </el-tab-pane>

        <!-- Code Analysis -->
        <el-tab-pane label="Code Analysis" name="analysis">
          <div v-if="stageData[3]?.analyzedFiles?.length">
            <div v-for="(file, fi) in stageData[3].analyzedFiles" :key="fi" class="jv-file-block">
              <div class="jv-file-header">
                <span style="font-family:var(--font-mono); font-size:13px; color:var(--accent-light)">
                  {{ file.fileName }}
                </span>
              </div>

              <!-- CWE Matches -->
              <div v-if="file.cweMatches?.length" style="margin-bottom:16px">
                <div class="jv-section-label" style="margin-bottom:8px">
                  CWE MATCHES ({{ file.cweMatches.length }})
                </div>
                <div v-for="c in file.cweMatches" :key="c.cweId + c.matchedCode" class="jv-cwe-block">
                  <div class="cwe-id">{{ c.cweId }}: {{ c.cweName }}</div>
                  <div class="cwe-code">{{ c.matchedCode }}</div>
                  <div class="cwe-expl">{{ c.explanation }}</div>
                </div>
              </div>

              <!-- Method Analysis -->
              <div v-for="m in file.methods" :key="m.methodName" style="margin-bottom:16px">
                <div style="color:var(--text-primary); font-weight:500; font-size:13px; margin-bottom:8px; font-family:var(--font-mono)">
                  {{ m.methodName }}()
                  <span style="color:var(--text-disabled); font-weight:400; font-size:11px; margin-left:8px">
                    → {{ m.calledMethods?.join(', ') }}
                  </span>
                </div>
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:8px">
                  <div>
                    <div class="jv-code-label-vuln">VULNERABLE</div>
                    <pre class="jv-code-vuln">{{ m.vulnerableCode }}</pre>
                  </div>
                  <div>
                    <div class="jv-code-label-fixed">FIXED</div>
                    <pre class="jv-code-fixed">{{ m.fixedCode }}</pre>
                  </div>
                </div>
              </div>

              <!-- Call Chain -->
              <div v-if="file.callChain?.length">
                <div class="jv-section-label" style="margin-bottom:6px">CALL CHAIN</div>
                <div style="display:flex; gap:6px; flex-wrap:wrap; align-items:center">
                  <span v-for="(c, ci) in file.callChain" :key="c" class="jv-chain-item">
                    {{ c }}
                    <span v-if="ci < file.callChain.length - 1" style="color:var(--text-disabled); margin-left:6px">→</span>
                  </span>
                </div>
              </div>
            </div>
          </div>
          <el-empty v-else description="Code analysis not available yet" />
        </el-tab-pane>

        <!-- AI Reasoning -->
        <el-tab-pane label="AI Reasoning" name="reasoning">
          <div v-if="stageData[4]">
            <pre class="jv-json-view">{{ jsonStr(stageData[4]) }}</pre>
          </div>
          <div v-else style="text-align:center; padding:40px">
            <div v-if="stages.find(s => s.stageNum === 4 && s.status === 'FAILED')"
              style="color:var(--critical)">
              Stage 4 failed: {{ stages.find(s => s.stageNum === 4)?.errorMsg }}
              <br/>
              <el-button style="margin-top:12px" @click="rerun(4)">Retry AI Reasoning</el-button>
            </div>
            <div v-else style="color:var(--text-disabled)">AI reasoning not available yet</div>
          </div>
        </el-tab-pane>

        <!-- Raw Intelligence -->
        <el-tab-pane label="Intelligence" name="intel">
          <pre v-if="stageData[1]" class="jv-json-view">{{ jsonStr(stageData[1]) }}</pre>
          <el-empty v-else description="Intelligence not available" />
        </el-tab-pane>

        <!-- Stage Logs -->
        <el-tab-pane label="Stage Logs" name="logs">
          <el-timeline>
            <el-timeline-item
              v-for="s in stages" :key="s.stageNum"
              :type="s.status === 'COMPLETED' ? 'success' : s.status === 'FAILED' ? 'danger' : 'primary'"
              :timestamp="s.finishedAt?.replace('T', ' ').slice(0, 19) ?? ''">
              <div style="font-weight:500">Stage {{ s.stageNum }}: {{ s.stageName }}</div>
              <div style="color:var(--text-disabled); font-size:12px; font-family:var(--font-mono)">
                Started: {{ s.startedAt?.replace('T', ' ').slice(0, 19) ?? '—' }}
              </div>
              <div v-if="s.errorMsg" style="color:var(--critical); font-size:12px; margin-top:4px">
                {{ s.errorMsg }}
              </div>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>
      </el-tabs>
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

/* Section label */
.jv-section-label {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
  letter-spacing: 1px;
  text-transform: uppercase;
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

/* JSON view */
.jv-json-view {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg-code);
  padding: 16px;
  overflow-x: auto;
  white-space: pre-wrap;
  max-height: 600px;
  overflow-y: auto;
  border-left: 3px solid var(--border);
  margin: 0;
}
</style>

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

const statusColor: Record<string, string> = {
  PENDING: '#64748b', RUNNING: '#f59e0b', COMPLETED: '#22c55e', FAILED: '#ef4444', SKIPPED: '#94a3b8'
}

const stageIcons = ['🔍', '🔧', '📊', '🤖', '📦']
const stageNames = ['Intelligence', 'Patch Locate', 'Code Analysis', 'AI Reasoning', 'Artifacts']

const load = async () => {
  try {
    detail.value = await api.getTask(cveId)
    await loadStageData()
  } catch (e) {
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
    sseMessages.value.push(`[${type}] ${e.data ?? ''}`)
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
</script>

<template>
  <div v-if="detail">
    <!-- Header -->
    <div style="display:flex; align-items:center; gap:12px; margin-bottom:24px">
      <el-button text @click="router.push('/')">← Back</el-button>
      <h2 style="color:#e2e8f0">{{ cveId }}</h2>
      <el-tag :style="{ background: statusColor[task!.status], border:'none', color:'#fff' }">
        {{ task!.status }}
      </el-tag>
      <div style="flex:1"/>
      <el-button @click="rerun()" :loading="sseActive">Rerun All</el-button>
      <el-button @click="router.push(`/analysis/${cveId}/diff`)" v-if="diffText">View Diff</el-button>
    </div>

    <!-- Pipeline Progress -->
    <el-card style="background:#1e1e3a; border:1px solid #2a2a4a; margin-bottom:20px">
      <div style="display:flex; gap:0; overflow:hidden; border-radius:6px">
        <div v-for="(name, i) in stageNames" :key="i"
          style="flex:1; padding:12px 8px; text-align:center; position:relative; cursor:pointer"
          :style="{
            background: stages[i]?.status === 'COMPLETED' ? '#166534' :
                        stages[i]?.status === 'RUNNING' ? '#78350f' :
                        stages[i]?.status === 'FAILED' ? '#7f1d1d' : '#1e293b'
          }"
          @click="rerun(i + 1)">
          <div style="font-size:20px">{{ stageIcons[i] }}</div>
          <div style="color:#e2e8f0; font-size:12px; margin-top:4px">{{ name }}</div>
          <div style="font-size:10px; margin-top:2px"
            :style="{ color: statusColor[stages[i]?.status ?? 'PENDING'] }">
            {{ stages[i]?.status ?? 'PENDING' }}
          </div>
        </div>
      </div>
    </el-card>

    <!-- SSE Log -->
    <el-card v-if="sseMessages.length" style="background:#0a0a1a; border:1px solid #1e3a2a; margin-bottom:20px">
      <div style="font-family:monospace; font-size:12px; color:#4ade80; max-height:200px; overflow-y:auto">
        <div v-for="(msg, i) in sseMessages" :key="i">{{ msg }}</div>
      </div>
    </el-card>

    <!-- Stage Data Tabs -->
    <el-card style="background:#1e1e3a; border:1px solid #2a2a4a">
      <el-tabs v-model="activeTab" type="border-card"
        style="--el-tabs-header-background-color:#0f0f23; --el-color-primary:#60a5fa">
        <!-- Overview -->
        <el-tab-pane label="Overview" name="overview">
          <div v-if="stageData[1]" style="color:#e2e8f0">
            <el-descriptions :column="2" border size="small"
              :label-style="{ background:'#0f0f23', color:'#94a3b8' }"
              :content-style="{ background:'#1e1e3a', color:'#e2e8f0' }">
              <el-descriptions-item label="CVE ID">{{ stageData[1].cveId }}</el-descriptions-item>
              <el-descriptions-item label="CVSS">
                <el-tag type="danger" v-if="stageData[1].cvss?.score >= 9">{{ stageData[1].cvss?.score }} CRITICAL</el-tag>
                <el-tag type="warning" v-else-if="stageData[1].cvss?.score >= 7">{{ stageData[1].cvss?.score }} HIGH</el-tag>
                <span v-else>{{ stageData[1].cvss?.score ?? '-' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="CWE">{{ stageData[1].cweId ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="Fixed Version">{{ stageData[1].fixedVersion || '-' }}</el-descriptions-item>
              <el-descriptions-item label="Artifact" :span="2">
                {{ stageData[1].artifact?.groupId }}:{{ stageData[1].artifact?.artifactId }}
              </el-descriptions-item>
              <el-descriptions-item label="Source Repo" :span="2">
                <a :href="stageData[1].sourceRepo" target="_blank" style="color:#60a5fa">
                  {{ stageData[1].sourceRepo }}
                </a>
              </el-descriptions-item>
              <el-descriptions-item label="Description" :span="2">
                {{ stageData[1].description }}
              </el-descriptions-item>
            </el-descriptions>

            <div v-if="stageData[1].fixCommits?.length" style="margin-top:16px">
              <div style="color:#94a3b8; font-size:13px; margin-bottom:8px">Fix Commits</div>
              <div v-for="c in stageData[1].fixCommits" :key="c">
                <a :href="c" target="_blank" style="color:#60a5fa; font-size:13px">{{ c }}</a>
              </div>
            </div>
          </div>
          <el-empty v-else description="Intelligence data not available yet" />
        </el-tab-pane>

        <!-- Code Analysis -->
        <el-tab-pane label="Code Analysis" name="analysis">
          <div v-if="stageData[3]?.analyzedFiles?.length">
            <div v-for="(file, fi) in stageData[3].analyzedFiles" :key="fi" style="margin-bottom:24px">
              <h4 style="color:#60a5fa; font-size:13px; margin-bottom:12px">{{ file.fileName }}</h4>

              <!-- CWE Matches -->
              <div v-if="file.cweMatches?.length" style="margin-bottom:16px">
                <div style="color:#94a3b8; font-size:12px; margin-bottom:8px">
                  CWE Matches ({{ file.cweMatches.length }})
                </div>
                <el-tag v-for="c in file.cweMatches" :key="c.cweId + c.matchedCode"
                  type="danger" style="margin:2px 4px 2px 0">
                  {{ c.cweId }}: {{ c.cweName }}
                </el-tag>
                <div v-for="c in file.cweMatches" :key="c.cweId + c.explanation"
                  style="background:#1a0000; border:1px solid #7f1d1d; border-radius:6px; padding:10px; margin-top:8px">
                  <div style="color:#fca5a5; font-size:12px; font-weight:600">{{ c.cweId }}: {{ c.cweName }}</div>
                  <div style="color:#ef4444; font-family:monospace; font-size:12px; margin:4px 0">
                    {{ c.matchedCode }}
                  </div>
                  <div style="color:#94a3b8; font-size:12px">{{ c.explanation }}</div>
                </div>
              </div>

              <!-- Method Analysis -->
              <div v-for="m in file.methods" :key="m.methodName" style="margin-bottom:16px">
                <div style="color:#e2e8f0; font-weight:600; font-size:13px; margin-bottom:8px">
                  {{ m.methodName }}()
                  <span style="color:#94a3b8; font-weight:400; font-size:11px; margin-left:8px">
                    calls: {{ m.calledMethods?.join(', ') }}
                  </span>
                </div>
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:8px">
                  <div>
                    <div style="color:#ef4444; font-size:11px; margin-bottom:4px">VULNERABLE</div>
                    <pre style="background:#1a0000; padding:10px; border-radius:4px; font-size:11px; color:#fca5a5; overflow-x:auto; white-space:pre-wrap">{{ m.vulnerableCode }}</pre>
                  </div>
                  <div>
                    <div style="color:#22c55e; font-size:11px; margin-bottom:4px">FIXED</div>
                    <pre style="background:#001a00; padding:10px; border-radius:4px; font-size:11px; color:#86efac; overflow-x:auto; white-space:pre-wrap">{{ m.fixedCode }}</pre>
                  </div>
                </div>
              </div>

              <!-- Call Chain -->
              <div v-if="file.callChain?.length">
                <div style="color:#94a3b8; font-size:12px; margin-bottom:6px">Call Chain</div>
                <div style="display:flex; gap:8px; flex-wrap:wrap">
                  <span v-for="c in file.callChain" :key="c"
                    style="background:#1e293b; color:#60a5fa; padding:4px 10px; border-radius:16px; font-size:12px">
                    {{ c }}
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
            <pre style="color:#e2e8f0; font-size:12px; overflow-x:auto; white-space:pre-wrap; max-height:600px; overflow-y:auto">{{ jsonStr(stageData[4]) }}</pre>
          </div>
          <div v-else style="color:#64748b; text-align:center; padding:40px">
            <div v-if="stages.find(s => s.stageNum === 4 && s.status === 'FAILED')" style="color:#ef4444">
              Stage 4 failed: {{ stages.find(s => s.stageNum === 4)?.errorMsg }}
              <br/>
              <el-button style="margin-top:12px" @click="rerun(4)">Retry AI Reasoning</el-button>
            </div>
            <div v-else>AI reasoning not available yet</div>
          </div>
        </el-tab-pane>

        <!-- Raw Intelligence -->
        <el-tab-pane label="Intelligence" name="intel">
          <pre v-if="stageData[1]" style="color:#e2e8f0; font-size:12px; overflow-x:auto; white-space:pre-wrap; max-height:500px; overflow-y:auto">{{ jsonStr(stageData[1]) }}</pre>
          <el-empty v-else description="Intelligence not available" />
        </el-tab-pane>

        <!-- Stage Logs -->
        <el-tab-pane label="Stage Logs" name="logs">
          <el-timeline>
            <el-timeline-item
              v-for="s in stages" :key="s.stageNum"
              :type="s.status === 'COMPLETED' ? 'success' : s.status === 'FAILED' ? 'danger' : 'primary'"
              :timestamp="s.finishedAt?.replace('T', ' ').slice(0, 19) ?? ''">
              <div style="color:#e2e8f0">Stage {{ s.stageNum }}: {{ s.stageName }}</div>
              <div style="color:#64748b; font-size:12px">
                Started: {{ s.startedAt?.replace('T', ' ').slice(0, 19) ?? '-' }}
              </div>
              <div v-if="s.errorMsg" style="color:#ef4444; font-size:12px">{{ s.errorMsg }}</div>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
  <el-skeleton v-else :rows="8" animated style="padding:20px" />
</template>

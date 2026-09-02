<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { api, type CveTask } from '../api'
import { useI18n } from '../i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Layers, Flame, Loader, CircleCheck, Trash2, ArrowUpRight,
  Hexagon, PieChart, Gauge, BarChart3, Activity,
} from 'lucide-vue-next'
import SeverityDonut from '../components/charts/SeverityDonut.vue'
import RadialMeter from '../components/charts/RadialMeter.vue'
import BarList from '../components/charts/BarList.vue'
import TrendBars from '../components/charts/TrendBars.vue'

const router = useRouter()
const { t } = useI18n()
const tasks = ref<CveTask[]>([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try { tasks.value = await api.listTasks() }
  finally { loading.value = false }
}

onMounted(load)

const deleteTask = async (cveId: string) => {
  try {
    await ElMessageBox.confirm(
      t('dashboard.deleteConfirm', { name: cveId }),
      t('dashboard.confirmDelete'),
      { confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel'), type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.deleteTask(cveId)
    ElMessage.success(t('dashboard.deleteSuccess'))
    await load()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error ?? t('dashboard.deleteFailed'))
  }
}

const cvssClass = (score: number) => {
  if (score >= 9)   return 'jv-tag jv-tag-critical'
  if (score >= 7)   return 'jv-tag jv-tag-high'
  if (score >= 4)   return 'jv-tag jv-tag-medium'
  return 'jv-tag jv-tag-low'
}

const statusClass = (s: string) => ({
  COMPLETED: 'jv-tag jv-tag-completed',
  RUNNING:   'jv-tag jv-tag-running',
  FAILED:    'jv-tag jv-tag-failed',
  PENDING:   'jv-tag jv-tag-pending',
}[s] ?? 'jv-tag jv-tag-pending')

const stats = computed(() => ({
  total:    tasks.value.length,
  critical: tasks.value.filter(t => (t.cvssScore ?? 0) >= 9).length,
  running:  tasks.value.filter(t => t.status === 'RUNNING').length,
  done:     tasks.value.filter(t => t.status === 'COMPLETED').length,
}))

const SEV = { critical: '#ff4d6d', high: '#ff9e2c', medium: '#ffd84d', low: '#34e0a1', none: '#5d6678' }

const cvssColor = (score?: number | null) => {
  if (!score) return SEV.none
  if (score >= 9) return SEV.critical
  if (score >= 7) return SEV.high
  if (score >= 4) return SEV.medium
  return SEV.low
}

const severitySlices = computed(() => {
  const buckets = { critical: 0, high: 0, medium: 0, low: 0, none: 0 }
  for (const task of tasks.value) {
    const s = task.cvssScore ?? 0
    if (!s) buckets.none++
    else if (s >= 9) buckets.critical++
    else if (s >= 7) buckets.high++
    else if (s >= 4) buckets.medium++
    else buckets.low++
  }
  return [
    { label: 'CRITICAL', value: buckets.critical, color: SEV.critical },
    { label: 'HIGH',     value: buckets.high,     color: SEV.high },
    { label: 'MEDIUM',   value: buckets.medium,   color: SEV.medium },
    { label: 'LOW',      value: buckets.low,      color: SEV.low },
    { label: 'N/A',      value: buckets.none,     color: SEV.none },
  ].filter(s => s.value > 0)
})

const completionPct = computed(() =>
  stats.value.total ? (stats.value.done / stats.value.total) * 100 : 0)

const artifactRows = computed(() => {
  const counts = new Map<string, number>()
  for (const task of tasks.value) {
    const full = task.artifact ?? '—'
    const short = full.includes(':') ? full.split(':').slice(-1)[0] : full
    counts.set(short, (counts.get(short) ?? 0) + 1)
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
    .map(([label, value]) => ({ label, value }))
})

const scoreSpark = computed(() => {
  const scores = tasks.value.map(t => t.cvssScore ?? 0).slice(0, 12)
  const peak = Math.max(1, ...scores)
  return scores.map(s => Math.max(8, (s / peak) * 100))
})

const scoreBands = computed(() => {
  const bands = [
    { label: '0-2', lo: 0,   hi: 2 },
    { label: '2-4', lo: 2,   hi: 4 },
    { label: '4-6', lo: 4,   hi: 6 },
    { label: '6-7', lo: 6,   hi: 7 },
    { label: '7-8', lo: 7,   hi: 8 },
    { label: '8-9', lo: 8,   hi: 9 },
    { label: '9-10', lo: 9,  hi: 10.01 },
  ]
  return bands.map(b => ({
    label: b.label,
    value: tasks.value.filter(t => {
      const s = t.cvssScore ?? 0
      return s > 0 && s >= b.lo && s < b.hi
    }).length,
  }))
})

const formatDuration = (start: string | null, end: string | null, status?: string) => {
  if (!start) return '—'
  const endMs = status === 'RUNNING' ? Date.now() : (end ? new Date(end).getTime() : NaN)
  if (Number.isNaN(endMs)) return '—'
  const ms = endMs - new Date(start).getTime()
  if (ms < 0) return '—'
  const sec = Math.floor(ms / 1000)
  if (sec < 60) return `${sec}s`
  const min = Math.floor(sec / 60)
  const remSec = sec % 60
  if (min < 60) return `${min}m ${remSec}s`
  const hr = Math.floor(min / 60)
  const remMin = min % 60
  return `${hr}h ${remMin}m`
}
</script>

<template>
  <div>
    <!-- Hero -->
    <section class="jv-hero">
      <div class="jv-hero-inner">
        <div>
          <div class="jv-eyebrow">JAVA VULNERABILITY INTELLIGENCE</div>
          <h1 class="jv-hero-title">{{ t('dashboard.title') }}<em>.</em></h1>
          <p class="jv-hero-sub">
            {{ t('dashboard.summary', { total: stats.total, done: stats.done, running: stats.running }) }}
          </p>
        </div>
        <div class="jv-hero-side">
          <RadialMeter
            :value="completionPct"
            :label="t('dashboard.completed')"
            color="#22d3ee"
            :size="122"
          />
        </div>
      </div>
    </section>

    <!-- KPI tiles -->
    <div class="jv-stats-row">
      <div class="jv-stat-card">
        <div class="jv-stat-head">
          <Layers :size="14" :stroke-width="2" />
          <span class="jv-stat-label">{{ t('dashboard.total') }}</span>
        </div>
        <div class="jv-stat-value">{{ stats.total }}</div>
        <div class="jv-spark">
          <i v-for="(h, i) in scoreSpark" :key="i" :style="{ height: h + '%' }" />
        </div>
      </div>
      <div class="jv-stat-card jv-stat-critical">
        <div class="jv-stat-head">
          <Flame :size="14" :stroke-width="2" />
          <span class="jv-stat-label">{{ t('dashboard.critical') }}</span>
        </div>
        <div class="jv-stat-value">{{ stats.critical }}</div>
        <div class="jv-stat-foot">CVSS &gt;= 9.0</div>
      </div>
      <div class="jv-stat-card jv-stat-running">
        <div class="jv-stat-head">
          <Loader :size="14" :stroke-width="2" />
          <span class="jv-stat-label">{{ t('dashboard.running') }}</span>
        </div>
        <div class="jv-stat-value">{{ stats.running }}</div>
        <div class="jv-stat-foot">PIPELINE ACTIVE</div>
      </div>
      <div class="jv-stat-card jv-stat-done">
        <div class="jv-stat-head">
          <CircleCheck :size="14" :stroke-width="2" />
          <span class="jv-stat-label">{{ t('dashboard.completed') }}</span>
        </div>
        <div class="jv-stat-value">{{ stats.done }}</div>
        <div class="jv-stat-foot">{{ Math.round(completionPct) }}% OF TOTAL</div>
      </div>
    </div>

    <!-- Analytics -->
    <div class="jv-analytics">
      <div class="jv-panel jv-chart-panel">
        <div class="jv-panel-head">
          <span class="jv-panel-head-icon"><PieChart :size="14" :stroke-width="2" /></span>
          <span class="jv-panel-head-title">SEVERITY MIX</span>
        </div>
        <div class="jv-chart-body">
          <template v-if="severitySlices.length">
            <SeverityDonut
              :slices="severitySlices"
              :size="152"
              :thickness="12"
              :center-value="stats.total"
              center-label="TASKS"
            />
            <div class="jv-chart-legend">
              <div v-for="s in severitySlices" :key="s.label" class="jv-chart-legend-row">
                <span class="swatch" :style="{ background: s.color, color: s.color }" />
                <span>{{ s.label }}</span>
                <span class="n">{{ s.value }}</span>
              </div>
            </div>
          </template>
          <div v-else class="jv-chart-empty">NO DATA</div>
        </div>
      </div>

      <div class="jv-panel jv-chart-panel">
        <div class="jv-panel-head">
          <span class="jv-panel-head-icon"><Gauge :size="14" :stroke-width="2" /></span>
          <span class="jv-panel-head-title">THROUGHPUT</span>
        </div>
        <div class="jv-chart-body">
          <RadialMeter :value="completionPct" label="COMPLETE" color="#34e0a1" :size="142" />
        </div>
      </div>

      <div class="jv-panel jv-chart-panel">
        <div class="jv-panel-head">
          <span class="jv-panel-head-icon"><BarChart3 :size="14" :stroke-width="2" /></span>
          <span class="jv-panel-head-title">{{ t('dashboard.artifact') }}</span>
        </div>
        <div class="jv-chart-body is-stack">
          <BarList v-if="artifactRows.length" :rows="artifactRows" color="#7c5cff" />
          <div v-else class="jv-chart-empty">NO DATA</div>
        </div>
      </div>
    </div>

    <!-- CVSS distribution -->
    <div class="jv-panel jv-dist-panel">
      <div class="jv-panel-head">
        <span class="jv-panel-head-icon"><Activity :size="14" :stroke-width="2" /></span>
        <span class="jv-panel-head-title">CVSS DISTRIBUTION</span>
        <div class="jv-panel-head-spacer" />
        <span class="jv-panel-head-note">{{ t('dashboard.cvss') }}</span>
      </div>
      <div class="jv-chart-body is-stack">
        <TrendBars :bars="scoreBands" :height="104" color="#22d3ee" />
      </div>
    </div>

    <!-- Ledger -->
    <div class="jv-panel" v-loading="loading">
      <div class="jv-ledger-head">
        <span>{{ t('dashboard.cveId') }}</span>
        <span>{{ t('common.status') }}</span>
        <span>{{ t('dashboard.cvss') }}</span>
        <span class="jv-ledger-hide">{{ t('dashboard.description') }}</span>
        <span class="jv-ledger-hide">{{ t('dashboard.startedAt') }}</span>
        <span class="jv-ledger-hide">{{ t('dashboard.duration') }}</span>
        <span />
      </div>

      <div
        v-for="(row, i) in tasks" :key="row.cveId"
        class="jv-ledger-row"
        :style="{ animationDelay: Math.min(i, 12) * 28 + 'ms' }"
        @click="router.push(`/analysis/${row.cveId}`)"
      >
        <span class="jv-ledger-cve">
          {{ row.cveId }}
          <ArrowUpRight :size="13" :stroke-width="2.2" />
        </span>
        <span><span :class="statusClass(row.status)">{{ t(`status.${row.status}`) }}</span></span>
        <span class="jv-ledger-score">
          <template v-if="row.cvssScore">
            <span :class="cvssClass(row.cvssScore)">{{ row.cvssScore }}</span>
            <span class="jv-ledger-score-bar">
              <i :style="{ width: (row.cvssScore / 10) * 100 + '%', background: cvssColor(row.cvssScore) }" />
            </span>
          </template>
          <span v-else class="jv-ledger-mono">—</span>
        </span>
        <span class="jv-ledger-text jv-ledger-hide" :title="row.description ?? ''">
          {{ row.description ?? '—' }}
        </span>
        <span class="jv-ledger-mono jv-ledger-hide">
          {{ row.createdAt?.replace('T', ' ').slice(0, 19) ?? '—' }}
        </span>
        <span class="jv-ledger-mono jv-ledger-hide">
          {{ formatDuration(row.createdAt, row.updatedAt, row.status) }}
        </span>
        <span class="jv-ledger-actions">
          <button
            class="jv-icon-btn"
            :title="t('common.delete')"
            @click.stop="deleteTask(row.cveId)"
          >
            <Trash2 :size="14" :stroke-width="1.9" />
          </button>
        </span>
      </div>

      <div v-if="!loading && tasks.length === 0" class="jv-empty">
        <Hexagon :size="42" :stroke-width="1.1" />
        <div class="jv-empty-title">{{ t('dashboard.empty') }}</div>
        <div class="jv-empty-sub">NO PIPELINE RUNS ON RECORD</div>
        <el-button type="primary" @click="router.push('/analysis/new')">
          {{ t('dashboard.startFirst') }}
        </el-button>
      </div>
    </div>
  </div>
</template>

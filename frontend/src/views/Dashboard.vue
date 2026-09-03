<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { api, type CveTask } from '../api'
import { useI18n } from '../i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Trash2, ArrowUpRight, Hexagon } from 'lucide-vue-next'

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
      </div>
    </section>

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

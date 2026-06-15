<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { api, type CveTask } from '../api'
import { useI18n } from '../i18n'

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
  await api.deleteTask(cveId)
  await load()
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
</script>

<template>
  <div>
    <!-- Page header -->
    <div class="jv-page-header">
      <div>
        <h2 style="margin:0 0 4px">{{ t('dashboard.title') }}</h2>
        <p style="color:var(--text-muted); font-size:13px; margin:0">
          {{ t('dashboard.summary', { total: stats.total, done: stats.done, running: stats.running }) }}
        </p>
      </div>
    </div>

    <!-- Stat cards -->
    <div class="jv-stats-row">
      <div class="jv-stat-card">
        <div class="jv-stat-label">{{ t('dashboard.total') }}</div>
        <div class="jv-stat-value">{{ stats.total }}</div>
      </div>
      <div class="jv-stat-card jv-stat-critical">
        <div class="jv-stat-label">{{ t('dashboard.critical') }}</div>
        <div class="jv-stat-value" style="color:var(--critical)">{{ stats.critical }}</div>
      </div>
      <div class="jv-stat-card jv-stat-running">
        <div class="jv-stat-label">{{ t('dashboard.running') }}</div>
        <div class="jv-stat-value" style="color:var(--medium)">{{ stats.running }}</div>
      </div>
      <div class="jv-stat-card jv-stat-done">
        <div class="jv-stat-label">{{ t('dashboard.completed') }}</div>
        <div class="jv-stat-value" style="color:var(--success)">{{ stats.done }}</div>
      </div>
    </div>

    <!-- Table -->
    <el-table :data="tasks" v-loading="loading" style="width:100%">

      <el-table-column prop="cveId" :label="t('dashboard.cveId')" width="180">
        <template #default="{ row }">
          <span class="jv-cve-link" @click="router.push(`/analysis/${row.cveId}`)">
            {{ row.cveId }}
          </span>
        </template>
      </el-table-column>

      <el-table-column :label="t('common.status')" width="130">
        <template #default="{ row }">
          <span :class="statusClass(row.status)">{{ t(`status.${row.status}`) }}</span>
        </template>
      </el-table-column>

      <el-table-column :label="t('common.stage')" width="80">
        <template #default="{ row }">
          <span style="font-family:var(--font-mono); color:var(--text-muted); font-size:13px">
            {{ row.currentStage }}/4
          </span>
        </template>
      </el-table-column>

      <el-table-column :label="t('dashboard.cvss')" width="110">
        <template #default="{ row }">
          <span v-if="row.cvssScore" :class="cvssClass(row.cvssScore)">
            {{ row.cvssScore }}
          </span>
          <span v-else style="color:var(--text-disabled)">—</span>
        </template>
      </el-table-column>

      <el-table-column prop="cweId" :label="t('dashboard.cwe')" width="100">
        <template #default="{ row }">
          <span style="font-family:var(--font-mono); color:var(--text-muted); font-size:12px">
            {{ row.cweId || '—' }}
          </span>
        </template>
      </el-table-column>

      <el-table-column :label="t('dashboard.artifact')">
        <template #default="{ row }">
          <span style="font-family:var(--font-mono); font-size:12px; color:var(--text-secondary)">
            {{ row.artifact ?? '—' }}
          </span>
        </template>
      </el-table-column>

      <el-table-column :label="t('dashboard.updated')" width="170">
        <template #default="{ row }">
          <span style="font-family:var(--font-mono); font-size:11px; color:var(--text-disabled)">
            {{ row.updatedAt?.replace('T', ' ').slice(0, 19) }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="" width="120" align="right">
        <template #default="{ row }">
          <el-button size="small" @click="router.push(`/analysis/${row.cveId}`)">{{ t('common.view') }}</el-button>
          <el-popconfirm
            :title="t('dashboard.deleteConfirm')"
            :confirm-button-text="t('common.delete')"
            :cancel-button-text="t('common.cancel')"
            @confirm="deleteTask(row.cveId)"
          >
            <template #reference>
              <el-button size="small" type="danger">{{ t('common.delete') }}</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loading && tasks.length === 0" class="jv-empty">
      <div style="font-size:32px; margin-bottom:12px">⬡</div>
      <div style="color:var(--text-muted); font-size:14px">{{ t('dashboard.empty') }}</div>
      <el-button type="primary" style="margin-top:16px" @click="router.push('/analysis/new')">
        {{ t('dashboard.startFirst') }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.jv-page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
}
.jv-stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1px;
  margin-bottom: 24px;
  background: var(--border-subtle);
  border: 1px solid var(--border-subtle);
}
.jv-stat-card {
  background: var(--bg-surface);
  padding: 16px 20px;
}
.jv-stat-critical { border-top: 2px solid var(--critical); }
.jv-stat-running  { border-top: 2px solid var(--medium); }
.jv-stat-done     { border-top: 2px solid var(--success); }
.jv-stat-label {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-disabled);
  letter-spacing: 1px;
  margin-bottom: 8px;
}
.jv-stat-value {
  font-family: var(--font-mono);
  font-size: 28px;
  font-weight: 300;
  color: var(--text-primary);
  line-height: 1;
}
.jv-cve-link {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--accent-light);
  cursor: pointer;
}
.jv-cve-link:hover { color: var(--accent); text-decoration: underline; }
.jv-empty {
  text-align: center;
  padding: 60px 0;
}
</style>

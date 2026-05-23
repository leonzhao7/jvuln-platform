<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, type CveTask } from '../api'

const router = useRouter()
const tasks = ref<CveTask[]>([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try { tasks.value = await api.listTasks() }
  finally { loading.value = false }
}

onMounted(load)

const statusTag = (s: string) => {
  const map: Record<string, string> = {
    PENDING: 'info', RUNNING: 'warning', COMPLETED: 'success', FAILED: 'danger'
  }
  return map[s] ?? 'info'
}

const deleteTask = async (cveId: string) => {
  await api.deleteTask(cveId)
  await load()
}
</script>

<template>
  <div>
    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:20px">
      <h2 style="color:#e2e8f0">CVE Analysis Tasks</h2>
      <el-button type="primary" @click="router.push('/analysis/new')">+ New Analysis</el-button>
    </div>

    <el-table :data="tasks" v-loading="loading"
      style="background:#1e1e3a; border-radius:8px"
      :header-cell-style="{ background:'#2a2a4a', color:'#94a3b8' }"
      :cell-style="{ background:'#1e1e3a', color:'#e2e8f0' }">

      <el-table-column prop="cveId" label="CVE ID" width="180">
        <template #default="{ row }">
          <el-link @click="router.push(`/analysis/${row.cveId}`)" style="color:#60a5fa">
            {{ row.cveId }}
          </el-link>
        </template>
      </el-table-column>

      <el-table-column label="Status" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="Stage" width="100">
        <template #default="{ row }">
          <span>{{ row.currentStage }}/5</span>
        </template>
      </el-table-column>

      <el-table-column prop="cvssScore" label="CVSS" width="80"/>
      <el-table-column prop="cweId" label="CWE" width="100"/>

      <el-table-column label="Artifact">
        <template #default="{ row }">
          <span style="font-size:12px; color:#94a3b8">{{ row.artifact ?? '-' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="Updated" width="180">
        <template #default="{ row }">
          <span style="font-size:12px; color:#64748b">{{ row.updatedAt?.replace('T', ' ').slice(0, 19) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="Actions" width="120">
        <template #default="{ row }">
          <el-button size="small" @click="router.push(`/analysis/${row.cveId}`)">View</el-button>
          <el-popconfirm title="Delete this task?" @confirm="deleteTask(row.cveId)">
            <template #reference>
              <el-button size="small" type="danger">Del</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && tasks.length === 0" description="No analyses yet. Start one!" />
  </div>
</template>

import axios from 'axios'

const http = axios.create({ baseURL: '/api' })

export interface CveTask {
  id: number
  cveId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  currentStage: number
  artifact: string | null
  cvssScore: number | null
  cweId: string | null
  workspacePath: string
  createdAt: string
  updatedAt: string
}

export interface StageRecord {
  id: number
  cveId: string
  stageNum: number
  stageName: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED'
  startedAt: string | null
  finishedAt: string | null
  errorMsg: string | null
}

export interface TaskDetail {
  task: CveTask
  stages: StageRecord[]
}

export const api = {
  listTasks: () => http.get<CveTask[]>('/analysis').then(r => r.data),

  submitTask: (cveId: string) =>
    http.post<{ cveId: string; status: string }>('/analysis', { cveId }).then(r => r.data),

  getTask: (cveId: string) =>
    http.get<TaskDetail>(`/analysis/${cveId}`).then(r => r.data),

  deleteTask: (cveId: string) => http.delete(`/analysis/${cveId}`),

  rerunTask: (cveId: string, fromStage?: number) =>
    http.post(`/analysis/${cveId}/rerun`, null, { params: { fromStage } }).then(r => r.data),

  getIntelligence: (cveId: string) =>
    http.get(`/analysis/${cveId}/intelligence`).then(r => r.data),

  getPatch: (cveId: string) =>
    http.get(`/analysis/${cveId}/patch`).then(r => r.data),

  getDiff: (cveId: string) =>
    http.get<{ diff: string }>(`/analysis/${cveId}/diff`).then(r => r.data),

  getCodeAnalysis: (cveId: string) =>
    http.get(`/analysis/${cveId}/code-analysis`).then(r => r.data),

  getReasoning: (cveId: string) =>
    http.get(`/analysis/${cveId}/reasoning`).then(r => r.data),

  getReport: (cveId: string) =>
    http.get<{ markdown: string }>(`/analysis/${cveId}/report`).then(r => r.data),

  getLlmConfig: () => http.get<LlmConfig>('/config/llm').then(r => r.data),
  saveLlmConfig: (cfg: LlmConfig) => http.put<LlmConfig>('/config/llm', cfg).then(r => r.data),
  testLlmConfig: () => http.post<{ ok: boolean; model?: string; response?: string; tokens?: string; error?: string }>('/config/llm/test').then(r => r.data),
}

export interface LlmConfig {
  id?: number
  providerType: string
  baseUrl: string
  apiKey: string
  model: string
  temperature: number
  maxTokens: number
  enabled: boolean
}

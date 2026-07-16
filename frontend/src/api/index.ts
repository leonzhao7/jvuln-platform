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
  description: string | null
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

  getStageJson: (cveId: string, stageNum: number) =>
    http.get(`/analysis/${cveId}/stages/${stageNum}/json`).then(r => r.data),

  getDiff: (cveId: string) =>
    http.get<{ diff: string }>(`/analysis/${cveId}/diff`).then(r => r.data),

  getCodeAnalysis: (cveId: string) =>
    http.get(`/analysis/${cveId}/code-analysis`).then(r => r.data),

  getReasoning: (cveId: string) =>
    http.get(`/analysis/${cveId}/reasoning`).then(r => r.data),

  getReport: (cveId: string) =>
    http.get<{ markdown: string }>(`/analysis/${cveId}/report`).then(r => r.data),

  getArtifacts: (cveId: string) =>
    http.get(`/analysis/${cveId}/artifacts`).then(r => r.data),

  getArtifactFile: (cveId: string, path: string) =>
    http.get<{ path: string; content: string }>(`/analysis/${cveId}/artifacts/file`, { params: { path } }).then(r => r.data),

  downloadArtifacts: (cveId: string) =>
    http.get(`/analysis/${cveId}/artifacts/download`, { responseType: 'blob' }).then(r => r.data as Blob),

  getTranscript: (cveId: string) =>
    http.get<TranscriptEvent[]>(`/analysis/${cveId}/transcript`).then(r => r.data),

  getPipelineLog: (cveId: string) =>
    http.get<PipelineLogEntry[]>(`/analysis/${cveId}/pipeline-log`).then(r => r.data),

  listLlmConfigs: () => http.get<LlmConfig[]>('/config/llm').then(r => r.data),
  createLlmConfig: (cfg: Omit<LlmConfig, 'id' | 'active'>) => http.post<LlmConfig>('/config/llm', cfg).then(r => r.data),
  updateLlmConfig: (id: number, cfg: Partial<LlmConfig>) => http.put<LlmConfig>(`/config/llm/${id}`, cfg).then(r => r.data),
  deleteLlmConfig: (id: number) => http.delete(`/config/llm/${id}`),
  activateLlmConfig: (id: number) => http.post<LlmConfig>(`/config/llm/${id}/activate`).then(r => r.data),
  testLlmConfig: (id: number) =>
    http.post<{ ok: boolean; model?: string; response?: string; tokens?: string; error?: string }>(`/config/llm/${id}/test`).then(r => r.data),

  // Java Profiles
  listJavaProfiles: () => http.get<JavaProfile[]>('/config/java-profiles').then(r => r.data),
  createJavaProfile: (p: Omit<JavaProfile, 'id' | 'isDefault'>) => http.post<JavaProfile>('/config/java-profiles', p).then(r => r.data),
  updateJavaProfile: (id: number, p: Partial<JavaProfile>) => http.put<JavaProfile>(`/config/java-profiles/${id}`, p).then(r => r.data),
  deleteJavaProfile: (id: number) => http.delete(`/config/java-profiles/${id}`),
  setDefaultJavaProfile: (id: number) => http.post<JavaProfile>(`/config/java-profiles/${id}/set-default`).then(r => r.data),
}

export interface LlmConfig {
  id?: number
  name: string
  baseUrl: string
  apiKey: string
  model: string
  endpoint: '/v1/chat/completions' | '/v1/responses' | '/v1/messages'
  active: boolean
}

export interface JavaProfile {
  id?: number
  name: string
  javaVersion: string
  javaHome: string
  springBootVersion: string
  mavenJavaVersion: string
  syntaxConstraints: string
  isDefault: boolean
}

export interface TranscriptEvent {
  ts: number
  turn: number
  type: 'assistant' | 'directive' | 'tool_results' | 'compact'
  phase: string
  payload: any
}

export interface PipelineLogEntry {
  type: string
  stageNum: number
  message: string
  timestamp: number
}

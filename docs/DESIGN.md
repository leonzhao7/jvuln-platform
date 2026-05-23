# JVuln Platform — 架构设计文档

## 1. 设计目标

JVuln Platform 是一个面向企业内部安全团队的 Java CVE 漏洞分析与教育平台。核心设计原则：

- **确定性工作由程序完成**：情报采集、补丁定位、代码解析等结构化任务通过代码实现，保证稳定可重现。
- **推理性工作由 AI 完成**：漏洞触发链分析、根因识别、修复完整性评估等需要语义理解的任务交由 LLM。
- **文件系统作为数据主体**：分析数据以 JSON 文件存储，数据库仅作轻量索引，便于调试和导出。
- **断点续跑**：已完成阶段从文件缓存读取，支持从任意阶段重跑。

---

## 2. 整体架构

```
┌────────────────────────────────────────────────────────────────┐
│  Browser (Vue 3 + TypeScript)                                  │
│  Dashboard → New Analysis → Detail (5-stage tabs) → Settings  │
└──────────────────────────────┬─────────────────────────────────┘
                               │ HTTP + SSE
┌──────────────────────────────▼─────────────────────────────────┐
│  Spring Boot 2.7 (Java 8)                                      │
│  ┌──────────────┐  ┌─────────────────────────────────────────┐ │
│  │ REST API     │  │ Pipeline Engine (Async Thread Pool)      │ │
│  │ /api/analysis│  │  Stage1 → Stage2 → Stage3 → Stage4 → 5  │ │
│  │ /api/config  │  │  SSE progress push via SseEmitter        │ │
│  └──────────────┘  └──────────────────────┬──────────────────┘ │
│  ┌──────────────────────────────────────  │  ──────────────────┐│
│  │ jvuln-store: H2 (task index) + Workspace (JSON files)      ││
│  └────────────────────────────────────────────────────────────┘│
└────────────────────────────────────────────────────────────────┘
         │                    │
         ▼                    ▼
  外部 API 源           LLM Provider
  NVD / GHSA / OSV     Anthropic / OpenAI-compat
  GitHub / Maven
```

---

## 3. 模块结构

### 后端（Maven 多模块）

| 模块 | 职责 |
|------|------|
| `jvuln-app` | Spring Boot 启动入口、REST Controller、跨域配置 |
| `jvuln-pipeline` | 5 阶段 Pipeline 引擎、SSE 推送、异步执行 |
| `jvuln-collector` | Stage 1：NVD/GHSA/OSV/Maven 情报采集 |
| `jvuln-patcher` | Stage 2：GitHub commit 定位、unified diff 提取 |
| `jvuln-analyzer` | Stage 3：JavaParser AST 分析、CWE 模式匹配 |
| `jvuln-llm` | AI 抽象层：Anthropic / OpenAI-compat 双适配 |
| `jvuln-generator` | Stage 5：漏洞复现项目生成、PoC、报告 |
| `jvuln-store` | H2 实体、WorkspaceManager 文件 I/O |

### 前端（Vue 3）

| 目录/文件 | 职责 |
|-----------|------|
| `views/Dashboard.vue` | CVE 任务列表、状态筛选 |
| `views/AnalysisDetail.vue` | 5 阶段进度 + Tab 结果展示 |
| `views/Settings.vue` | LLM 配置（Provider / API Key / Model）|
| `components/StageProgress.vue` | SSE 驱动的 5 步进度条 |
| `api/` | Axios 封装，统一错误处理 |
| `stores/` | Pinia 全局状态 |

---

## 4. 存储设计

### 混合存储策略

```
H2 嵌入式数据库（索引层）           文件系统（数据层）
─────────────────────────           ──────────────────────────────
cve_task                            workspace/CVE-xxxx-xxxxx/
  ├─ cve_id (UNIQUE)                  ├─ stages/
  ├─ status (PENDING/RUNNING/           │   ├─ 1_intelligence.json
  │          COMPLETED/FAILED)         │   ├─ 2_patch.json
  ├─ current_stage (0-5)              │   ├─ 3_analysis.json
  └─ workspace_path                   │   ├─ 4_reasoning.json
                                      │   └─ 5_artifacts.json
stage_record                          ├─ patches/
  ├─ cve_id                           │   ├─ fix.diff
  ├─ stage_num (1-5)                  │   └─ source/{before,after}/
  ├─ status                           ├─ vuln-demo/  # 生成的复现项目
  └─ error_msg                        ├─ poc/
                                      └─ report/
```

**设计理由**：数据库只维护"有哪些任务、当前状态"（轻量查询、列表展示），分析数据全在文件系统（便于调试、手动查看、版本管理）。

### DB/文件同步

当 DB 状态与磁盘文件不一致时（如并发重跑导致某阶段 DB 标记 FAILED 但文件实际存在），可调用 `POST /api/analysis/{cveId}/sync-status` 重新对齐：遍历各阶段文件，存在则将 DB 状态更新为 COMPLETED。

---

## 5. Pipeline 引擎

### 执行流程

```java
// PipelineEngine.execute(cveId, fromStage) 在 @Async 线程池中运行
for (Stage stage : stages) {
    if (stage.number() < fromStage) continue;
    if (workspaceManager.isStageComplete(cveId, stage.number())) {
        // 从文件缓存加载，跳过执行
        ctx.addCompletedStage(stage.number(), loadFromCache());
        continue;
    }
    stageRecord.setStatus(RUNNING);
    StageResult result = stage.execute(ctx);
    if (result.isSuccess()) {
        stageRecord.setStatus(COMPLETED);
    } else {
        stageRecord.setStatus(FAILED);
        break;
    }
}
```

### SSE 推送

每个阶段通过 `PipelineContext.reportProgress(message)` 推送事件，前端通过 `EventSource` 接收：

```
event: stage_start   data: {"stage":1,"name":"Intelligence Collection"}
event: progress      data: {"message":"Fetching NVD data..."}
event: stage_done    data: {"stage":1,"status":"COMPLETED"}
event: pipeline_done data: {"status":"COMPLETED"}
```

### 断点续跑

- 默认：从文件缓存恢复已完成阶段，仅执行缺失阶段。
- 强制重跑：`POST /api/analysis/{cveId}/rerun?fromStage=3` 从指定阶段重新执行（删除该阶段及之后的缓存文件）。

---

## 6. 各阶段实现

### Stage 1 — Intelligence Collection

数据源优先级：NVD → GHSA → OSV，结果合并去重。

```json
{
  "cveId": "CVE-2025-24813",
  "description": "...",
  "cvss": {"score": 9.8, "vector": "CVSS:3.1/..."},
  "cweId": "CWE-44",
  "artifact": "org.apache.tomcat:tomcat-catalina",
  "affectedVersions": ["9.0.0-9.0.98", "10.1.0-10.1.34"],
  "fixedVersion": "9.0.99",
  "fixCommits": ["https://github.com/apache/tomcat/commit/0a668e0c"]
}
```

### Stage 2 — Patch Locating

策略链（按优先级）：
1. **RefCommitStrategy**：直接从情报中提取 fixCommits URL，下载 commit diff
2. **SearchCommitStrategy**：用 CVE ID 搜索 GitHub commit message
3. **TagDiffStrategy**：diff 相邻发布 tag（fixedVersion vs 前一版本）

产物：`patches/fix.diff`（unified diff），以及 `patches/source/` 下 before/after 源文件（供 Stage 3 解析）。

### Stage 3 — Code Analysis

使用 JavaParser 直接解析源文件 AST（无子进程）：

- **方法提取**：`MethodDeclaration` 访问者，记录方法签名、行号、参数
- **调用链解析**：`MethodCallExpr` 访问者，追踪调用关系
- **CWE 模式匹配**：规则引擎，预置规则包括：
  - CWE-44：路径等价（`replace('/', '.')` 等路径分隔符操作）
  - CWE-502：反序列化（`ObjectInputStream`、`readObject()`）
  - CWE-22：路径穿越（`..` 未过滤）
  - CWE-79：XSS（未转义输出到响应）

### Stage 4 — Vulnerability Reasoning (AI)

**Prompt 策略**：系统提示固定角色为"漏洞分析专家"，用户提示注入三个输入：
- 精简后的 intelligence JSON（仅保留 cveId/cweId/description/cvss/fixedVersion/artifact/fixCommits）
- 原始 patch diff 文本
- 精简后的代码分析结果（仅 methods + cweMatches，去掉详细调用链）

**Anthropic 集成**：`AnthropicCaller.chat()` 内部使用 `stream: true`，通过 `bodyToFlux` + `blockLast()` 收集所有 `content_block_delta` 事件，规避代理的非流式超时限制（代理对非流式请求有 ~60s 超时，流式则不同）。

**输出格式**：要求 LLM 返回结构化 JSON，包含：
```json
{
  "triggerChain": [...],
  "rootCause": "...",
  "exploitability": "...",
  "fixAssessment": "...",
  "severity": "CRITICAL"
}
```
`ReasoningStage` 在解析前先 strip markdown code fence（LLM 可能将 JSON 包裹在 ` ```json ``` ` 中）。

### Stage 5 — Artifact Generation

生成可运行的 Spring Boot 漏洞复现项目，位于 `workspace/{cveId}/vuln-demo/`：
- 配置了漏洞触发条件的 `application.properties`（如 `readonly=false`, `allowPartialPut=true`）
- 演示触发路径的 REST controller
- PoC 脚本（`poc/exploit.sh` 或 `.py`）
- Markdown 格式报告（`report/report.md`）

---

## 7. AI 抽象层

### 双适配架构

```
LlmClient (interface)
  ├── AnthropicCaller      — Anthropic Messages API 原生
  │                          header: x-api-key + anthropic-version
  │                          endpoint: POST /v1/messages
  └── OpenAiCompatClient   — OpenAI 兼容协议
                             header: Authorization: Bearer
                             endpoint: POST /v1/chat/completions
```

`LlmClientFactory` 根据配置的 `provider` 字段（`anthropic` / `openai-compat`）实例化对应实现，并注入 `baseUrl`、`apiKey`、`model`。

### 配置持久化

LLM 配置通过 `PUT /api/config/llm` 保存到 `backend/data/llm-config.json`，重启后自动加载，不依赖数据库。

### 连通性测试

`POST /api/config/llm/test` 发送一条轻量请求（`max_tokens=5`）验证端点可达和 API Key 有效性，返回延迟和模型信息。

---

## 8. 前端架构

### 路由结构

```
/                  → Dashboard（CVE 任务列表）
/analysis/new      → 新建分析
/analysis/:cveId   → 分析详情（Tab: Intelligence / Patch / Code / AI / Artifacts）
/settings          → LLM 配置
```

### SSE 驱动的进度更新

```typescript
// AnalysisDetail.vue
const es = new EventSource(`/api/analysis/${cveId}/stream`);
es.addEventListener('stage_start', e => { /* 更新进度条 */ });
es.addEventListener('stage_done',  e => { /* 标记完成，加载阶段数据 */ });
es.addEventListener('pipeline_done', () => { es.close(); loadAllStages(); });
```

### Diff 展示

Patch Diff tab 使用 `diff2html` 将 unified diff 渲染为 side-by-side 或 line-by-line HTML，提供语法高亮。

---

## 9. 关键设计决策

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 数据库 | MySQL / H2 / SQLite | H2 嵌入式 | 零依赖，单文件，适合内部工具 |
| Java 版本 | 21 / 8 | Java 8 | 兼容企业内部 JDK 版本约束 |
| AST 分析 | 子进程 / 依赖 | Maven 依赖 (JavaParser) | 无子进程开销，JVM 内直接调用 |
| LLM 超时 | 非流式 / 流式 | 流式 + 收集 | 规避代理非流式 60s 超时 |
| Stage 数据 | 全量 DB / 文件系统 | 文件系统 | 便于调试、手动查看、导出 |
| AI 响应格式 | 纯文本 / JSON | 结构化 JSON | 便于前端解析展示触发链等数据 |

---

## 10. 安全约束

- 平台仅限本地部署（`localhost`），不对外暴露
- 所有漏洞演示项目仅在本地环境运行，不部署到任何外部系统
- 生成的 PoC 仅用于理解漏洞原理，不用于测试非授权目标
- AI Reasoning 结果仅供分析参考，不作为自动化漏洞扫描依据

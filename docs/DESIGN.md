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

| 路由/文件 | 职责 |
|-----------|------|
| `/` → `Dashboard.vue` | CVE 任务列表、状态筛选、一键新建 |
| `/analysis/new` → `NewAnalysis.vue` | 输入 CVE ID，选择起始阶段，提交分析 |
| `/analysis/:cveId` → `AnalysisDetail.vue` | 5 阶段进度条（SSE 驱动）+ 结果 Tab 展示 |
| `/analysis/:cveId/diff` → `PatchDiff.vue` | unified diff 渲染（diff2html），支持 side-by-side / line-by-line 切换 |
| `/settings` → `Settings.vue` | 多 LLM 配置管理（表格 + 激活/测试/编辑/删除） |
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

策略链（按优先级依次尝试，成功即停止）：

| 优先级 | 策略 | 说明 |
|--------|------|------|
| 1 | `reference-commit` | 从 Stage 1 情报的 `fixCommits` URL 直接下载 commit diff |
| 2 | `ghsa-commit` | 从 GHSA advisory 提取 patch 引用 commit |
| 3 | `cve-commit-search` | 用 CVE ID 搜索 GitHub commit message；多结果时用 AI 辅助挑选最相关的 commit |
| 4 | `maven-source-diff` | 下载修复版本和受影响版本的 JAR，解压后 diff 源文件（fallback） |
| 5 | `ai-patch-search` | AI 根据 CVE 描述生成搜索关键词，在 GitHub 代码/commit 中定位修复（最后手段） |

产物：`patches/fix.diff`（unified diff），以及 `patches/source/` 下 before/after 源文件（供 Stage 3 解析）。

**CRLF 处理**：maven-source-diff 生成时使用 `diff --strip-trailing-cr`，避免因 JAR 内文件行尾为 CRLF 而导致所有行都出现在 diff 中（可将无关文件数从 90+ 膨胀到实际修改的 20+ 个）。

### Stage 3 — Code Analysis

#### Diff 相关性过滤（DiffRelevanceFilter）

maven-source-diff 策略产生的 diff 可能包含大量无关文件（新增 feature、重构、分页 dialect 等）。Stage 3 进入实际分析前先做两阶段过滤：

**阶段一：传统预过滤**（确定性，快速同步）

排除条件需同时满足（保守策略，有疑问则保留）：
- 移除行数 < 5（几乎是纯新增，非修复）
- diff 内容不含安全关键词（`inject`、`sql`、`xss`、`deserializ`、`bypass`、`traversal` 等约 20 个）
- diff 内容不含 CVE 描述中提取的特征 token（组件名、类名等）

**阶段二：AI 过滤**（当预过滤后仍 > 6 个文件时触发）

向 LLM 发送每个文件的压缩摘要（路径 + 增减行数 + 方法名 + 3 行移除代码样本），要求返回：
```json
[{"file": "path/to/File.java", "relevant": true, "reason": "one sentence"}]
```
安全机制：AI 返回 0 个文件或调用失败时，回退到预过滤结果。

**阈值**：≤ 6 个文件（commit 策略的 diff 通常很精准）直接跳过所有过滤。

#### 代码静态分析

使用 JavaParser 直接解析源文件 AST（无子进程）：

- **方法提取**：从 diff 的 hunk 头（`@@ ... @@ method signature`）提取变更方法名；再用 JavaParser `MethodDeclaration` 访问者补全签名和调用关系
- **调用链解析**：`MethodCallExpr` 访问者，追踪变更方法内的调用关系
- **CWE 模式匹配**：对移除（漏洞）代码做正则匹配，预置规则：

| CWE | 名称 | 检测模式示例 |
|-----|------|------|
| CWE-44 | Path Equivalence | `replace('/', '.')` 等路径分隔符替换 |
| CWE-22 | Path Traversal | `../` 未过滤的路径穿越 |
| CWE-377 | Insecure Temporary File | `File.createTempFile` 等不安全临时文件创建 |
| CWE-502 | Deserialization of Untrusted Data | `ObjectInputStream`、`readObject()` |
| CWE-404 | Improper Resource Shutdown | 流/连接未关闭 |

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

### 配置持久化（多模型支持）

LLM 配置存储在 H2 数据库的 `llm_config` 表中，支持保存多条配置并指定其中一条为激活状态：

- `POST /api/config/llm` — 新建配置
- `PUT /api/config/llm/{id}` — 更新配置（API Key 显示为 `••••••••`，提交该值时不覆盖原 Key）
- `DELETE /api/config/llm/{id}` — 删除配置
- `POST /api/config/llm/{id}/activate` — 原子操作：将所有配置 `active=false`，再将目标配置 `active=true`
- `POST /api/config/llm/{id}/test` — 用指定配置发送轻量请求验证连通性

`DbLlmConfigProvider` 通过 `findByActiveTrue()` 查询当前激活配置，供 Pipeline 各阶段使用。

**H2 兼容注意**：`active` 字段使用 `Boolean`（装箱类型）而非 `boolean`，避免 `ddl-auto: update` 添加新列后现有行为 NULL 导致映射失败。ID 使用 `@GeneratedValue(SEQUENCE)` 而非 `IDENTITY`，避免 H2 无法 ALTER 已有列类型。

### 连通性测试

`POST /api/config/llm/{id}/test` 发送一条轻量请求（`max_tokens=5`）验证端点可达和 API Key 有效性，返回延迟和模型信息。

### Diff 视图过滤

`GET /api/analysis/{cveId}/diff` 在 Stage 3 数据可用时，自动将原始 `fix.diff` 过滤为只包含 Stage 3 `analyzedFiles` 中的文件，响应同时返回 `totalFiles`（原始文件数）和 `shownFiles`（过滤后文件数）。Stage 3 数据不存在时退化为返回完整 diff。

---

## 8. 前端架构

### 路由结构

```
/                        → Dashboard（CVE 任务列表）
/analysis/new            → 新建分析（输入 CVE ID，选择起始阶段）
/analysis/:cveId         → 分析详情（Tab: Intelligence / Patch / Code / AI / Artifacts）
/analysis/:cveId/diff    → Patch Diff 全屏视图（side-by-side / line-by-line）
/settings                → LLM 配置管理（多配置表格，activate/test/edit/delete）
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

`/analysis/:cveId/diff` 路由对应独立的 `PatchDiff.vue`，使用 `diff2html` 将 unified diff 渲染为 side-by-side 或 line-by-line HTML，展示的文件由后端按 Stage 3 结果过滤（`shownFiles` / `totalFiles`）。

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
| Diff 噪音过滤 | 纯传统 / 纯 AI | 两阶段混合 | 传统预过滤快且稳定，AI 处理语义判断；小 diff（≤6 文件）跳过过滤 |
| LLM 多配置 | 单配置文件 / DB 多条 | H2 多行 + active 标志 | 支持切换不同 provider/model，保留历史配置 |

---

## 10. 安全约束

- 平台仅限本地部署（`localhost`），不对外暴露
- 所有漏洞演示项目仅在本地环境运行，不部署到任何外部系统
- 生成的 PoC 仅用于理解漏洞原理，不用于测试非授权目标
- AI Reasoning 结果仅供分析参考，不作为自动化漏洞扫描依据

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
| `i18n/` | 轻量本地化辅助模块，提供 `useI18n()`、`t()`、`array()`、`setLocale()` |
| `locales/` | 中英文资源文件（`zh-CN.ts` / `en-US.ts`） |
| `api/` | Axios 封装，统一错误处理 |
| `stores/` | Pinia 全局状态 |

### 前端视觉与本地化

前端采用 IBM Carbon 风格深色主题：方正组件、高对比暗色背景、蓝色主操作，以及 success/running/failed/pending/critical/high/medium/low 等语义化状态颜色。全局样式集中在 `src/style.css`，Patch Diff 视图使用 `diff2html` 暗色模式并在 `PatchDiff.vue` 中重写 `--d2h-dark-*` 变量以贴合 Carbon 配色。

UI 文案不直接写在页面组件中，而是放在两个资源文件中：

- `frontend/src/locales/zh-CN.ts` — 中文资源，默认语言
- `frontend/src/locales/en-US.ts` — 英文资源

`App.vue` Header 提供语言切换器，选择结果持久化到 `localStorage` 的 `jvuln-locale`。

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
  "severity": "CRITICAL",
  "detectionPoints": [...]
}
```
`ReasoningStage` 在解析前先 strip markdown code fence（LLM 可能将 JSON 包裹在 ` ```json ``` ` 中）。

#### 漏洞源码检测要点（detectionPoints）

Stage 4 在推理触发链和根因的同时，要求 AI **额外输出一组可机器执行的检测要点**，供 Stage 6 在任意 Java 项目上扫描是否存在相同漏洞。每条检测要点包含：

```json
{
  "detectionPoints": [
    {
      "id": "DP-1",
      "type": "dependency",
      "description": "使用了受影响版本的 tomcat-catalina",
      "artifact": "org.apache.tomcat:tomcat-catalina",
      "affectedVersionRange": ">=9.0.0 <9.0.99",
      "fixedVersion": "9.0.99"
    },
    {
      "id": "DP-2",
      "type": "code_pattern",
      "description": "DefaultServlet.doPut() 中将路径分隔符替换为点号",
      "cweId": "CWE-44",
      "pattern": "replace.*['\"]/['\"].*['\"]\\\\.['\"]",
      "className": "DefaultServlet",
      "methodName": "doPut",
      "matchTarget": "vulnerable_code"
    },
    {
      "id": "DP-3",
      "type": "config_risk",
      "description": "Tomcat 启用了 partial PUT 且使用 FileStore session",
      "configKeys": [
        {"key": "server.tomcat.allow-partial-put", "riskyValue": "true"},
        {"key": "server.servlet.session.store-type", "riskyValue": "file"}
      ]
    },
    {
      "id": "DP-4",
      "type": "api_usage",
      "description": "代码中直接调用了 ObjectInputStream.readObject() 处理不可信输入",
      "dangerousApis": ["ObjectInputStream.readObject", "FileStore.load"],
      "safeAlternatives": ["使用白名单 ObjectInputFilter", "使用数据库 session 存储"]
    }
  ]
}
```

检测要点类型：

| type | 说明 | Stage 6 扫描方式 |
|------|------|-----------------|
| `dependency` | 受影响的 Maven/Gradle 依赖版本 | 解析 pom.xml / build.gradle 比对版本范围 |
| `code_pattern` | 漏洞代码的正则/AST 特征 | JavaParser 扫描 + 正则匹配 |
| `config_risk` | 触发漏洞所需的配置项 | 扫描 application.yml / .properties |
| `api_usage` | 调用了危险 API 且缺乏安全防护 | JavaParser MethodCallExpr 匹配 |

这些检测要点是 Stage 4 的核心扩展产出——它们将 AI 对漏洞的理解转化为可程序化执行的检测规则，是 Stage 6 产品漏洞检测的依据。

### Stage 5 — Vulnerability Education Lab（漏洞教学演示）

生成可运行的漏洞复现教学环境，位于 `workspace/{cveId}/`，包含：
- `vuln-demo/`：可编译、可启动的 Spring Boot 教学项目
- `poc/exploit.sh`：针对真实漏洞路径的验证脚本
- `report/report.md`：教学报告

当前实现不是一次性“生成全部文件然后结束”，而是一个 **后端约束的 agent 循环**：

1. **先提交执行计划**
   - 首批文件
   - 最小交付物
   - 验证顺序
   - 风险与报告策略
2. **一次性写最小候选**
   - 优先用一轮 `write_files` 生成最小可运行的 `vuln-demo + poc`
3. **后端验证驱动修复**
   - 后端自动执行编译、启动和 PoC 验证
   - 根据结果切到编译修复、启动修复或 PoC 修复阶段
4. **验证通过后再收尾报告**
   - 只有在后端证据绿灯后，才写/改 `report/report.md` 并 finish

当前 Stage 5 的几个关键收敛机制：
- 显式 phase：`PLAN / GENERATE_MINIMAL / COMPILE_FIX / STARTUP_FIX / POC_FIX / REPORT`
- `5_memory.json` 记录失败上下文，重跑时带入提示，但不恢复旧控制流
- 启动前清理同一 workspace 的残留 demo 进程，避免 `18080` 端口占用导致假性 startup failed
- 连续无进展会中止，而不是无限读文件/重复验证
- 若后端已验证 `compileOk + startupOk + pocVerified`，reviewer LLM 不再有权阻止完成

这使 Stage 5 的职责从“让模型自己判断是否成功”转向“由模型生成和修补，由后端负责验证与收敛控制”。

### Stage 6 — Product Vulnerability Detection（产品漏洞检测）

#### 目标

基于 Stage 4 输出的 `detectionPoints`，扫描开发团队的 Java 项目源码，判断是否受同一漏洞影响，并生成项目特定的触发链分析和测试环境验证 PoC。

#### 输入

| 来源 | 内容 |
|------|------|
| Stage 4 `detectionPoints` | 可执行的检测规则（依赖、代码模式、配置、API 调用） |
| Stage 4 `triggerChain` | 漏洞触发链（用于生成项目特定的触发路径） |
| 用户指定 | 待扫描的 Java 项目路径 |

#### 扫描流程

```
用户指定项目路径 + CVE 编号
            │
            ▼
┌───────────────────────────────┐
│ 1. 依赖版本检测（程序化）       │  解析 pom.xml / build.gradle
│    匹配 detectionPoints       │  比对 type=dependency 条目
│    [type=dependency]          │  → AFFECTED / SAFE / UNKNOWN
└──────────────┬────────────────┘
               │
┌──────────────▼────────────────┐
│ 2. 代码模式扫描（程序化）       │  JavaParser 遍历项目源码
│    匹配 detectionPoints       │  正则 + AST 匹配
│    [type=code_pattern]        │  → 匹配的文件:行号:代码片段
└──────────────┬────────────────┘
               │
┌──────────────▼────────────────┐
│ 3. 配置风险检测（程序化）       │  扫描 application.yml/properties
│    匹配 detectionPoints       │  检查 type=config_risk 的配置键
│    [type=config_risk]         │  → 风险配置项列表
└──────────────┬────────────────┘
               │
┌──────────────▼────────────────┐
│ 4. 危险 API 调用检测（程序化）  │  JavaParser MethodCallExpr
│    匹配 detectionPoints       │  匹配 type=api_usage 的危险 API
│    [type=api_usage]           │  → 调用位置 + 上下文
└──────────────┬────────────────┘
               │
┌──────────────▼────────────────┐
│ 5. 综合风险评估 + 触发链（AI）  │  将上述发现 + Stage 4 triggerChain
│                               │  输入 LLM，生成：
│                               │  - 该项目特定的触发链
│                               │  - 风险等级（CRITICAL/HIGH/...）
│                               │  - 修复建议
└──────────────┬────────────────┘
               │
┌──────────────▼────────────────┐
│ 6. 验证 PoC 生成（AI）         │  基于项目特定的触发链
│                               │  生成可在测试环境运行的验证 PoC
│                               │  自动判定 VULNERABLE / SAFE
└───────────────────────────────┘
```

#### 输出

```
workspace/CVE-xxxx-xxxxx/scan-{projectName}/
├── scan-result.json            # 完整扫描结果
├── trigger-chain.json          # 该项目特定的触发链
├── verify-exploit.py           # 验证 PoC（测试环境用）
├── verify-exploit.sh           # Shell 版验证 PoC
└── remediation.md              # 修复建议文档
```

**scan-result.json 结构**：

```json
{
  "cveId": "CVE-2025-24813",
  "projectPath": "/path/to/project",
  "scanTime": "2026-05-27T10:00:00Z",
  "overallRisk": "HIGH",
  "matchedPoints": [
    {
      "pointId": "DP-1",
      "type": "dependency",
      "status": "AFFECTED",
      "evidence": "pom.xml:45 → tomcat-catalina:9.0.85 (affected: >=9.0.0 <9.0.99)"
    },
    {
      "pointId": "DP-3",
      "type": "config_risk",
      "status": "RISKY",
      "evidence": "application.yml:12 → allow-partial-put: true"
    }
  ],
  "unmatchedPoints": ["DP-2"],
  "projectTriggerChain": [...],
  "recommendation": "升级 tomcat-catalina 到 9.0.99+，关闭 partial PUT"
}
```

**验证 PoC 特性**：

| 特性 | 说明 |
|------|------|
| 目标参数 | `--target` 必须手动指定测试环境地址，不内置默认值 |
| 行为 | 仅发送探测请求，不执行破坏性操作 |
| 判定 | 输出 `VULNERABLE` / `NOT_VULNERABLE` / `UNKNOWN` |
| 退出码 | 0=受影响 / 1=不受影响 / 2=检测失败 |
| 安全标注 | 首行注释标明仅限授权测试环境使用 |

#### API

```
POST /api/scan                        # 提交扫描任务
  body: { "cveId": "CVE-2025-24813", "projectPath": "/path/to/project" }

GET  /api/scan/{scanId}               # 获取扫描结果
GET  /api/scan?cveId={cveId}          # 按 CVE 查询扫描记录
GET  /api/scan/{scanId}/poc           # 下载验证 PoC
```

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

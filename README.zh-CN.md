# JVuln Platform

JVuln Platform 是一个 Java CVE 漏洞分析与安全教育平台。输入 CVE 编号后，平台会自动采集漏洞情报、定位修复补丁、分析 Java 代码变更、调用 LLM 推理漏洞根因和触发链，并生成本地教学用产物。

English documentation: [`README.md`](README.md)

## 架构概览

```
jvuln-platform/
├── backend/          # Spring Boot 2.7 (Java 8), 多模块 Maven
│   ├── jvuln-app         # 启动模块 + REST API
│   ├── jvuln-pipeline    # 5 阶段 Pipeline 引擎 + SSE 进度推送
│   ├── jvuln-collector   # Stage 1: 情报采集 (NVD/GHSA/OSV)
│   ├── jvuln-patcher     # Stage 2: 补丁定位 + Diff 解析
│   ├── jvuln-analyzer    # Stage 3: JavaParser 静态分析 + Diff 过滤
│   ├── jvuln-llm         # AI 抽象层 (Anthropic + OpenAI-compatible)
│   ├── jvuln-generator   # Stage 5: 产物生成
│   └── jvuln-store       # 存储层 (H2 + 文件系统 workspace)
├── frontend/         # Vue 3 + TypeScript + Vite + Element Plus
└── docs/             # 设计文档
```

**存储策略**：H2 存储任务、配置和状态索引；分析数据以 JSON 和文件形式存储在 `backend/workspace/CVE-xxx/`。

## 环境要求

| 组件 | 版本 |
|------|------|
| Java | 8+ |
| Maven | 3.8+ |
| Node.js | 18+ |
| LLM | Anthropic API 或任意 OpenAI 兼容端点 |

## 快速启动

### 1. 构建后端

```bash
cd backend
mvn install -DskipTests -q
```

### 2. 启动后端

```bash
# 必须从 backend/ 目录启动，因为 H2 数据库路径依赖当前工作目录。
cd backend
java -jar jvuln-app/target/jvuln-app-1.0.0-SNAPSHOT.jar
```

后端默认监听 `http://localhost:8080`。

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认监听 `http://localhost:5173`。

### 4. 配置 LLM

打开 `http://localhost:5173/settings`。设置页支持保存多个 LLM 配置，同一时间只有一个配置处于激活状态。

| 字段 | 说明 |
|------|------|
| Name | 可选显示名称，例如 `Claude Sonnet via proxy` |
| Provider | `anthropic`、`openai-compat`、`openai`、`deepseek` 或 `ollama` |
| Base URL | API Base URL，例如 `https://api.anthropic.com` 或本地代理地址 |
| API Key | 对应 Provider 的 API Key |
| Model | 模型名称，例如 `claude-sonnet-4-6` |

- **Activate / 激活**：将该配置设为 Pipeline 当前使用的 LLM。
- **Test / 测试**：发送轻量请求验证连通性和凭证有效性。

## 前端界面与本地化

前端使用 IBM Carbon 风格的深色主题：方正组件、高对比暗色表面、蓝色主操作、语义化状态颜色。

UI 支持中英文切换：

- 中文资源：`frontend/src/locales/zh-CN.ts`
- 英文资源：`frontend/src/locales/en-US.ts`
- i18n 辅助模块：`frontend/src/i18n/index.ts`

语言切换器位于页面 Header。选择结果会持久化到 `localStorage` 的 `jvuln-locale`。默认语言为中文。

## 使用流程

1. 访问 `http://localhost:5173`，点击 **新建分析**。
2. 输入 CVE 编号，例如 `CVE-2025-24813`。
3. 通过 SSE 实时查看 6 阶段 Pipeline 进度。
4. 分析完成后查看：
   - **概览 / 情报**：CVSS、CWE、受影响组件、源码仓库和参考信息
   - **补丁 Diff**：Carbon 风格的 `diff2html` 补丁视图，支持左右对比和统一视图
   - **代码分析**：JavaParser 方法分析和 CWE 模式匹配
   - **AI 推理**：触发链、根因、可利用性、修复评估和检测要点
   - **阶段日志**：每个阶段的状态和错误信息
5. 可选：运行 **产品漏洞检测**，检查你的 Java 项目是否受分析的漏洞影响。

## 6 阶段 Pipeline

| 阶段 | 名称 | 说明 |
|------|------|------|
| 1 | Intelligence Collection / 情报采集 | 从 NVD、GHSA、OSV、Maven 和参考链接采集 CVE 数据 |
| 2 | Patch Locating / 补丁定位 | 定位修复 commit 并提取 unified diff |
| 3 | Code Analysis / 代码分析 | 过滤相关 diff 文件，解析 Java AST，匹配 CWE 模式 |
| 4 | Vulnerability Reasoning / 漏洞推理 | 使用 LLM 推理触发链、根因、修复质量，并生成可机器执行的漏洞检测要点 |
| 5 | Vulnerability Education Lab / 漏洞教学演示 | 生成本地教学用复现项目、PoC、Docker Compose 环境和报告 |
| 6 | Product Vulnerability Detection / 产品漏洞检测 | 基于 Stage 4 检测要点扫描项目源码，生成项目特定触发链和验证 PoC |

Pipeline 支持断点续跑和指定阶段重跑。已完成阶段可从 workspace 文件缓存读取，`fromStage` 可强制从指定阶段重新执行。

## API 端点

```
POST /api/analysis                      # 提交新分析任务
GET  /api/analysis                      # 任务列表
GET  /api/analysis/{cveId}              # 任务详情 + 阶段状态
GET  /api/analysis/{cveId}/stream       # SSE 实时进度
POST /api/analysis/{cveId}/rerun        # 重跑 (?fromStage=N)
POST /api/analysis/{cveId}/sync-status  # 按 workspace 文件同步 DB 状态

GET  /api/analysis/{cveId}/intelligence
GET  /api/analysis/{cveId}/patch
GET  /api/analysis/{cveId}/diff         # 返回 {diff, totalFiles, shownFiles}
GET  /api/analysis/{cveId}/code-analysis
GET  /api/analysis/{cveId}/reasoning

POST /api/scan                          # 提交扫描任务 (cveId + projectPath)
GET  /api/scan/{scanId}                 # 获取扫描结果
GET  /api/scan?cveId={cveId}            # 按 CVE 查询扫描记录
GET  /api/scan/{scanId}/poc             # 下载验证 PoC

GET    /api/config/llm                  # 获取 LLM 配置列表
POST   /api/config/llm                  # 新建 LLM 配置
PUT    /api/config/llm/{id}             # 更新 LLM 配置
DELETE /api/config/llm/{id}             # 删除 LLM 配置
POST   /api/config/llm/{id}/activate    # 激活 LLM 配置
POST   /api/config/llm/{id}/test        # 测试 LLM 配置
```

## Workspace 结构

```
backend/workspace/CVE-xxxx-xxxxx/
├── stages/
│   ├── 1_intelligence.json
│   ├── 2_patch.json
│   ├── 3_analysis.json
│   ├── 4_reasoning.json
│   └── 5_artifacts.json
├── patches/
│   ├── fix.diff
│   └── source/{before,after}/
├── vuln-demo/        # 生成的 Spring Boot 漏洞复现项目
├── poc/
├── report/
└── scan-{projectName}/   # Stage 6 产品扫描结果
    ├── scan-result.json
    ├── trigger-chain.json
    ├── verify-exploit.py
    └── remediation.md
```

## 已验证 CVE

| CVE | 组件 | CVSS | 类型 |
|-----|------|------|------|
| CVE-2025-24813 | Apache Tomcat | 9.8 | Partial PUT RCE (CWE-44 + CWE-502) |
| CVE-2023-25330 | MyBatis-Plus | 9.8 | SQL Injection (TenantLine/DataPermission) |
| CVE-2022-22965 | Spring Framework | 9.8 | Spring4Shell RCE |
| CVE-2023-3276 | — | — | — |
| CVE-2016-1000027 | Spring Web | — | — |

## 注意事项

- 本平台仅用于本地安全教育和授权研究。
- 生成的 Demo 和 PoC 只应在本地受控环境运行。
- AI 推理需要能访问 Anthropic 或兼容 API 端点的网络环境。
- 如果使用本地中转或代理，建议将请求超时时间设为足够高，推理任务最好不少于 300 秒。

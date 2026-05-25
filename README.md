# JVuln Platform

Java CVE 漏洞分析与安全教育平台。输入 CVE 编号，自动完成情报采集、补丁定位、代码分析、AI 推理，生成可复现的漏洞演示项目和完整分析报告。

## 架构概览

```
jvuln-platform/
├── backend/          # Spring Boot 2.7 (Java 8), 多模块 Maven
│   ├── jvuln-app         # 启动模块 + REST API
│   ├── jvuln-pipeline    # 5 阶段 Pipeline 引擎 + SSE 推送
│   ├── jvuln-collector   # Stage 1: 情报采集 (NVD/GHSA/OSV)
│   ├── jvuln-patcher     # Stage 2: 补丁定位 + Diff 解析
│   ├── jvuln-analyzer    # Stage 3: JavaParser 代码静态分析 + Diff 过滤
│   ├── jvuln-llm         # AI 抽象层 (Anthropic + OpenAI-compat)
│   ├── jvuln-generator   # Stage 5: 产物生成
│   └── jvuln-store       # 存储层 (H2 + 文件系统)
└── frontend/         # Vue 3 + TypeScript + Vite
```

**存储策略**：H2 嵌入式数据库管理任务状态索引，分析数据以 JSON 文件存储在 `workspace/CVE-xxx/stages/`。

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
# 必须在 backend/ 目录下启动，H2 数据库路径依赖 CWD
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

打开 `http://localhost:5173/settings`，可保存多个 LLM 配置并指定哪一个为激活状态：

| 字段 | 说明 |
|------|------|
| Name | 配置名称（如 "Claude Sonnet via new-api"） |
| Provider | `anthropic`（原生）或 `openai-compat`（OpenAI 兼容） |
| Base URL | 如 `https://api.anthropic.com` 或本地代理地址 |
| API Key | 对应服务的 API Key |
| Model | 如 `claude-sonnet-4-6` |

- **Activate**：将该配置设为当前生效配置（同一时刻只有一个激活）
- **Test**：发送轻量请求验证连通性和 API Key 有效性

## 使用流程

1. 访问 `http://localhost:5173`，点击 **New Analysis**
2. 输入 CVE 编号（如 `CVE-2025-24813`），提交
3. 实时查看 5 个阶段的执行进度（SSE 推送）
4. 分析完成后查看：
   - **Intelligence**：漏洞情报（CVSS、影响版本、修复提交）
   - **Patch Diff**：修复补丁对比视图（仅展示 Stage 3 确认相关的文件）
   - **Code Analysis**：JavaParser 静态分析 + CWE 模式匹配
   - **AI Reasoning**：触发链、漏洞根因、修复评估
   - **Artifacts**：生成的演示项目和 PoC

## 5 阶段 Pipeline

| Stage | 名称 | 说明 |
|-------|------|------|
| 1 | Intelligence Collection | 从 NVD/GHSA/OSV 采集 CVE 情报 |
| 2 | Patch Locating | 定位修复 commit，提取 unified diff |
| 3 | Code Analysis | Diff 相关性过滤 + JavaParser AST 分析 + CWE 模式匹配 |
| 4 | Vulnerability Reasoning | AI 推理触发链、根因、修复完整性 |
| 5 | Artifact Generation | 生成漏洞复现项目和报告 |

Pipeline 支持断点续跑：已完成阶段从文件缓存读取，可通过 `fromStage` 参数从指定阶段重跑。

## API 端点

```
POST /api/analysis                      # 提交新分析任务
GET  /api/analysis                      # 任务列表
GET  /api/analysis/{cveId}              # 任务详情 + 阶段状态
GET  /api/analysis/{cveId}/stream       # SSE 实时进度
POST /api/analysis/{cveId}/rerun        # 重跑 (?fromStage=N)
POST /api/analysis/{cveId}/sync-status  # 按磁盘文件同步 DB 状态

GET  /api/analysis/{cveId}/intelligence
GET  /api/analysis/{cveId}/patch
GET  /api/analysis/{cveId}/diff         # 返回 {diff, totalFiles, shownFiles}
GET  /api/analysis/{cveId}/code-analysis
GET  /api/analysis/{cveId}/reasoning

GET  /api/config/llm                    # 获取所有 LLM 配置列表
POST /api/config/llm                    # 新建 LLM 配置
PUT  /api/config/llm/{id}              # 更新指定配置
DELETE /api/config/llm/{id}            # 删除指定配置
POST /api/config/llm/{id}/activate     # 激活指定配置（自动去激活其他）
POST /api/config/llm/{id}/test         # 测试指定配置的连通性
```

## 工作区结构

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
├── vuln-demo/        # 生成的 Spring Boot 复现项目
├── poc/
└── report/
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

- 本平台仅用于安全教育和研究，所有漏洞演示限于本地环境
- AI 推理需要可访问 Anthropic 或兼容 API 的网络环境
- 如通过本地代理（如 new-api）转发，建议将代理的 relay timeout 设为 ≥ 300s

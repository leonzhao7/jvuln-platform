# JVuln Platform 后端 API

本文档整理 `backend/jvuln-app` 当前实现的 HTTP API。默认服务地址为
`http://localhost:8080`。

## 通用约定

### 认证与权限

- 所有 `/api/**` 请求需要 HTTP Basic Authentication。
- `/api/analysis/**` 需要 `USER` 角色；`/api/config/**` 需要 `ADMIN` 角色。
- 默认用户名为 `admin`，密码由 `ADMIN_PASSWORD` 环境变量提供；未设置时为
  `changeme`（仅适用于开发环境）。
- 示例请求中的 `${AUTH}` 表示 `-u admin:<密码>`。

### 请求与响应

- JSON 请求使用 `Content-Type: application/json`，响应默认也是 JSON。
- 时间字段使用 ISO-8601 字符串（Jackson 已关闭时间戳序列化）。
- 成功的异步启动接口通常返回 `202 Accepted`。
- 统一业务错误体为：

  ```json
  { "error": "错误说明" }
  ```

  Spring Security、参数绑定与校验失败等框架错误可能使用 Spring Boot 的标准错误体。

- 常见状态码：
  - `200 OK`：读取或操作成功
  - `202 Accepted`：任务已接受并在后台执行
  - `204 No Content`：删除成功
  - `400 Bad Request`：参数或状态不合法
  - `401/403`：未认证或无相应角色
  - `404 Not Found`：任务、配置或工作区数据不存在
  - `409 Conflict`：重复创建或同一 CVE 正在执行
  - `500 Internal Server Error`：读取工作区或后端处理失败

### CVE 与阶段

- 创建任务时，`cveId` 必须匹配 `CVE-\d{4}-\d{4,}`，例如
  `CVE-2024-1234`。
- 分析流水线有 5 个阶段：

  | 阶段 | 名称 |
  | --- | --- |
  | 1 | Intelligence Collection |
  | 2 | Patch Analysis |
  | 3 | AI Reasoning |
  | 4 | Artifacts Generation |
  | 5 | Report Generation |

- `CveTask.status`：`PENDING`、`RUNNING`、`COMPLETED`、`FAILED`。
- `StageRecord.status`：`PENDING`、`RUNNING`、`COMPLETED`、`FAILED`、`SKIPPED`。

## 分析 API

基础路径：`/api/analysis`

### 创建分析任务

`POST /api/analysis`

请求体：

```json
{
  "cveId": "CVE-2024-1234",
  "fromStage": "1"
}
```

- `cveId` 必填。
- `fromStage` 可选，字符串形式的整数，默认 `1`；用于指定开始执行的阶段。
- CVE 已存在返回 `409`；格式错误或 `fromStage` 不是整数返回 `400`。

成功响应（`202`）：

```json
{ "cveId": "CVE-2024-1234", "status": "PENDING" }
```

示例：

```bash
curl -u admin:changeme -X POST http://localhost:8080/api/analysis \
  -H 'Content-Type: application/json' \
  -d '{"cveId":"CVE-2024-1234","fromStage":"1"}'
```

### 列出分析任务

`GET /api/analysis`

返回 `CveTask[]`。每个任务包含以下字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | number | 数据库 ID |
| `cveId` | string | CVE 编号 |
| `status` | enum | 任务状态 |
| `currentStage` | number | 当前或最近阶段（0 表示尚未开始） |
| `artifact` | string/null | 受影响制品信息（若已提取） |
| `cvssScore` | number/null | CVSS 分数 |
| `cweId` | string/null | CWE 编号 |
| `description` | string/null | 漏洞描述 |
| `workspacePath` | string/null | 工作区相对路径 |
| `createdAt` / `updatedAt` | string | ISO-8601 时间 |

### 获取任务详情

`GET /api/analysis/{cveId}`

成功响应（`200`）：

```json
{
  "task": { "id": 1, "cveId": "CVE-2024-1234", "status": "RUNNING" },
  "stages": [
    {
      "id": 1,
      "cveId": "CVE-2024-1234",
      "stageNum": 1,
      "stageName": "Intelligence Collection",
      "status": "COMPLETED",
      "startedAt": "2026-08-28T10:00:00",
      "finishedAt": "2026-08-28T10:01:00",
      "errorMsg": null
    }
  ]
}
```

任务不存在返回 `404`。

### 订阅流水线进度（SSE）

`GET /api/analysis/{cveId}/stream`

响应为 `text/event-stream`。连接最长保持 30 分钟；建立连接时会先回放当前
任务已经产生的事件，任务结束后服务端完成连接。接口不单独校验任务是否存在；目标任务
未运行且没有进度历史时，连接会立即结束。

事件名及 `data` JSON 字段：

| 事件名 | 含义 |
| --- | --- |
| `stage_start` | 阶段开始，`stageNum` 为 1-5 |
| `progress` | 阶段内部进度 |
| `stage_done` | 阶段完成或失败 |
| `error` | 流水线或阶段异常 |
| `pipeline_done` | 全部完成，或用户取消（此时 message 为 `Cancelled by user`） |

所有事件的数据结构均为：

```json
{
  "type": "stage_start",
  "stageNum": 1,
  "message": "Intelligence Collection",
  "timestamp": 1787901600000
}
```

`timestamp` 是 Unix epoch 毫秒。命令行示例：

```bash
curl -N -u admin:changeme \
  http://localhost:8080/api/analysis/CVE-2024-1234/stream
```

完成同源的 Basic 认证后，浏览器可使用：

```js
const es = new EventSource('/api/analysis/CVE-2024-1234/stream')
es.addEventListener('stage_done', event => console.log(JSON.parse(event.data)))
es.addEventListener('pipeline_done', () => es.close())
```

### 重跑分析

`POST /api/analysis/{cveId}/rerun?fromStage=1`

- `fromStage` 查询参数可选，默认 `1`。
- 可选 JSON 请求体 `{ "hint": "补充说明" }`，提示会传递给流水线。
- 任务不存在返回 `404`；任务正在运行返回 `409`。

成功响应（`202`）：

```json
{ "cveId": "CVE-2024-1234", "fromStage": 3 }
```

### 取消正在运行的任务

`POST /api/analysis/{cveId}/cancel`

任务不存在返回 `404`；任务不是运行状态或无法发出取消信号返回 `400`。

成功响应（`200`）：

```json
{ "cveId": "CVE-2024-1234", "cancelled": true }
```

取消会在当前阶段检查点生效，任务最终状态记为 `FAILED`，SSE 会收到
`pipeline_done` 事件。

### 上传并验证 vuln-demo

`POST /api/analysis/{cveId}/upload-vulndemo`

使用 `multipart/form-data` 上传字段 `file`，文件必须是 ZIP，最大请求和文件大小
均为 100 MB：

```bash
curl -u admin:changeme -X POST \
  http://localhost:8080/api/analysis/CVE-2024-1234/upload-vulndemo \
  -F 'file=@vuln-demo.zip'
```

ZIP 会解压到该 CVE 工作区，替换 `vuln-demo/` 与 `poc/`，然后依次执行编译、启动、
PoC 验证，成功后继续执行第 5 阶段。压缩包根目录必须包含 `vuln-demo/`；路径穿越条目
会被拒绝。任务不存在返回 `404`，空文件返回 `400`，任务运行中返回 `409`。

成功响应（`202`）：

```json
{ "cveId": "CVE-2024-1234" }
```

### 获取阶段数据

以下接口均返回对应阶段 JSON 文件内容（结构随阶段输出版本变化，服务端不再包装）：

| 方法 | 路径 | 阶段 |
| --- | --- | --- |
| `GET` | `/api/analysis/{cveId}/intelligence` | 1 |
| `GET` | `/api/analysis/{cveId}/patch` | 2 |
| `GET` | `/api/analysis/{cveId}/reasoning` | 3 |
| `GET` | `/api/analysis/{cveId}/artifacts` | 4 |
| `GET` | `/api/analysis/{cveId}/code-analysis` | 3（兼容别名） |
| `GET` | `/api/analysis/{cveId}/stages/{stageNum}/json` | 指定 1-5 |

`stageNum` 不在 1-5 范围返回 `400`；文件不存在返回 `404`；读取失败返回 `500`。
阶段 4 的 `vulnDemo` 对象会补充派生的 `compileStatus` 与 `startupStatus`（当原文件
未提供时）。

### 获取补丁 diff

`GET /api/analysis/{cveId}/diff`

成功响应：

```json
{
  "diff": "diff --git a/src/A.java b/src/A.java\\n...",
  "totalFiles": 4,
  "shownFiles": 2
}
```

当阶段 3 已产生 `analyzedFiles` 时，只返回相关文件；否则返回完整
`patches/fix.diff`。diff 不存在返回 `404`。

### 获取单个制品文件

`GET /api/analysis/{cveId}/artifacts/file?path=vuln-demo/src/Main.java`

`path` 必填，必须是阶段 4 JSON 中声明的制品路径，且不能进行路径穿越。

成功响应：

```json
{ "path": "vuln-demo/src/Main.java", "content": "package demo;\\n..." }
```

未声明或包含非法路径返回 `400`；文件不存在返回 `404`。

### 下载全部制品

`GET /api/analysis/{cveId}/artifacts/download`

返回 `application/octet-stream` ZIP，响应头包含
`Content-Disposition: attachment; filename="{cveId}-artifacts.zip"`。只打包阶段 4
声明且位于工作区内的文件。阶段 4 数据不存在时返回空 ZIP；读取失败返回 `500`。

### 获取报告

`GET /api/analysis/{cveId}/report`

成功响应：

```json
{ "markdown": "# 漏洞分析报告\\n..." }
```

报告文件不存在返回 `404`。

### 获取流水线日志

`GET /api/analysis/{cveId}/pipeline-log`

任务运行中返回内存中的最新 `StageProgress[]`；任务结束后读取工作区中的日志文件。
不存在返回 `404`。

### 获取对话记录

`GET /api/analysis/{cveId}/transcript`

返回阶段 4 transcript 中可见事件组成的数组（`assistant`、`directive`、
`tool_results`、`compact`）。没有 transcript 时返回空数组 `[]`，而不是 `404`。

### 同步任务状态

`POST /api/analysis/{cveId}/sync-status`

扫描工作区阶段文件并更新数据库中的阶段与任务状态。任务运行中返回 `409`，任务不存在
返回 `404`。

成功响应：

```json
{
  "cveId": "CVE-2024-1234",
  "syncedToStage": 5,
  "currentStage": 5,
  "status": "COMPLETED"
}
```

### 删除分析任务

`DELETE /api/analysis/{cveId}`

删除数据库中的任务、阶段记录、CVE 工作区及进度缓存。任务不存在时幂等返回 `204`；
任务仍在运行返回 `409`。

## 配置 API

基础路径：`/api/config`。所有接口需要 `ADMIN` 角色。

### LLM 配置

#### 列出配置

`GET /api/config/llm`

返回 `LlmConfig[]`。响应中的 `apiKey` 始终脱敏为 `••••••••`（未设置时为空字符串）。

#### 创建配置

`POST /api/config/llm`

请求体字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string | 配置名称 |
| `baseUrl` | string | 服务基础 URL |
| `apiKey` | string | API Key；不会在响应中明文返回 |
| `model` | string | 模型名称 |
| `endpoint` | string | 仅支持 `/v1/chat/completions`、`/v1/responses`、`/v1/messages` |
| `userAgent` | string | 可选 User-Agent |

示例：

```json
{
  "name": "本地 Ollama",
  "baseUrl": "http://localhost:11434/v1",
  "apiKey": "",
  "model": "deepseek-coder",
  "endpoint": "/v1/chat/completions",
  "userAgent": "JVuln-Platform/1.0"
}
```

新配置默认 `active=false`。成功返回 `200` 及脱敏后的 `LlmConfig`；不支持的 endpoint
会抛出参数错误。

#### 更新配置

`PUT /api/config/llm/{id}`

请求体字段同创建。提交 `apiKey: "••••••••"` 表示保留原 API Key，不会覆盖。配置不存在
返回 `404`，成功返回脱敏后的配置。

#### 删除配置

`DELETE /api/config/llm/{id}`

配置不存在返回 `404`；成功返回 `204`。

#### 激活配置

`POST /api/config/llm/{id}/activate`

配置不存在返回 `404`。成功时先将所有配置设为非激活，再激活目标配置，返回脱敏后的
配置（`200`）。

#### 测试配置连通性

`POST /api/config/llm/{id}/test`

不需要请求体。接口使用指定配置发送诊断 prompt，始终以 `200` 返回测试结果：

成功：

```json
{
  "ok": true,
  "model": "deepseek-coder",
  "response": "PONG",
  "tokens": "12/1"
}
```

失败：

```json
{ "ok": false, "error": "Base URL is not configured" }
```

#### LLM 配置字段响应

`LlmConfig` 还包含 `id`（number）与 `active`（boolean）字段；服务端不会返回明文 API Key。

### Java Profile

#### 列出 Profile

`GET /api/config/java-profiles`

返回 `JavaProfile[]`，字段为 `id`、`name`、`javaVersion`、`javaHome`、
`springBootVersion`、`mavenJavaVersion`、`syntaxConstraints`、`isDefault`。

#### 创建 Profile

`POST /api/config/java-profiles`

请求体示例：

```json
{
  "name": "JDK 17",
  "javaVersion": "17",
  "javaHome": "/opt/java/jdk-17",
  "springBootVersion": "2.7.18",
  "mavenJavaVersion": "17",
  "syntaxConstraints": "Java 8 compatible"
}
```

`javaHome` 必填，长度不超过 500，且必须是绝对路径；目录必须存在并包含 `bin/java` 或
`bin/javac`，同时必须位于允许的安装目录（如 `/usr/lib/jvm/`、`/usr/local/`、`/opt/`、
`/Library/Java/`）。路径只允许字母、数字、`/`、`_`、`.`、`-`。新建 Profile 默认
`isDefault=false`。

#### 更新 Profile

`PUT /api/config/java-profiles/{id}`

请求体同创建，并执行相同的 `javaHome` 校验。不存在返回 `404`，成功返回更新后的对象。

#### 删除 Profile

`DELETE /api/config/java-profiles/{id}`

不存在返回 `404`；成功返回 `204`。

#### 设置默认 Profile

`POST /api/config/java-profiles/{id}/set-default`

清除其他 Profile 的默认标记并将目标设为默认。不存在返回 `404`，成功返回 Profile。

### 代理设置

#### 获取代理设置

`GET /api/config/proxy`

返回 `ProxySettings`：

| 字段 | 类型 | 默认值/说明 |
| --- | --- | --- |
| `id` | number/null | 数据库 ID；尚未持久化的默认配置为 null |
| `proxyType` | string | `SOCKS5`、`SOCKS4`、`HTTP` 或 `NONE`，默认 `NONE` |
| `proxyHost` | string/null | 代理主机 |
| `proxyPort` | number/null | 代理端口 |
| `proxyScope` | string | `llm`、`url`、`all`，或逗号/空格分隔的组合；默认 `url` |
| `urlConnectTimeout` | number | 默认 5000 ms |
| `urlReadTimeout` | number | 默认 8000 ms |
| `llmTimeout` | number | 默认 300000 ms |

#### 更新代理设置

`PUT /api/config/proxy`

请求体为 `ProxySettings`（通常省略 `id`）。若数据库已有记录，会沿用已有 ID。成功返回
保存后的对象。

#### 测试代理设置

`POST /api/config/proxy/test`

不需要请求体。当前实现只检查代理类型、主机和端口是否有效，不会发起真实的网络连接；
接口始终以 `200` 返回：

```json
{ "ok": true, "message": "代理配置有效" }
```

代理类型为 `NONE`、主机为空或端口小于等于 0 时：

```json
{ "ok": false, "error": "代理未启用" }
```

## CORS 与限制

- `/api/**` 允许的前端来源仅为 `http://localhost:5173`，允许凭据及 `GET`、`POST`、
  `PUT`、`DELETE`、`OPTIONS` 方法。
- 单个上传文件和整个 multipart 请求最大均为 100 MB。
- API Key 只用于后端调用 LLM；配置读取、创建、更新和激活响应均会脱敏。

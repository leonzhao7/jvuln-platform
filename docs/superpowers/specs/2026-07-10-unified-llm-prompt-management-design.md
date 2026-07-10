# 统一 LLM 提示词与协议调用设计

**日期：** 2026-07-10  
**模块：** `jvuln-utils` LLM 客户端、`jvuln-stages` 提示词资源、LLM 配置接口与设置页

## 目标

LLM 请求只保留三种语义输入，不再保留 `current` 提示词层级：

1. `global`：每次 LLM 请求都生效，由 `prompts/global.md` 提供。
2. `stage`：只在所属的四个流水线 Stage 生效，由 `prompts/stages/*.md` 提供。
3. `task`：由当前调用通过 `LlmRequest.taskPrompt` 传入，只属于本次任务请求。

客户端根据配置中显式选择的 endpoint，使用对应协议表达这三种输入并解析响应。endpoint 是协议选择的唯一依据，不再通过 `providerType` 或模型名称推断协议。

## 提示词资源与 Stage

Stage 仍按四个流水线维度划分：

| `LlmPromptStage` | 流水线阶段 | 资源文件 |
| --- | --- | --- |
| `INTELLIGENCE` | 情报采集 | `prompts/stages/intelligence.md` |
| `PATCH_ANALYSIS` | 补丁分析 | `prompts/stages/patch-analysis.md` |
| `REASONING` | 漏洞推理 | `prompts/stages/reasoning.md` |
| `ARTIFACT_GENERATION` | 制品生成 | `prompts/stages/artifact-generation.md` |

`PromptManager` 分别读取并缓存 global 和 stage 文本，不再返回拼接后的单一 system prompt。资源通过运行时 classpath 加载，不依赖工作目录。

现有 `jvuln-stages/src/main/resources/prompts/current/*.md` 文件全部保留，后续另行整理。本次只移除 Java API 中 `currentSystemPrompt`、`systemPrompt` 和 `withResolvedSystemPrompt` 等 current 层概念；现有调用点暂时仍可通过 `PromptRegistry` 读取这些文件，但读出的内容作为 `taskPrompt` 传入。

## 请求模型

`LlmRequest` 保存：

- `stage`：流水线请求必须提供；配置连通性测试等非流水线诊断请求可以为空。
- `taskPrompt`：当前调用的任务提示词，作为协议中的首个 user 输入。
- `messages`：任务提示词之后的用户数据、助手历史和工具调用历史。
- 温度、最大输出 token、JSON 模式、工具定义和 tool choice 等请求选项。

`taskPrompt` 不写入共享状态，也不会在下一次请求中复用。`OpenAiCompatClient` 在每次同步或流式调用前，从 `PromptManager` 取得 global 与当前 stage 文本，构造内部不可变的 `PromptContext(globalPrompt, stagePrompt)`，再用 `LlmCall(request, promptContext)` 交给 Caller。公开的 `LlmRequest` 不暴露已解析提示词的 setter、复制方法或可变字段。若流水线请求的 stage 为空、global 文件缺失或对应 stage 文件缺失，请求发送前失败。非流水线诊断请求只使用 global 与 task。

## Endpoint 配置

LLM 配置增加必填的 `endpoint` 字段，只允许以下三个规范值：

- `/v1/chat/completions`
- `/v1/responses`
- `/v1/messages`

设置页使用下拉选择并在配置列表中显示 endpoint。创建、更新、激活、测试和运行时取配置时都校验该字段；未知值直接报错。已有数据库记录可继续读取和编辑，但在补齐 endpoint 前不能激活、测试或用于 LLM 请求。

`baseUrl` 仍表示服务地址，可以是服务根地址、以 `/v1` 结尾的兼容地址，或包含某个完整已知 endpoint。客户端先移除末尾的 `/v1` 或已知 endpoint，再追加配置选择的 endpoint，生成唯一的最终请求 URI。这样配置决定协议，同时兼容现有 `https://host/v1` 形式和带自定义前缀的代理地址。

应用级 fallback 配置增加 `jvuln.llm.endpoint`，默认值为 `/v1/chat/completions`。数据库中存在 active 配置但 endpoint 缺失或非法时必须失败，不能退回 fallback 或根据模型猜测。

## Caller 架构

原 `LlmCaller` 重命名为 `ChatCaller`，原 `AnthropicCaller` 重命名为 `MessagesCaller`，并新增 `ResponsesCaller`。三者实现同一个内部 Caller 接口，接收已解析的 `LlmCall` 并提供：

- `LlmResponse chat(LlmCall call)`：发送非流式请求并完整解析文本、token、结束原因和工具调用。
- `Flux<String> chatStream(LlmCall call)`：发送流式请求并输出文本增量。

Caller 工厂只按 `endpoint` 创建实现：

| Endpoint | Caller |
| --- | --- |
| `/v1/chat/completions` | `ChatCaller` |
| `/v1/responses` | `ResponsesCaller` |
| `/v1/messages` | `MessagesCaller` |

`OpenAiCompatClient` 是生产调用的统一入口，并提供接收显式 `ActiveConfig` 的内部调用入口供配置连通性测试使用。该入口与正常调用复用相同的 PromptManager、`LlmCall` 构造、Caller 工厂和 endpoint 路由，避免形成另一套协议判断逻辑。`providerType` 继续作为配置展示信息，但不参与请求协议选择。

## 三种协议的提示词映射

### Chat Completions

`ChatCaller` 按以下顺序构造 `messages`：

1. global -> `{"role":"system","content":...}`
2. stage -> `{"role":"developer","content":...}`
3. task -> `{"role":"user","content":...}`
4. `LlmRequest.messages` 中的后续对话和工具历史

Chat Completions 没有顶层 `instructions`，因此 global 使用其协议等价的 `system` role。若某个 OpenAI-compatible 服务不支持 `developer` role，由服务返回明确错误；客户端不静默降级为 system 或 user，以免改变提示词优先级。

### Responses

`ResponsesCaller` 使用 OpenAI Responses 格式：

- global -> 顶层 `instructions`
- stage -> `input` 中 role 为 `developer`、content type 为 `input_text` 的 message item
- task -> `input` 中 role 为 `user`、content type 为 `input_text` 的 message item
- 历史文本、函数调用和函数结果 -> 后续 `input` items

工具定义使用 Responses 的扁平 function tool 格式，而不是 Chat Completions 的 `tools[].function` 包装。非流式响应从 `output` items 读取 `output_text` 与 `function_call`；流式响应按 `response.output_text.delta`、`response.function_call_arguments.*`、`response.completed`、`response.failed` 等事件类型处理，不把 SSE 当成 Chat chunk 解析。

### Messages

Anthropic Messages 没有 `developer` role。`MessagesCaller` 使用协议原生的顶层 `system` content 数组，依次放入两个独立 text block：global、stage；task 作为第一条 user message，随后追加 `LlmRequest.messages`。

若 task 与后续历史形成连续 user message，Caller 将它们合并为同一 user message 的多个 content block，以满足 Messages 的角色交替约束，但不会把 task 合并进 system。工具定义使用 `input_schema`，工具调用和结果使用 `tool_use` / `tool_result` content block。

## 统一响应语义

三种 Caller 都归一化为现有 `LlmResponse`：

- `content`：所有文本块按协议顺序拼接。
- `promptTokens` / `completionTokens`：分别映射 Chat 的 `prompt_tokens` / `completion_tokens`、Responses 的 `input_tokens` / `output_tokens`、Messages 的 `input_tokens` / `output_tokens`。
- `model`：优先使用响应模型，缺失时使用配置模型。
- `finishReason`：Chat 和 Messages 使用协议返回值；Responses 将 completed 映射为 `stop`，存在函数调用时映射为 `tool_calls`，incomplete 使用 `incomplete_details.reason`，failed 映射为 `error`。
- `contentBlocks`：文本归一化为 `text`，三种协议的函数调用统一为 `tool_use`，保留调用 id、函数名和 JSON 参数。

流式 `chatStream` 延续 `Flux<String>` 合约，只输出文本 delta；需要工具调用结果的 Agent 使用非流式 `chat`。同步请求若收到与配置协议不符的响应格式，应抛出包含 endpoint 和截断响应内容的解析错误，不跨协议猜测。

## 错误处理

- endpoint 缺失或不在白名单中：发送前抛出 `IllegalArgumentException`。
- global/stage 资源缺失：抛出包含资源路径和 stage 的 `IllegalStateException`。
- HTTP 非 2xx：错误包含状态码、endpoint 和截断后的响应体，但不记录 API key。
- SSE `error`、`response.failed` 或 Messages error event：终止流并返回明确异常。
- 无文本但包含合法工具调用的响应是成功响应；文本和工具调用均为空才视为非法空响应。
- 不因模型名前缀、provider 类型或单次 4xx 自动切换 endpoint、role 或请求格式。

## 测试策略

1. `PromptManager`：验证 global/stage 独立读取、缓存、缺失资源和诊断请求无 stage。
2. `LlmRequest`：验证 task 命名、不可变复制以及消息、工具、选项完整保留，并确认 current API 已删除。
3. Endpoint 配置与工厂：验证三个白名单值、URI 规范化、每个 endpoint 的 Caller 选择，以及非法/缺失 endpoint 快速失败。
4. `ChatCaller`：用本地 mock HTTP 服务断言 system/developer/user 顺序、工具请求格式、普通 JSON 响应和 Chat SSE。
5. `ResponsesCaller`：断言 `instructions` 与 typed input items、扁平工具格式、`output` 解析、函数调用聚合和 typed SSE。
6. `MessagesCaller`：断言 system content blocks、user 合并、Anthropic headers、工具格式、普通响应和 Messages SSE。
7. `OpenAiCompatClient`：验证同步与流式请求都按 active endpoint 路由，连续请求的 task 不泄漏，非法 active 配置不回退。
8. 配置接口和前端：验证 endpoint 的保存、脱敏复制、测试请求路由，以及设置页类型、选择器、列表显示和必填校验。
9. 运行 `mvn test` 与前端构建，确认现有 Stage 调用全部迁移到 task 语义且三个协议均可编译运行。

## 非目标

- 本次不重写或删除 `prompts/current/*.md` 内容，也不重新划分每个子步骤的提示词文件。
- 不提供提示词热更新、数据库编辑、版本管理或额外提示词层级。
- 不实现 Chat、Responses、Messages 之外的 endpoint。
- 不复刻 Codex CLI 的工具集、client metadata、reasoning 配置或会话管理；只采用相应协议的官方请求结构。

# 统一 LLM 提示词管理设计

**日期：** 2026-07-10  
**模块：** `jvuln-utils` LLM 客户端、`jvuln-stages` 提示词资源

## 目标

为所有 LLM 请求提供统一的系统提示词合成机制。系统提示词固定分为三层：

1. `global`：每一次 LLM 请求都生效。
2. `stage`：只在所属的四个流水线 Stage 内生效。
3. `current`：只在当前 `LlmRequest` 生效；请求完成后不保留。

所有生产环境提示词文件均为 Markdown，存放在 `jvuln-stages` 的资源目录。现有 LLM 调用点传入的系统提示词全部改为 `current` 级别；调用点不读取、不拼接 global 或 stage 提示词。

## 范围与命名

Stage 提示词按四个流水线维度划分，而不是按内部 Agent 或子步骤划分：

| LLM Stage 枚举 | 流水线阶段 | 资源文件 |
| --- | --- | --- |
| `INTELLIGENCE` | 情报采集 | `prompts/stages/intelligence.md` |
| `PATCH_ANALYSIS` | 补丁分析 | `prompts/stages/patch-analysis.md` |
| `REASONING` | 漏洞推理 | `prompts/stages/reasoning.md` |
| `ARTIFACT_GENERATION` | 制品生成 | `prompts/stages/artifact-generation.md` |

全局文件固定为 `prompts/global.md`。内部 Agent 或子步骤的专有说明继续在调用点构造，但作为该请求的 `current` 提示词传入。

## 架构

在 `jvuln-utils` 增加以下职责明确的组件：

- `LlmPromptStage`：四个受支持流水线阶段的枚举，并提供稳定的资源名。
- `PromptManager`：从 classpath 读取并缓存 Markdown 文件，根据阶段与 current 内容生成最终系统提示词。
- `LlmRequest`：保存 `stage` 与 `currentSystemPrompt`，并提供复制方法以写入已解析的最终系统提示词；请求及其副本均不可变。
- `OpenAiCompatClient`：`chat` 与 `chatStream` 的唯一统一入口。在委托 Adapter 前使用 `PromptManager` 解析请求，Adapter 和具体 Provider 无需感知提示词层级。

资源虽由 `jvuln-stages` 打包，但在完整应用运行时位于 classpath，因此 `jvuln-utils` 的 `PromptManager` 可通过 Spring 的资源加载机制读取它们。资源路径不能依赖当前工作目录或文件系统绝对路径。

## 数据流

```text
Stage 内的 LLM 子步骤
  └─ LlmRequest(stage, currentSystemPrompt, messages, options)
       └─ OpenAiCompatClient
            └─ PromptManager.resolve(stage, current)
                 = global.md + stage.md + current
            └─ Adapter / Provider
                 └─ 使用合成后的 system prompt 发起本次请求
```

三段非空内容使用两个换行符分隔，顺序严格为 `global → stage → current`。空白 current 不产生额外分隔符。每次调用都根据该请求独立合成；不使用 `ThreadLocal`、会话状态或可变的“当前 Stage”，以保证并发、重试和流式调用不会发生提示词串用。

## 调用点迁移

所有现有 `LlmRequest` 工厂方法与直接构造调用必须显式传入所属的 `LlmPromptStage`。其原本传入的 `systemPrompt` 改为 `currentSystemPrompt`：

- 情报采集相关 LLM 调用使用 `INTELLIGENCE`。
- 补丁分析相关 LLM 调用使用 `PATCH_ANALYSIS`。
- `ReasoningStage` 使用 `REASONING`。
- `ArtifactGenStage` 及其 Agent、评审、验证、画像等内部子步骤使用 `ARTIFACT_GENERATION`。

既有 `PromptRegistry` 及 app 模块下的 `.txt` 提示词将不再用于生产调用；相应内容迁移为 `jvuln-stages` 下的 `.md` 文件。若某段旧提示词本质上是某个调用独有的任务说明，应保留在该调用点作为 current，而不能放入 stage 文件。

## 错误处理与兼容性

- `stage` 为空、global 文件缺失或对应 stage 文件缺失时，在请求发送前抛出带资源路径和 stage 名称的 `IllegalStateException`；不得静默跳过层级。
- `currentSystemPrompt` 可为空或仅空白，此时仅合成 global 与 stage。
- 现有温度、token 上限、JSON 模式、消息、工具定义和工具选择必须在请求副本中完整保留。
- 不改变 `LlmAdapter`、Provider Caller 及其请求协议；它们只接收最终的 `systemPrompt`。
- 旧的不带 stage 的构造入口在迁移完成后删除或改为失败，防止以后绕开统一管理。

## 测试策略

1. 在 `jvuln-utils` 为 `PromptManager` 编写单元测试，验证 global/stage/current 的顺序、空 current、资源缺失和缓存后的稳定结果。
2. 为 `LlmRequest` 编写单元测试，验证解析后副本保留消息、模型选项和工具调用字段。
3. 为 `OpenAiCompatClient` 编写行为测试，验证同步与流式请求在交给 Adapter 前均包含三层提示词，且两个连续请求的 current 不互相泄漏。
4. 在 `jvuln-stages` 编写资源集成测试，验证四个 Markdown 文件均可从运行时 classpath 读取。
5. 执行 `mvn test -pl jvuln-utils,jvuln-stages -am`，并通过全量后端测试确认所有现有调用点都已迁移。

## 非目标

- 不提供运行时热更新、数据库编辑或提示词版本管理。
- 不把内部 Agent/子步骤扩展为第五层或独立 stage；它们只使用 current。
- 不修改 LLM Provider 适配器协议或模型配置逻辑。

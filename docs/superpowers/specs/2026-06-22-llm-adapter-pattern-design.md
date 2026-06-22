# LLM Adapter Pattern 设计文档

**日期**: 2026-06-22  
**项目**: JVuln Platform  
**模块**: jvuln-utils (LLM 抽象层)  
**作者**: AI Assistant  

---

## 1. 背景与目标

### 1.1 当前状况

JVuln Platform 的 Stage 4 (ArtifactGenStage) 使用 LLM agent 生成 CVE 复现环境，依赖 tool calling 机制调用后端工具（write_file, validate_artifacts, finish 等）。

**现有架构：**
- `LlmCaller`: 处理 OpenAI-compatible API 调用（支持 tool_calls）
- `AnthropicCaller`: 单独处理 Anthropic API（不同的请求/响应格式）
- `LlmClient`: 简单的客户端封装
- `ArtifactGenStage`: 直接使用 `response.hasToolUse()` 判断并执行工具

**问题：**
1. 缺乏统一的 LLM 提供商抽象，扩展新 LLM 需要修改多处代码
2. 未来可能需要支持不支持原生 tool calling 的 LLM（需要 ReAct 等 fallback 模式）
3. 提供商特定逻辑散落在多处，难以维护

### 1.2 设计目标

**功能需求：**
- ✅ 立即支持 3 个 LLM：gpt-5.4, claude-opus-4-6, deepseek-v4（都支持原生 tool calling）
- ✅ 架构易扩展，未来可支持不支持 tool calling 的 LLM（如 Llama、Mistral 本地部署版本）
- ✅ 不需要自动能力检测，通过 model 名称识别提供商

**非功能需求：**
- 最小化对现有代码的侵入（保留现有 LlmCaller 和 AnthropicCaller）
- 现有的 ArtifactGenStage 代码无需修改
- 支持渐进式迁移（新旧代码可以共存）

---

## 2. 架构设计

### 2.1 核心思想：适配器模式 (Adapter Pattern)

创建 `LlmAdapter` 接口统一不同 LLM 提供商的调用方式，每个具体提供商实现自己的适配器。

**架构图：**

```
PipelineContext / ArtifactGenStage
           ↓
       LlmClient (统一入口)
           ↓
   LlmAdapterFactory (根据 model 创建适配器)
           ↓
┌──────────────────┬─────────────────┬──────────────────┐
│   OpenAI         │   Anthropic     │   DeepSeek       │
│   Compatible     │   Adapter       │   Adapter        │
│   Adapter        │                 │                  │
└──────────────────┴─────────────────┴──────────────────┘
       ↓                   ↓                  ↓
   LlmCaller       AnthropicCaller       LlmCaller
   (现有)              (现有)             (复用)
```

**设计优势：**
1. **最小侵入**：现有 Caller 类无需修改，只需新增 Adapter 层
2. **统一接口**：所有 Adapter 实现 `LlmAdapter` 接口
3. **易扩展**：新增 LLM 只需实现新 Adapter，在工厂类中注册即可
4. **灵活性**：每个 Adapter 可以定制 request/response 转换逻辑

### 2.2 模块职责

| 模块 | 职责 | 位置 |
|------|------|------|
| `LlmAdapter` | 统一接口定义 | `com.jvuln.llm.LlmAdapter` |
| `LlmAdapterFactory` | 根据 model 创建适配器 | `com.jvuln.llm.LlmAdapterFactory` |
| `OpenAICompatibleAdapter` | 支持 OpenAI API 的 LLM | `com.jvuln.llm.OpenAICompatibleAdapter` |
| `AnthropicAdapter` | 支持 Anthropic API 的 LLM | `com.jvuln.llm.AnthropicAdapter` |
| `LlmClient` | 统一客户端入口 | `com.jvuln.llm.LlmClient` |

---

## 3. 接口与组件设计

### 3.1 LlmAdapter 接口

```java
package com.jvuln.llm;

import reactor.core.publisher.Flux;

/**
 * LLM 适配器接口
 * 
 * 统一不同 LLM 提供商的调用方式，支持未来扩展不同的工具调用模式
 */
public interface LlmAdapter {
    
    /**
     * 发送聊天请求
     * @param request LLM 请求（统一格式）
     * @return LLM 响应（统一格式）
     */
    LlmResponse chat(LlmRequest request);
    
    /**
     * 流式聊天请求
     * @param request LLM 请求
     * @return 流式响应（文本片段）
     */
    Flux<String> chatStream(LlmRequest request);
    
    /**
     * 该 LLM 是否支持原生 tool calling
     * @return true 表示支持，false 表示需要 fallback（如 ReAct）
     */
    boolean supportsToolCalling();
    
    /**
     * 获取适配器名称（用于日志和调试）
     */
    String getName();
}
```

**设计要点：**
- `chat()` 和 `chatStream()` 使用统一的 `LlmRequest/LlmResponse` 格式
- `supportsToolCalling()` 标识能力，用于日志记录和未来优化
- `getName()` 用于调试和错误追踪

### 3.2 LlmAdapterFactory 工厂类

```java
package com.jvuln.llm;

/**
 * LLM 适配器工厂
 */
@Component
public class LlmAdapterFactory {
    
    private final ObjectMapper mapper;
    
    public LlmAdapter createAdapter(LlmCallConfig config) {
        String model = config.getModel();
        
        // Anthropic 模型识别
        if (isAnthropicModel(model)) {
            return new AnthropicAdapter(config, mapper);
        }
        
        // 默认使用 OpenAI 兼容适配器
        return new OpenAICompatibleAdapter(config, mapper);
    }
    
    private boolean isAnthropicModel(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.startsWith("claude-") || 
               m.contains("opus") || 
               m.contains("sonnet") || 
               m.contains("haiku");
    }
}
```

**识别规则：**
- **Anthropic 模型**：model 名称包含 `claude-`, `opus`, `sonnet`, `haiku`
- **OpenAI 兼容**：默认选项，支持 gpt-5.4, deepseek-v4 等

### 3.3 OpenAICompatibleAdapter

```java
/**
 * OpenAI 兼容 API 适配器
 * 
 * 支持：
 * - OpenAI (gpt-5.4, gpt-4o, etc.)
 * - DeepSeek (deepseek-v4, deepseek-chat, etc.)
 * - 其他 OpenAI-compatible 提供商
 */
public class OpenAICompatibleAdapter implements LlmAdapter {
    
    private final LlmCaller caller;
    private final String model;
    
    public OpenAICompatibleAdapter(LlmCallConfig config, ObjectMapper mapper) {
        this.caller = new LlmCaller(
            config.getBaseUrl(),
            config.getApiKey(),
            config.getModel(),
            mapper
        );
        this.model = config.getModel();
    }
    
    @Override
    public LlmResponse chat(LlmRequest request) {
        return caller.chat(request);  // 直接委托
    }
    
    @Override
    public Flux<String> chatStream(LlmRequest request) {
        return caller.chatStream(request);
    }
    
    @Override
    public boolean supportsToolCalling() {
        return true;  // OpenAI API 支持 tool_calls
    }
    
    @Override
    public String getName() {
        return "OpenAI-Compatible (" + model + ")";
    }
}
```

**设计要点：**
- 完全委托给现有的 `LlmCaller`，零修改
- 支持所有 OpenAI-compatible API 的 LLM

### 3.4 AnthropicAdapter

```java
/**
 * Anthropic API 适配器
 * 
 * 支持：claude-opus-4-6, claude-sonnet-*, claude-haiku-*
 */
public class AnthropicAdapter implements LlmAdapter {
    
    private final AnthropicCaller caller;
    private final String model;
    
    public AnthropicAdapter(LlmCallConfig config, ObjectMapper mapper) {
        this.caller = new AnthropicCaller(
            config.getBaseUrl(),
            config.getApiKey(),
            config.getModel(),
            mapper
        );
        this.model = config.getModel();
    }
    
    @Override
    public LlmResponse chat(LlmRequest request) {
        return caller.chat(request);  // 直接委托
    }
    
    @Override
    public Flux<String> chatStream(LlmRequest request) {
        return caller.chatStream(request);
    }
    
    @Override
    public boolean supportsToolCalling() {
        return true;  // Claude 支持原生 tool use
    }
    
    @Override
    public String getName() {
        return "Anthropic (" + model + ")";
    }
}
```

### 3.5 LlmClient 集成

```java
@Component
public class LlmClient {
    
    private final LlmAdapterFactory adapterFactory;
    private LlmAdapter adapter;
    
    public LlmClient(LlmCallConfig config, LlmAdapterFactory adapterFactory) {
        this.adapterFactory = adapterFactory;
        this.adapter = adapterFactory.createAdapter(config);
    }
    
    public LlmResponse chat(LlmRequest request) {
        return adapter.chat(request);
    }
    
    public Flux<String> chatStream(LlmRequest request) {
        return adapter.chatStream(request);
    }
    
    public boolean supportsToolCalling() {
        return adapter.supportsToolCalling();
    }
    
    public String getAdapterName() {
        return adapter.getName();
    }
    
    /**
     * 动态切换适配器（用于同一 Pipeline 中使用不同 LLM）
     */
    public void switchAdapter(LlmCallConfig newConfig) {
        this.adapter = adapterFactory.createAdapter(newConfig);
    }
}
```

**依赖注入说明：**
- `LlmAdapterFactory` 通过 Spring `@Component` 注入
- `LlmCallConfig` 由 `PipelineContext` 提供（从数据库或配置文件加载）
- `LlmClient` 仍然是 `@Component`，在 Pipeline 启动时创建

**变更影响：**
- ✅ 现有 `ArtifactGenStage` 代码无需修改
- ✅ 继续使用 `ctx.getLlmClient().chat(request)`
- ✅ 响应处理逻辑完全不变（`response.hasToolUse()`）

---

## 4. 未来扩展：ReAct 模式

### 4.1 扩展场景

未来如果需要支持不支持原生 tool calling 的 LLM（如本地部署的 Llama、Mistral），可以实现 `ReActAdapter`。

### 4.2 ReAct 模式原理

**ReAct (Reasoning + Acting) 格式：**

LLM 输出结构化文本，包含 "Thought" 和 "Action"：

```
Thought: I need to create the pom.xml file
Action:
```json
{
    "action": "write_file",
    "action_input": {
        "path": "vuln-demo/pom.xml",
        "content": "..."
    }
}
```
```

后端解析 JSON 并转换为标准的 `ContentBlock.toolUse`。

### 4.3 ReActAdapter 设计（未来实现）

```java
public class ReActAdapter implements LlmAdapter {
    
    @Override
    public LlmResponse chat(LlmRequest request) {
        // 1. 转换请求：将 tools 转换为 system prompt 中的文本描述
        LlmRequest transformed = transformRequestToReAct(request);
        
        // 2. 调用 LLM（不带 tools 参数）
        LlmResponse raw = caller.chat(transformed);
        
        // 3. 解析响应文本，提取 JSON action
        LlmResponse parsed = parseReActResponse(raw);
        
        return parsed;
    }
    
    @Override
    public boolean supportsToolCalling() {
        return false;  // 通过 ReAct 模拟，不是原生支持
    }
    
    private LlmRequest transformRequestToReAct(LlmRequest request) {
        // 将 tools 注入到 system prompt：
        // - Tool 名称、描述、参数 schema
        // - 输出格式说明（JSON action）
        // - Final Answer 格式
    }
    
    private LlmResponse parseReActResponse(LlmResponse raw) {
        // 正则匹配 ```json ... ```
        // 解析 action 和 action_input
        // 转换为 ContentBlock.toolUse(toolUseId, action, actionInput)
    }
}
```

### 4.4 工厂类注册（未来）

```java
public LlmAdapter createAdapter(LlmCallConfig config) {
    String model = config.getModel();
    
    if (isAnthropicModel(model)) {
        return new AnthropicAdapter(config, mapper);
    }
    
    // 识别需要 ReAct 模式的模型
    if (isReActModel(model)) {
        return new ReActAdapter(config, mapper);
    }
    
    return new OpenAICompatibleAdapter(config, mapper);
}

private boolean isReActModel(String model) {
    if (model == null) return false;
    String m = model.toLowerCase();
    return m.startsWith("llama-") || m.startsWith("mistral-");
}
```

---

## 5. 数据流与调用链

### 5.1 正常工具调用流程（当前实现）

```
ArtifactGenStage
    ↓
ctx.getLlmClient().chat(request)
    ↓
LlmClient.chat()
    ↓
adapter.chat() (OpenAICompatibleAdapter 或 AnthropicAdapter)
    ↓
LlmCaller.chat() 或 AnthropicCaller.chat()
    ↓
HTTP POST /v1/chat/completions (OpenAI API)
或
HTTP POST /v1/messages (Anthropic API)
    ↓
解析响应，提取 ContentBlock (tool_use)
    ↓
返回 LlmResponse
    ↓
ArtifactGenStage 检查 response.hasToolUse()
    ↓
执行工具：AgentToolExecutor.doWriteFile() 等
```

### 5.2 未来 ReAct 模式流程

```
ArtifactGenStage
    ↓
ctx.getLlmClient().chat(request)
    ↓
LlmClient.chat()
    ↓
ReActAdapter.chat()
    ↓
transformRequestToReAct(): 将 tools 注入 system prompt
    ↓
LlmCaller.chat() (不带 tools 参数)
    ↓
HTTP POST /v1/chat/completions
    ↓
parseReActResponse(): 解析文本中的 JSON action
    ↓
转换为 ContentBlock.toolUse
    ↓
返回 LlmResponse
    ↓
ArtifactGenStage 检查 response.hasToolUse() ✅ (已转换)
    ↓
执行工具（与原生 tool calling 流程相同）
```

**关键点：**
- ReActAdapter 内部完成所有转换，对外接口完全一致
- ArtifactGenStage 无感知，继续使用 `response.hasToolUse()`
- 真正的"零侵入扩展"

---

## 6. 错误处理

### 6.1 适配器创建失败

```java
// LlmAdapterFactory
public LlmAdapter createAdapter(LlmCallConfig config) {
    try {
        String model = config.getModel();
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("Model name is required");
        }
        // ... 创建适配器
    } catch (Exception e) {
        log.error("Failed to create LLM adapter for model: {}", 
                  config.getModel(), e);
        throw new RuntimeException("LLM adapter creation failed", e);
    }
}
```

### 6.2 API 调用失败

适配器直接委托给现有 Caller，错误处理保持不变：
- `LlmCaller` 和 `AnthropicCaller` 已实现重试逻辑（LlmUtil.executeWithRetry）
- 超时、网络错误、API 错误都会抛出 `RuntimeException`

### 6.3 未来 ReAct 解析失败

```java
// ReActAdapter
private LlmResponse parseReActResponse(LlmResponse raw) {
    try {
        // 尝试解析 JSON action
    } catch (Exception e) {
        log.warn("Failed to parse ReAct response, treating as plain text: {}", 
                 e.getMessage());
        // 返回原始响应（无 tool use）
        return raw;
    }
}
```

**降级策略：**
- 如果解析失败，返回纯文本响应
- ArtifactGenStage 会检测到 `!response.hasToolUse()`，累积空响应计数
- 达到 `MAX_EMPTY_AGENT_RESPONSES` 后触发失败

---

## 7. 测试策略

### 7.1 单元测试

| 测试类 | 覆盖范围 |
|--------|----------|
| `LlmAdapterFactoryTest` | 测试 model 识别逻辑、适配器创建 |
| `OpenAICompatibleAdapterTest` | 测试 OpenAI API 调用（mock LlmCaller） |
| `AnthropicAdapterTest` | 测试 Anthropic API 调用（mock AnthropicCaller） |

**测试用例：**
```java
@Test
public void testCreateOpenAIAdapter() {
    LlmCallConfig config = new LlmCallConfig();
    config.setModel("gpt-5.4");
    
    LlmAdapter adapter = factory.createAdapter(config);
    
    assertThat(adapter).isInstanceOf(OpenAICompatibleAdapter.class);
    assertThat(adapter.supportsToolCalling()).isTrue();
    assertThat(adapter.getName()).contains("gpt-5.4");
}

@Test
public void testCreateDeepSeekAdapter() {
    LlmCallConfig config = new LlmCallConfig();
    config.setModel("deepseek-v4");
    
    LlmAdapter adapter = factory.createAdapter(config);
    
    assertThat(adapter).isInstanceOf(OpenAICompatibleAdapter.class);
    assertThat(adapter.getName()).contains("deepseek-v4");
}

@Test
public void testCreateAnthropicAdapter() {
    LlmCallConfig config = new LlmCallConfig();
    config.setModel("claude-opus-4-6");
    
    LlmAdapter adapter = factory.createAdapter(config);
    
    assertThat(adapter).isInstanceOf(AnthropicAdapter.class);
}
```

### 7.2 集成测试

**现有集成测试无需修改：**
- `ArtifactGenStageTest`: 测试完整的 agent 循环
- 通过配置不同的 `LlmCallConfig.model` 测试不同适配器
- 验证 tool calling 流程正常工作

---

## 8. 实施计划

### Phase 1: 核心适配器实现（第1周）

**文件清单：**
1. `LlmAdapter.java` - 接口定义
2. `LlmAdapterFactory.java` - 工厂类
3. `OpenAICompatibleAdapter.java` - OpenAI 兼容适配器
4. `AnthropicAdapter.java` - Anthropic 适配器
5. `LlmClient.java` - 更新使用适配器

**任务：**
- [ ] 创建 `LlmAdapter` 接口
- [ ] 实现 `LlmAdapterFactory`（model 识别逻辑）
- [ ] 实现 `OpenAICompatibleAdapter`（委托给 LlmCaller）
- [ ] 实现 `AnthropicAdapter`（委托给 AnthropicCaller）
- [ ] 更新 `LlmClient` 使用工厂创建适配器
- [ ] 编写单元测试

**验证：**
- 运行现有集成测试，确保无回归
- 手动测试三个模型的适配器创建和工具调用：
  1. 配置 `model=gpt-5.4`，验证创建 `OpenAICompatibleAdapter`
  2. 配置 `model=claude-opus-4-6`，验证创建 `AnthropicAdapter`
  3. 配置 `model=deepseek-v4`，验证创建 `OpenAICompatibleAdapter`
  4. 运行一个简单的 Stage 4 任务，验证 tool calling 正常工作
- 检查日志输出，确认适配器名称正确显示

### Phase 2: 文档与清理（第2周）

**任务：**
- [ ] 更新 API 文档（JavaDoc）
- [ ] 添加使用示例到 README
- [ ] 代码审查和重构优化
- [ ] 性能测试（确保适配器层无明显开销）

### Phase 3: 未来扩展预留（可选）

**仅设计，不实现：**
- [ ] `ReActAdapter` 接口骨架（注释掉的代码）
- [ ] ReAct 模式的设计文档和示例
- [ ] 单元测试骨架（@Disabled）

---

## 9. 风险与缓解

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|----------|
| 适配器层引入性能开销 | 中 | 低 | 适配器只做委托，无复杂逻辑；进行性能基准测试 |
| model 识别规则不准确 | 高 | 中 | 提供明确的文档说明 model 命名规范；支持手动指定适配器类型 |
| 现有代码回归 | 高 | 低 | 保留现有 Caller 类不变；完整运行现有集成测试 |
| 未来 ReAct 模式复杂度高 | 中 | 中 | 当前不实现，预留接口；参考 LangChain 实现 |

---

## 10. 设计决策记录

### 决策 1: 选择适配器模式而非策略模式

**原因：**
- 适配器模式更直观：每个提供商一个适配器
- 最小侵入：保留现有 Caller 类，只需新增 Adapter 层
- 易扩展：新增 LLM 只需实现新 Adapter

**替代方案：**
- 策略模式：将"工具调用策略"抽象为独立的 Strategy
- 缺点：概念较重，需要重构 LlmClient

### 决策 2: 工厂类使用 model 名称识别，而非配置

**原因：**
- 简单直接：根据 model 名称前缀识别提供商
- 无需额外配置：用户只需提供 baseUrl + model
- 易维护：识别规则集中在工厂类中

**替代方案：**
- 配置驱动：在数据库中手动标记 `providerType: "anthropic"`
- 缺点：增加配置复杂度，容易配置错误

### 决策 3: 当前不实现 ReAct 模式，只预留扩展点

**原因：**
- YAGNI 原则：当前支持的 3 个 LLM 都有原生 tool calling
- 降低风险：先验证适配器模式可行，再扩展复杂功能
- 清晰边界：ReAct 模式是独立的 Adapter，不影响现有代码

---

## 11. 附录

### 11.1 参考资料

- [LangChain ChatDeepSeek 实现](https://github.com/langchain-ai/langchain/tree/master/libs/partners/deepseek)
- [LangChain ReAct Agent](https://github.com/langchain-ai/langchain/tree/master/libs/langchain/langchain_classic/agents/react)
- [OpenAI Function Calling API](https://platform.openai.com/docs/guides/function-calling)
- [Anthropic Tool Use](https://docs.anthropic.com/en/docs/tool-use)

### 11.2 术语表

| 术语 | 定义 |
|------|------|
| Tool Calling | LLM 原生支持的工具调用机制，通过 API 参数传递工具定义 |
| ReAct | Reasoning + Acting 模式，通过文本格式模拟工具调用 |
| Adapter | 适配器，统一不同 LLM 提供商的接口 |
| OpenAI-compatible | 遵循 OpenAI API 规范的 LLM 提供商 |

---

**文档版本**: v1.0  
**最后更新**: 2026-06-22

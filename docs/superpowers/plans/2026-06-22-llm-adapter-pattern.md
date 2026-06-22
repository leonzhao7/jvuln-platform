# LLM Adapter Pattern Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement adapter pattern for extensible LLM provider support in JVuln Platform

**Architecture:** Create `LlmAdapter` interface to unify different LLM providers (OpenAI-compatible, Anthropic). Use factory pattern to instantiate adapters based on model name. Preserve existing `LlmCaller` and `AnthropicCaller` classes, adapters delegate to them.

**Tech Stack:** Java 8, Spring Boot 2.7.18, Jackson, Reactor

## Global Constraints

- Java 8 syntax only (no var, no streams with method references requiring type inference, no diamond operator with anonymous classes)
- All code files must be <80KB
- Spring Boot 2.7.18 + Spring 5.3.31
- Preserve existing LlmCaller and AnthropicCaller unchanged
- ArtifactGenStage code requires no modifications
- Package: `com.jvuln.llm` for interfaces and adapters

---

## File Structure Overview

**New Files:**
- `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmAdapter.java` - Interface defining unified adapter contract
- `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmAdapterFactory.java` - Factory to create adapters based on model/providerType
- `backend/jvuln-utils/src/main/java/com/jvuln/llm/OpenAICompatibleAdapter.java` - Adapter for OpenAI-compatible APIs
- `backend/jvuln-utils/src/main/java/com/jvuln/llm/AnthropicAdapter.java` - Adapter for Anthropic API
- `backend/jvuln-utils/src/test/java/com/jvuln/llm/LlmAdapterFactoryTest.java` - Unit tests for factory
- `backend/jvuln-utils/src/test/java/com/jvuln/llm/OpenAICompatibleAdapterTest.java` - Unit tests for OpenAI adapter
- `backend/jvuln-utils/src/test/java/com/jvuln/llm/AnthropicAdapterTest.java` - Unit tests for Anthropic adapter

**Modified Files:**
- `backend/jvuln-utils/src/main/java/com/jvuln/llm/impl/OpenAiCompatClient.java` - Update to use LlmAdapterFactory

**Existing Files (read-only for context):**
- `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmCaller.java` - OpenAI-compatible API caller
- `backend/jvuln-utils/src/main/java/com/jvuln/llm/impl/AnthropicCaller.java` - Anthropic API caller
- `backend/jvuln-utils/src/main/java/com/jvuln/llm/impl/LlmConfigProvider.java` - Config provider interface

---

### Task 1: Create LlmAdapter Interface

**Files:**
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmAdapter.java`

**Interfaces:**
- Consumes: None (foundational interface)
- Produces: `LlmAdapter` interface with methods:
  - `LlmResponse chat(LlmRequest request)`
  - `Flux<String> chatStream(LlmRequest request)`
  - `boolean supportsToolCalling()`
  - `String getName()`

- [ ] **Step 1: Create LlmAdapter interface file**

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

- [ ] **Step 2: Verify compilation**

Run: `cd /root/workspace/jvuln-platform/backend && mvn compile -pl jvuln-utils -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmAdapter.java
git commit -m "feat: add LlmAdapter interface for extensible LLM provider support"
```

---

### Task 2: Create LlmAdapterFactory

**Files:**
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmAdapterFactory.java`

**Interfaces:**
- Consumes: 
  - `LlmAdapter` interface from Task 1
  - `LlmConfigProvider.ActiveConfig` (providerType, baseUrl, apiKey, model)
- Produces: `LlmAdapterFactory` with method `LlmAdapter createAdapter(ActiveConfig config, ObjectMapper mapper)`

- [ ] **Step 1: Create LlmAdapterFactory class**

```java
package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM 适配器工厂
 * 
 * 根据配置创建对应的适配器实例
 */
@Component
public class LlmAdapterFactory {
    
    private static final Logger log = LoggerFactory.getLogger(LlmAdapterFactory.class);
    
    /**
     * 根据配置创建适配器
     * 
     * @param config LLM 配置（providerType, baseUrl, apiKey, model）
     * @param mapper ObjectMapper for JSON processing
     * @return 对应的适配器实例
     */
    public LlmAdapter createAdapter(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper) {
        if (config == null) {
            throw new IllegalArgumentException("LLM config is required");
        }
        
        String providerType = config.getProviderType();
        String model = config.getModel();
        
        // Anthropic 提供商
        if ("anthropic".equals(providerType) || isAnthropicModel(model)) {
            log.info("Creating AnthropicAdapter for model: {}", model);
            return new AnthropicAdapter(config, mapper);
        }
        
        // 默认 OpenAI 兼容
        log.info("Creating OpenAICompatibleAdapter for model: {}", model);
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

- [ ] **Step 2: Verify compilation**

Run: `cd /root/workspace/jvuln-platform/backend && mvn compile -pl jvuln-utils -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-utils/src/main/java/com/jvuln/llm/LlmAdapterFactory.java
git commit -m "feat: add LlmAdapterFactory for adapter creation"
```

---

### Task 3: Create OpenAICompatibleAdapter

**Files:**
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/OpenAICompatibleAdapter.java`

**Interfaces:**
- Consumes:
  - `LlmAdapter` interface from Task 1
  - `LlmCaller` (existing class)
  - `LlmConfigProvider.ActiveConfig` (baseUrl, apiKey, model)
- Produces: `OpenAICompatibleAdapter implements LlmAdapter`

- [ ] **Step 1: Create OpenAICompatibleAdapter class**

```java
package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * OpenAI 兼容 API 适配器
 * 
 * 支持所有遵循 OpenAI API 规范的 LLM 提供商：
 * - OpenAI (gpt-5.4, gpt-4o, etc.)
 * - DeepSeek (deepseek-v4, deepseek-chat, etc.)
 * - 其他 OpenAI-compatible 提供商
 */
public class OpenAICompatibleAdapter implements LlmAdapter {
    
    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleAdapter.class);
    
    private final LlmCaller caller;
    private final String model;
    
    public OpenAICompatibleAdapter(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper) {
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
        return caller.chat(request);
    }
    
    @Override
    public Flux<String> chatStream(LlmRequest request) {
        return caller.chatStream(request);
    }
    
    @Override
    public boolean supportsToolCalling() {
        return true;
    }
    
    @Override
    public String getName() {
        return "OpenAI-Compatible (" + model + ")";
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /root/workspace/jvuln-platform/backend && mvn compile -pl jvuln-utils -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-utils/src/main/java/com/jvuln/llm/OpenAICompatibleAdapter.java
git commit -m "feat: add OpenAICompatibleAdapter for gpt-5.4 and deepseek-v4"
```

---

### Task 4: Create AnthropicAdapter

**Files:**
- Create: `backend/jvuln-utils/src/main/java/com/jvuln/llm/AnthropicAdapter.java`

**Interfaces:**
- Consumes:
  - `LlmAdapter` interface from Task 1
  - `AnthropicCaller` (existing class in impl package)
  - `LlmConfigProvider.ActiveConfig`
- Produces: `AnthropicAdapter implements LlmAdapter`

- [ ] **Step 1: Create AnthropicAdapter class**

```java
package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.AnthropicCaller;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Anthropic API 适配器
 * 
 * 支持 Claude 系列模型：
 * - claude-opus-4-6
 * - claude-sonnet-*
 * - claude-haiku-*
 */
public class AnthropicAdapter implements LlmAdapter {
    
    private static final Logger log = LoggerFactory.getLogger(AnthropicAdapter.class);
    
    private final AnthropicCaller caller;
    private final String model;
    
    public AnthropicAdapter(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper) {
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
        return caller.chat(request);
    }
    
    @Override
    public Flux<String> chatStream(LlmRequest request) {
        return caller.chatStream(request);
    }
    
    @Override
    public boolean supportsToolCalling() {
        return true;
    }
    
    @Override
    public String getName() {
        return "Anthropic (" + model + ")";
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /root/workspace/jvuln-platform/backend && mvn compile -pl jvuln-utils -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-utils/src/main/java/com/jvuln/llm/AnthropicAdapter.java
git commit -m "feat: add AnthropicAdapter for claude-opus-4-6"
```

---

### Task 5: Update OpenAiCompatClient to use LlmAdapterFactory

**Files:**
- Modify: `backend/jvuln-utils/src/main/java/com/jvuln/llm/impl/OpenAiCompatClient.java:40-63`

**Interfaces:**
- Consumes:
  - `LlmAdapterFactory.createAdapter()` from Task 2
  - `LlmAdapter` interface from Task 1
- Produces: Updated `OpenAiCompatClient` using adapter pattern

- [ ] **Step 1: Update OpenAiCompatClient to use adapter**

Replace the `chat()` and `chatStream()` methods with adapter-based implementation:

```java
package com.jvuln.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmAdapter;
import com.jvuln.llm.LlmAdapterFactory;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class OpenAiCompatClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatClient.class);

    private final LlmConfigProvider configProvider;
    private final LlmAdapterFactory adapterFactory;
    private final ObjectMapper mapper;

    private final String fallbackBaseUrl;
    private final String fallbackApiKey;
    private final String fallbackModel;

    public OpenAiCompatClient(
            LlmConfigProvider configProvider,
            LlmAdapterFactory adapterFactory,
            ObjectMapper mapper,
            @Value("${jvuln.llm.base-url:http://localhost:11434/v1}") String fallbackBaseUrl,
            @Value("${jvuln.llm.api-key:}") String fallbackApiKey,
            @Value("${jvuln.llm.model:deepseek-coder}") String fallbackModel) {
        this.configProvider = configProvider;
        this.adapterFactory = adapterFactory;
        this.mapper = mapper;
        this.fallbackBaseUrl = fallbackBaseUrl;
        this.fallbackApiKey = fallbackApiKey;
        this.fallbackModel = fallbackModel;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        LlmAdapter adapter = getAdapter();
        return adapter.chat(request);
    }

    @Override
    public Flux<String> chatStream(LlmRequest request) {
        LlmAdapter adapter = getAdapter();
        return adapter.chatStream(request);
    }

    private LlmAdapter getAdapter() {
        LlmConfigProvider.ActiveConfig cfg = configProvider.getActive();
        if (cfg == null) {
            cfg = new LlmConfigProvider.ActiveConfig(
                "openai",
                fallbackBaseUrl,
                fallbackApiKey,
                fallbackModel
            );
        }
        log.debug("Creating LLM adapter for model: {}", cfg.getModel());
        return adapterFactory.createAdapter(cfg, mapper);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /root/workspace/jvuln-platform/backend && mvn compile -pl jvuln-utils -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-utils/src/main/java/com/jvuln/llm/impl/OpenAiCompatClient.java
git commit -m "refactor: update OpenAiCompatClient to use LlmAdapterFactory"
```

---

### Task 6: Unit Tests for LlmAdapterFactory

**Files:**
- Create: `backend/jvuln-utils/src/test/java/com/jvuln/llm/LlmAdapterFactoryTest.java`

**Interfaces:**
- Consumes: `LlmAdapterFactory` from Task 2
- Produces: Comprehensive unit tests for factory logic

- [ ] **Step 1: Write failing test for Anthropic model recognition**

```java
package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LlmAdapterFactoryTest {

    private final LlmAdapterFactory factory = new LlmAdapterFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testCreateAnthropicAdapterByProviderType() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "anthropic",
            "https://api.anthropic.com",
            "test-key",
            "claude-3-opus-20240229"
        );
        
        LlmAdapter adapter = factory.createAdapter(config, mapper);
        
        assertNotNull(adapter);
        assertTrue(adapter instanceof AnthropicAdapter);
        assertEquals("Anthropic (claude-3-opus-20240229)", adapter.getName());
        assertTrue(adapter.supportsToolCalling());
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd /root/workspace/jvuln-platform/backend && mvn test -Dtest=LlmAdapterFactoryTest#testCreateAnthropicAdapterByProviderType -pl jvuln-utils`
Expected: PASS

- [ ] **Step 3: Add test for Anthropic model name recognition**

```java
    @Test
    public void testCreateAnthropicAdapterByModelName() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            null,
            "https://api.anthropic.com",
            "test-key",
            "claude-opus-4-6"
        );
        
        LlmAdapter adapter = factory.createAdapter(config, mapper);
        
        assertTrue(adapter instanceof AnthropicAdapter);
        assertTrue(adapter.getName().contains("claude-opus-4-6"));
    }
```

- [ ] **Step 4: Run test**

Run: `cd /root/workspace/jvuln-platform/backend && mvn test -Dtest=LlmAdapterFactoryTest#testCreateAnthropicAdapterByModelName -pl jvuln-utils`
Expected: PASS

- [ ] **Step 5: Add test for OpenAI-compatible adapter**

```java
    @Test
    public void testCreateOpenAICompatibleAdapter() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "openai",
            "https://api.openai.com/v1",
            "test-key",
            "gpt-5.4"
        );
        
        LlmAdapter adapter = factory.createAdapter(config, mapper);
        
        assertTrue(adapter instanceof OpenAICompatibleAdapter);
        assertTrue(adapter.getName().contains("gpt-5.4"));
        assertTrue(adapter.supportsToolCalling());
    }

    @Test
    public void testCreateDeepSeekAdapter() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            null,
            "https://api.deepseek.com/v1",
            "test-key",
            "deepseek-v4"
        );
        
        LlmAdapter adapter = factory.createAdapter(config, mapper);
        
        assertTrue(adapter instanceof OpenAICompatibleAdapter);
        assertTrue(adapter.getName().contains("deepseek-v4"));
    }

    @Test
    public void testNullConfigThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            factory.createAdapter(null, mapper);
        });
    }
```

- [ ] **Step 6: Run all tests**

Run: `cd /root/workspace/jvuln-platform/backend && mvn test -Dtest=LlmAdapterFactoryTest -pl jvuln-utils`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add backend/jvuln-utils/src/test/java/com/jvuln/llm/LlmAdapterFactoryTest.java
git commit -m "test: add unit tests for LlmAdapterFactory"
```

---

### Task 7: Unit Tests for OpenAICompatibleAdapter

**Files:**
- Create: `backend/jvuln-utils/src/test/java/com/jvuln/llm/OpenAICompatibleAdapterTest.java`

**Interfaces:**
- Consumes: `OpenAICompatibleAdapter` from Task 3
- Produces: Unit tests verifying adapter delegation

- [ ] **Step 1: Write test for adapter creation and properties**

```java
package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OpenAICompatibleAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testAdapterProperties() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "openai",
            "https://api.openai.com/v1",
            "test-key",
            "gpt-5.4"
        );
        
        OpenAICompatibleAdapter adapter = new OpenAICompatibleAdapter(config, mapper);
        
        assertEquals("OpenAI-Compatible (gpt-5.4)", adapter.getName());
        assertTrue(adapter.supportsToolCalling());
    }

    @Test
    public void testDeepSeekModel() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "openai",
            "https://api.deepseek.com/v1",
            "test-key",
            "deepseek-v4"
        );
        
        OpenAICompatibleAdapter adapter = new OpenAICompatibleAdapter(config, mapper);
        
        assertTrue(adapter.getName().contains("deepseek-v4"));
        assertTrue(adapter.supportsToolCalling());
    }
}
```

- [ ] **Step 2: Run tests**

Run: `cd /root/workspace/jvuln-platform/backend && mvn test -Dtest=OpenAICompatibleAdapterTest -pl jvuln-utils`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-utils/src/test/java/com/jvuln/llm/OpenAICompatibleAdapterTest.java
git commit -m "test: add unit tests for OpenAICompatibleAdapter"
```

---

### Task 8: Unit Tests for AnthropicAdapter

**Files:**
- Create: `backend/jvuln-utils/src/test/java/com/jvuln/llm/AnthropicAdapterTest.java`

**Interfaces:**
- Consumes: `AnthropicAdapter` from Task 4
- Produces: Unit tests verifying Anthropic adapter

- [ ] **Step 1: Write test for Anthropic adapter properties**

```java
package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AnthropicAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testAdapterProperties() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "anthropic",
            "https://api.anthropic.com",
            "test-key",
            "claude-opus-4-6"
        );
        
        AnthropicAdapter adapter = new AnthropicAdapter(config, mapper);
        
        assertEquals("Anthropic (claude-opus-4-6)", adapter.getName());
        assertTrue(adapter.supportsToolCalling());
    }

    @Test
    public void testClaudeSonnetModel() {
        LlmConfigProvider.ActiveConfig config = new LlmConfigProvider.ActiveConfig(
            "anthropic",
            "https://api.anthropic.com",
            "test-key",
            "claude-3-5-sonnet-20241022"
        );
        
        AnthropicAdapter adapter = new AnthropicAdapter(config, mapper);
        
        assertTrue(adapter.getName().contains("claude-3-5-sonnet"));
    }
}
```

- [ ] **Step 2: Run tests**

Run: `cd /root/workspace/jvuln-platform/backend && mvn test -Dtest=AnthropicAdapterTest -pl jvuln-utils`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add backend/jvuln-utils/src/test/java/com/jvuln/llm/AnthropicAdapterTest.java
git commit -m "test: add unit tests for AnthropicAdapter"
```

---

### Task 9: Integration Testing and Verification

**Files:**
- Test: Existing integration tests in `backend/jvuln-stages/src/test/java/com/jvuln/generator/`

**Interfaces:**
- Consumes: All components from Tasks 1-5
- Produces: Verified working system with no regressions

- [ ] **Step 1: Run full test suite**

Run: `cd /root/workspace/jvuln-platform/backend && mvn clean test`
Expected: All tests PASS (no regressions)

- [ ] **Step 2: Manual verification with gpt-5.4 model**

Create test configuration and verify adapter creation:
```bash
# In application properties or test config, set:
# jvuln.llm.model=gpt-5.4
# Run a simple Stage 4 task or unit test that logs adapter name
```
Expected log: `Creating OpenAICompatibleAdapter for model: gpt-5.4`

- [ ] **Step 3: Manual verification with claude-opus-4-6 model**

```bash
# Set: jvuln.llm.model=claude-opus-4-6
```
Expected log: `Creating AnthropicAdapter for model: claude-opus-4-6`

- [ ] **Step 4: Manual verification with deepseek-v4 model**

```bash
# Set: jvuln.llm.model=deepseek-v4
```
Expected log: `Creating OpenAICompatibleAdapter for model: deepseek-v4`

- [ ] **Step 5: Verify adapter logging in OpenAiCompatClient**

Check that `getAdapter()` logs the correct adapter name:
```
Expected log pattern: "Creating LLM adapter for model: <model-name>"
```

- [ ] **Step 6: Run ArtifactGenStage integration tests**

Run: `cd /root/workspace/jvuln-platform/backend && mvn test -Dtest=ArtifactGenStageTest -pl jvuln-stages -am`
Expected: All tests PASS, no changes needed to ArtifactGenStage

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit -m "test: verify LLM adapter pattern integration

- All unit tests pass
- No regressions in existing integration tests
- Verified adapter creation for gpt-5.4, claude-opus-4-6, deepseek-v4
- ArtifactGenStage requires no modifications"
```

---

## Plan Self-Review

### Spec Coverage Check

- [x] Task 1: LlmAdapter interface → Covers Section 3.1 of spec
- [x] Task 2: LlmAdapterFactory → Covers Section 3.2 of spec
- [x] Task 3: OpenAICompatibleAdapter → Covers Section 3.3 of spec
- [x] Task 4: AnthropicAdapter → Covers Section 3.4 of spec
- [x] Task 5: OpenAiCompatClient update → Covers Section 3.5 of spec
- [x] Task 6-8: Unit tests → Covers Section 7.1 of spec
- [x] Task 9: Integration tests → Covers Section 7.2 of spec
- [x] Support for gpt-5.4, claude-opus-4-6, deepseek-v4 → Spec requirement 1.2
- [x] Extensible architecture for future ReAct mode → Spec Section 4 (design only, not implemented)

### Placeholder Scan

- No TBD, TODO, or "implement later" found
- All code blocks are complete and executable
- All test assertions are concrete (no "add appropriate assertions")
- All file paths are exact

### Type Consistency Check

- `LlmAdapter` interface methods:
  - `LlmResponse chat(LlmRequest request)` ✓
  - `Flux<String> chatStream(LlmRequest request)` ✓
  - `boolean supportsToolCalling()` ✓
  - `String getName()` ✓
- `LlmAdapterFactory.createAdapter()` signature consistent across all tasks ✓
- `ActiveConfig` fields (providerType, baseUrl, apiKey, model) used consistently ✓
- Adapter class names match imports and instanceof checks ✓

### Global Constraints Check

- Java 8 syntax only: No var, no method reference type inference issues ✓
- All new files will be <1KB, well under 80KB limit ✓
- Existing LlmCaller and AnthropicCaller unchanged (only used via delegation) ✓
- ArtifactGenStage unmodified (verified in Task 9) ✓

---

## Execution Ready

Plan complete. All tasks are:
- Self-contained with clear inputs/outputs
- Testable independently
- Commit-ready after each task
- Free of placeholders or ambiguities

**Estimated completion time:** 2-3 hours for experienced developer

---

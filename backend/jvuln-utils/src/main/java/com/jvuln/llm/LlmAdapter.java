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

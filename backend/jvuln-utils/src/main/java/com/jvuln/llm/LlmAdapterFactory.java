package com.jvuln.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.impl.LlmConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM 适配器工厂
 *
 * 根据配置创建对应的适配器实例。
 * 检测 Anthropic 模型（claude-* 前缀或包含 opus/sonnet/haiku）并使用 AnthropicAdapter；
 * 其他模型默认使用 OpenAICompatibleAdapter（支持 OpenAI、DeepSeek 等）。
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
     * @throws IllegalArgumentException 如果配置为 null
     */
    public LlmAdapter createAdapter(LlmConfigProvider.ActiveConfig config, ObjectMapper mapper) {
        if (config == null) {
            throw new IllegalArgumentException("LLM config is required");
        }

        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper is required");
        }

        String providerType = config.getProviderType();
        String model = config.getModel();

        // Anthropic 提供商：通过 providerType 或模型名称检测
        // TODO: Task 4 - Uncomment when AnthropicAdapter is implemented
        if ("anthropic".equals(providerType) || isAnthropicModel(model)) {
            log.warn("AnthropicAdapter not yet implemented, falling back to OpenAICompatibleAdapter for model: {}", model);
            // return new AnthropicAdapter(config, mapper);
        }

        // 默认 OpenAI 兼容（OpenAI、DeepSeek、本地模型等）
        log.info("Creating OpenAICompatibleAdapter for model: {}", model);
        return new OpenAICompatibleAdapter(config, mapper);
    }

    /**
     * 检测是否为 Anthropic 模型
     *
     * @param model 模型名称
     * @return true 如果是 Anthropic 模型
     */
    private boolean isAnthropicModel(String model) {
        if (model == null) {
            return false;
        }
        String m = model.toLowerCase();
        return m.startsWith("claude-") ||
               m.contains("opus") ||
               m.contains("sonnet") ||
               m.contains("haiku");
    }
}

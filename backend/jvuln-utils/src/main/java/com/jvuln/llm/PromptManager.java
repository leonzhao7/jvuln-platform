package com.jvuln.llm;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the global, stage, and current prompt layers for a single LLM request.
 */
@Component
public class PromptManager {

    private static final String GLOBAL_RESOURCE = "prompts/global.md";

    private final ResourceLoader resourceLoader;
    private final String resourcePrefix;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptManager(ResourceLoader resourceLoader) {
        this(resourceLoader, "");
    }

    public PromptManager(ResourceLoader resourceLoader, String resourcePrefix) {
        if (resourceLoader == null) {
            throw new IllegalArgumentException("ResourceLoader is required");
        }
        this.resourceLoader = resourceLoader;
        this.resourcePrefix = resourcePrefix == null ? "" : resourcePrefix;
    }

    public String resolve(LlmPromptStage stage, String currentPrompt) {
        if (stage == null) {
            throw new IllegalStateException("LLM prompt stage is required");
        }

        List<String> layers = new ArrayList<>();
        layers.add(readRequired(GLOBAL_RESOURCE));
        layers.add(readRequired(stage.getResourcePath()));
        if (currentPrompt != null && !currentPrompt.trim().isEmpty()) {
            layers.add(currentPrompt.trim());
        }
        return String.join("\n\n", layers);
    }

    private String readRequired(String path) {
        String resourcePath = resourcePrefix + path;
        String cached = cache.get(resourcePath);
        if (cached != null) {
            return cached;
        }

        Resource resource = resourceLoader.getResource("classpath:" + resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("Prompt resource not found: " + resourcePath);
        }
        String loaded = readContent(resource, resourcePath);
        String existing = cache.putIfAbsent(resourcePath, loaded);
        return existing == null ? loaded : existing;
    }

    private String readContent(Resource resource, String resourcePath) {
        try (InputStream input = resource.getInputStream()) {
            String content = new String(StreamUtils.copyToByteArray(input), StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                throw new IllegalStateException("Prompt resource is empty: " + resourcePath);
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + resourcePath, e);
        }
    }
}

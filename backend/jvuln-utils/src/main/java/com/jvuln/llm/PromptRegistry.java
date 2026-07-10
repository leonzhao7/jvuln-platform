package com.jvuln.llm;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptRegistry {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String getPrompt(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Prompt name is required");
        }
        return loadPrompt(name);
    }

    public String render(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    private String loadPrompt(String name) {
        String cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        String content = readPrompt(name);
        String existing = cache.putIfAbsent(name, content);
        return existing == null ? content : existing;
    }

    private String readPrompt(String name) {
        try (InputStream input = new ClassPathResource("prompts/" + name + ".md").getInputStream()) {
            return new String(StreamUtils.copyToByteArray(input), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new RuntimeException("Prompt not found: " + name, e);
        }
    }
}

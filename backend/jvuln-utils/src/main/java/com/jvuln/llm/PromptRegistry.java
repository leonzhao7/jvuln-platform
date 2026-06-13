package com.jvuln.llm;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptRegistry {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String getSystemPrompt(String stageName) {
        return loadPrompt("system_" + stageName);
    }

    public String getUserPrompt(String stageName) {
        return loadPrompt("user_" + stageName);
    }

    public String render(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    private String loadPrompt(String name) {
        if (cache.containsKey(name)) {
            return cache.get(name);
        }
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + name + ".txt");
            InputStream is = resource.getInputStream();
            byte[] bytes = new byte[is.available()];
            is.read(bytes);
            is.close();
            String content = new String(bytes, StandardCharsets.UTF_8);
            cache.put(name, content);
            return content;
        } catch (IOException e) {
            throw new RuntimeException("Prompt not found: " + name, e);
        }
    }
}

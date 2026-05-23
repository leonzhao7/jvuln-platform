package com.jvuln.controller;

import com.jvuln.llm.LlmCaller;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.impl.AnthropicCaller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.store.LlmConfigRepository;
import com.jvuln.store.entity.LlmConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final LlmConfigRepository repo;
    private final LlmClient llmClient;
    private final ObjectMapper mapper;

    public ConfigController(LlmConfigRepository repo, LlmClient llmClient, ObjectMapper mapper) {
        this.repo = repo;
        this.llmClient = llmClient;
        this.mapper = mapper;
    }

    @GetMapping("/llm")
    public ResponseEntity<LlmConfig> get() {
        LlmConfig cfg = repo.findById(1L).orElseGet(LlmConfig::new);
        // Never return the raw API key to the client
        LlmConfig safe = copyWithMaskedKey(cfg);
        return ResponseEntity.ok(safe);
    }

    @PutMapping("/llm")
    public ResponseEntity<LlmConfig> save(@RequestBody LlmConfig incoming) {
        LlmConfig cfg = repo.findById(1L).orElseGet(LlmConfig::new);
        cfg.setId(1L);
        cfg.setProviderType(incoming.getProviderType());
        cfg.setBaseUrl(incoming.getBaseUrl());
        cfg.setModel(incoming.getModel());
        cfg.setTemperature(incoming.getTemperature() != null ? incoming.getTemperature() : 0.1);
        cfg.setMaxTokens(incoming.getMaxTokens() != null ? incoming.getMaxTokens() : 8192);
        cfg.setEnabled(incoming.isEnabled());
        // Only overwrite key if a non-masked value was sent
        if (incoming.getApiKey() != null && !incoming.getApiKey().equals("••••••••")) {
            cfg.setApiKey(incoming.getApiKey());
        }
        repo.save(cfg);
        return ResponseEntity.ok(copyWithMaskedKey(cfg));
    }

    @PostMapping("/llm/test")
    public ResponseEntity<Map<String, Object>> test() {
        Map<String, Object> result = new HashMap<>();
        try {
            LlmConfig cfg = repo.findById(1L).orElseThrow(
                    () -> new IllegalStateException("No LLM config saved yet"));
            if (cfg.getBaseUrl() == null || cfg.getBaseUrl().trim().isEmpty()) {
                throw new IllegalStateException("Base URL is not configured");
            }
            if (cfg.getModel() == null || cfg.getModel().trim().isEmpty()) {
                throw new IllegalStateException("Model is not configured");
            }

            LlmRequest req = new LlmRequest(
                    "You are a helpful assistant.",
                    Collections.singletonList(LlmRequest.Message.user("Reply with just the word: PONG")),
                    0.0, 64, false);

            LlmResponse resp;
            if ("anthropic".equals(cfg.getProviderType())) {
                resp = new AnthropicCaller(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(), mapper).chat(req);
            } else {
                resp = new LlmCaller(cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(), mapper).chat(req);
            }

            result.put("ok", true);
            result.put("model", resp.getModel());
            result.put("response", resp.getContent().trim());
            result.put("tokens", resp.getPromptTokens() + "/" + resp.getCompletionTokens());
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    private LlmConfig copyWithMaskedKey(LlmConfig src) {
        LlmConfig copy = new LlmConfig();
        copy.setId(src.getId());
        copy.setProviderType(src.getProviderType());
        copy.setBaseUrl(src.getBaseUrl());
        copy.setModel(src.getModel());
        copy.setTemperature(src.getTemperature());
        copy.setMaxTokens(src.getMaxTokens());
        copy.setEnabled(src.isEnabled());
        copy.setApiKey(src.getApiKey() != null && !src.getApiKey().isEmpty() ? "••••••••" : "");
        return copy;
    }
}

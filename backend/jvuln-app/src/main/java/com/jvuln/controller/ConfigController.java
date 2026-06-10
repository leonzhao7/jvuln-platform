package com.jvuln.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmCaller;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.impl.AnthropicCaller;
import com.jvuln.store.JavaProfileRepository;
import com.jvuln.store.LlmConfigRepository;
import com.jvuln.store.entity.JavaProfile;
import com.jvuln.store.entity.LlmConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final LlmConfigRepository repo;
    private final JavaProfileRepository javaProfileRepo;
    private final ObjectMapper mapper;

    public ConfigController(LlmConfigRepository repo, JavaProfileRepository javaProfileRepo, ObjectMapper mapper) {
        this.repo = repo;
        this.javaProfileRepo = javaProfileRepo;
        this.mapper = mapper;
    }

    @GetMapping("/llm")
    public ResponseEntity<List<LlmConfig>> list() {
        List<LlmConfig> all = repo.findAll().stream()
                .map(this::copyWithMaskedKey)
                .collect(Collectors.toList());
        return ResponseEntity.ok(all);
    }

    @PostMapping("/llm")
    public ResponseEntity<LlmConfig> create(@RequestBody LlmConfig incoming) {
        LlmConfig cfg = new LlmConfig();
        applyFields(cfg, incoming);
        cfg.setActive(false);
        repo.save(cfg);
        return ResponseEntity.ok(copyWithMaskedKey(cfg));
    }

    @PutMapping("/llm/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody LlmConfig incoming) {
        LlmConfig cfg = repo.findById(id).orElse(null);
        if (cfg == null) return ResponseEntity.notFound().build();
        applyFields(cfg, incoming);
        repo.save(cfg);
        return ResponseEntity.ok(copyWithMaskedKey(cfg));
    }

    @DeleteMapping("/llm/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @Transactional
    @PostMapping("/llm/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable Long id) {
        LlmConfig cfg = repo.findById(id).orElse(null);
        if (cfg == null) return ResponseEntity.notFound().build();
        repo.deactivateAll();
        cfg.setActive(true);
        repo.save(cfg);
        return ResponseEntity.ok(copyWithMaskedKey(cfg));
    }

    @PostMapping("/llm/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            LlmConfig cfg = repo.findById(id).orElseThrow(
                    () -> new IllegalStateException("Config not found"));
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

    // ==================== Java Profiles ====================

    @GetMapping("/java-profiles")
    public ResponseEntity<List<JavaProfile>> listJavaProfiles() {
        return ResponseEntity.ok(javaProfileRepo.findAll());
    }

    @PostMapping("/java-profiles")
    public ResponseEntity<JavaProfile> createJavaProfile(@RequestBody JavaProfile incoming) {
        JavaProfile profile = new JavaProfile();
        applyJavaProfileFields(profile, incoming);
        profile.setIsDefault(Boolean.FALSE);
        javaProfileRepo.save(profile);
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/java-profiles/{id}")
    public ResponseEntity<?> updateJavaProfile(@PathVariable Long id, @RequestBody JavaProfile incoming) {
        JavaProfile profile = javaProfileRepo.findById(id).orElse(null);
        if (profile == null) return ResponseEntity.notFound().build();
        applyJavaProfileFields(profile, incoming);
        javaProfileRepo.save(profile);
        return ResponseEntity.ok(profile);
    }

    @DeleteMapping("/java-profiles/{id}")
    public ResponseEntity<?> deleteJavaProfile(@PathVariable Long id) {
        if (!javaProfileRepo.existsById(id)) return ResponseEntity.notFound().build();
        javaProfileRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @Transactional
    @PostMapping("/java-profiles/{id}/set-default")
    public ResponseEntity<?> setDefaultJavaProfile(@PathVariable Long id) {
        JavaProfile profile = javaProfileRepo.findById(id).orElse(null);
        if (profile == null) return ResponseEntity.notFound().build();
        javaProfileRepo.clearAllDefaults();
        profile.setIsDefault(Boolean.TRUE);
        javaProfileRepo.save(profile);
        return ResponseEntity.ok(profile);
    }

    private void applyJavaProfileFields(JavaProfile target, JavaProfile incoming) {
        target.setName(incoming.getName());
        target.setJavaVersion(incoming.getJavaVersion());
        target.setJavaHome(incoming.getJavaHome());
        target.setSpringBootVersion(incoming.getSpringBootVersion());
        target.setMavenJavaVersion(incoming.getMavenJavaVersion());
        target.setSyntaxConstraints(incoming.getSyntaxConstraints());
    }

    private void applyFields(LlmConfig cfg, LlmConfig incoming) {
        cfg.setName(incoming.getName());
        cfg.setProviderType(incoming.getProviderType());
        cfg.setBaseUrl(incoming.getBaseUrl());
        cfg.setModel(incoming.getModel());
        cfg.setTemperature(incoming.getTemperature() != null ? incoming.getTemperature() : 0.1);
        cfg.setMaxTokens(incoming.getMaxTokens() != null ? incoming.getMaxTokens() : 8192);
        if (incoming.getApiKey() != null && !incoming.getApiKey().equals("••••••••")) {
            cfg.setApiKey(incoming.getApiKey());
        }
    }

    private LlmConfig copyWithMaskedKey(LlmConfig src) {
        LlmConfig copy = new LlmConfig();
        copy.setId(src.getId());
        copy.setName(src.getName());
        copy.setProviderType(src.getProviderType());
        copy.setBaseUrl(src.getBaseUrl());
        copy.setModel(src.getModel());
        copy.setTemperature(src.getTemperature());
        copy.setMaxTokens(src.getMaxTokens());
        copy.setActive(src.isActive());
        copy.setApiKey(src.getApiKey() != null && !src.getApiKey().isEmpty() ? "••••••••" : "");
        return copy;
    }
}

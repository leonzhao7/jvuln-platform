package com.jvuln.controller;

import com.jvuln.llm.LlmEndpoint;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.llm.impl.LlmConfigProvider;
import com.jvuln.llm.impl.OpenAiCompatClient;
import com.jvuln.store.JavaProfileRepository;
import com.jvuln.store.LlmConfigRepository;
import com.jvuln.store.entity.JavaProfile;
import com.jvuln.store.entity.LlmConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final PromptRegistry promptRegistry;
    private final OpenAiCompatClient llmClient;

    public ConfigController(LlmConfigRepository repo, JavaProfileRepository javaProfileRepo,
                            PromptRegistry promptRegistry, OpenAiCompatClient llmClient) {
        this.repo = repo;
        this.javaProfileRepo = javaProfileRepo;
        this.promptRegistry = promptRegistry;
        this.llmClient = llmClient;
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
        validateEndpoint(cfg.getEndpoint());
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
            String endpoint = validateEndpoint(cfg.getEndpoint());

            LlmRequest req = LlmRequest.diagnostic(
                    promptRegistry.getPrompt("current/config-connection-test"),
                    "Reply with just the word: PONG");

            LlmConfigProvider.ActiveConfig activeConfig = new LlmConfigProvider.ActiveConfig(
                    cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel(), endpoint);
            LlmResponse resp = llmClient.chat(activeConfig, req);

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
    public ResponseEntity<JavaProfile> createJavaProfile(@Valid @RequestBody JavaProfile incoming) {
        // 运行时路径白名单校验
        validateJavaHomePath(incoming.getJavaHome());

        JavaProfile profile = new JavaProfile();
        applyJavaProfileFields(profile, incoming);
        profile.setIsDefault(Boolean.FALSE);
        javaProfileRepo.save(profile);
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/java-profiles/{id}")
    public ResponseEntity<?> updateJavaProfile(@PathVariable Long id, @Valid @RequestBody JavaProfile incoming) {
        JavaProfile profile = javaProfileRepo.findById(id).orElse(null);
        if (profile == null) return ResponseEntity.notFound().build();

        // 运行时路径白名单校验
        validateJavaHomePath(incoming.getJavaHome());

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
        String endpoint = validateEndpoint(incoming.getEndpoint());
        cfg.setName(incoming.getName());
        cfg.setBaseUrl(incoming.getBaseUrl());
        cfg.setModel(incoming.getModel());
        cfg.setEndpoint(endpoint);
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
        copy.setBaseUrl(src.getBaseUrl());
        copy.setModel(src.getModel());
        copy.setEndpoint(src.getEndpoint());
        copy.setTemperature(src.getTemperature());
        copy.setMaxTokens(src.getMaxTokens());
        copy.setActive(src.isActive());
        copy.setApiKey(src.getApiKey() != null && !src.getApiKey().isEmpty() ? "••••••••" : "");
        return copy;
    }

    private String validateEndpoint(String endpoint) {
        return LlmEndpoint.fromPath(endpoint).getPath();
    }

    /**
     * 校验 Java Home 路径合法性
     *
     * 1. 路径必须是绝对路径
     * 2. 路径必须存在且为目录
     * 3. 必须包含 bin/java 或 bin/javac 可执行文件
     * 4. 路径必须在白名单目录内（防止命令注入）
     */
    private void validateJavaHomePath(String javaHome) {
        if (javaHome == null || javaHome.trim().isEmpty()) {
            throw new IllegalArgumentException("Java Home 路径不能为空");
        }

        Path path = Paths.get(javaHome);

        // 1. 必须是绝对路径
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Java Home 必须是绝对路径");
        }

        // 2. 路径必须存在且为目录
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("Java Home 路径不存在或不是目录");
        }

        // 3. 必须包含 bin/java 或 bin/javac
        Path javaBin = path.resolve("bin/java");
        Path javacBin = path.resolve("bin/javac");
        if (!Files.exists(javaBin) && !Files.exists(javacBin)) {
            throw new IllegalArgumentException("Java Home 目录中未找到 bin/java 或 bin/javac");
        }

        // 4. 白名单校验：只允许常见的 Java 安装目录
        String pathStr = path.toString();
        boolean allowed = pathStr.startsWith("/usr/lib/jvm/") ||
                         pathStr.startsWith("/usr/local/") ||
                         pathStr.startsWith("/opt/") ||
                         pathStr.startsWith("/Library/Java/") ||
                         pathStr.matches("^[A-Z]:\\\\Program Files\\\\.*") ||
                         pathStr.matches("^[A-Z]:\\\\Java\\\\.*");

        if (!allowed) {
            throw new SecurityException("Java Home 路径不在允许的目录范围内");
        }
    }
}

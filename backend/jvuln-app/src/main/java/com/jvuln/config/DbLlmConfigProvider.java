package com.jvuln.config;

import com.jvuln.llm.impl.LlmConfigProvider;
import com.jvuln.store.LlmConfigRepository;
import com.jvuln.store.entity.LlmConfig;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DbLlmConfigProvider implements LlmConfigProvider {

    private final LlmConfigRepository repo;

    public DbLlmConfigProvider(LlmConfigRepository repo) {
        this.repo = repo;
    }

    @Override
    public ActiveConfig getActive() {
        Optional<LlmConfig> opt = repo.findByActiveTrue();
        if (!opt.isPresent()) return null;
        LlmConfig cfg = opt.get();
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().trim().isEmpty()) return null;
        if (cfg.getModel()   == null || cfg.getModel().trim().isEmpty())   return null;
        return new ActiveConfig(cfg.getProviderType(), cfg.getBaseUrl(), cfg.getApiKey(), cfg.getModel());
    }
}

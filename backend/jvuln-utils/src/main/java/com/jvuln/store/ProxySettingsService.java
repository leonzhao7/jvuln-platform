package com.jvuln.store;

import com.jvuln.store.entity.ProxySettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProxySettingsService {

    private final ProxySettingsRepository repository;

    public ProxySettingsService(ProxySettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ProxySettings getSettings() {
        return repository.findAll().stream()
                .findFirst()
                .orElseGet(this::createDefaultSettings);
    }

    @Transactional
    public ProxySettings updateSettings(ProxySettings settings) {
        ProxySettings existing = repository.findAll().stream()
                .findFirst()
                .orElse(null);

        if (existing != null) {
            settings.setId(existing.getId());
        }
        return repository.save(settings);
    }

    private ProxySettings createDefaultSettings() {
        ProxySettings defaults = new ProxySettings();
        defaults.setProxyType("NONE");
        defaults.setProxyScope("url");
        defaults.setUrlConnectTimeout(5000);
        defaults.setUrlReadTimeout(8000);
        defaults.setLlmTimeout(300000);
        return defaults;
    }
}

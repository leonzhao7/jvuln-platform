package com.jvuln.store.entity;

import javax.persistence.*;

@Entity
@Table(name = "llm_config")
public class LlmConfig {

    @Id
    private Long id = 1L; // singleton row

    @Column(name = "provider_type", length = 30)
    private String providerType = "openai-compat"; // openai-compat | anthropic-proxy | ollama

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "api_key", length = 500)
    private String apiKey;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "temperature")
    private Double temperature = 0.1;

    @Column(name = "max_tokens")
    private Integer maxTokens = 8192;

    @Column(name = "enabled")
    private boolean enabled = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}

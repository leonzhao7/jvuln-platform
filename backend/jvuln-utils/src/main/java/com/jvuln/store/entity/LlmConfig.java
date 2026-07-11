package com.jvuln.store.entity;

import javax.persistence.*;

@Entity
@Table(name = "llm_config")
public class LlmConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "llm_config_seq")
    @SequenceGenerator(name = "llm_config_seq", sequenceName = "llm_config_seq", allocationSize = 1, initialValue = 100)
    private Long id;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "api_key", length = 500)
    private String apiKey;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "endpoint", length = 100)
    private String endpoint;

    @Column(name = "active")
    private Boolean active = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public boolean isActive() { return Boolean.TRUE.equals(active); }
    public void setActive(boolean active) { this.active = active; }

    @Override
    public String toString() {
        return "LlmConfig{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", apiKey='***'" + // 隐藏敏感信息
                ", model='" + model + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", active=" + active +
                '}';
    }
}

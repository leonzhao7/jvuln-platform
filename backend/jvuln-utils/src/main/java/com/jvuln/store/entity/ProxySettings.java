package com.jvuln.store.entity;

import javax.persistence.*;

@Entity
@Table(name = "proxy_settings")
public class ProxySettings {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "proxy_settings_seq")
    @SequenceGenerator(name = "proxy_settings_seq", sequenceName = "proxy_settings_seq", allocationSize = 1, initialValue = 1)
    private Long id;

    @Column(name = "proxy_type", length = 20)
    private String proxyType = "NONE";

    @Column(name = "proxy_host", length = 200)
    private String proxyHost;

    @Column(name = "proxy_port")
    private Integer proxyPort;

    @Column(name = "proxy_scope", length = 50)
    private String proxyScope = "url";

    @Column(name = "url_connect_timeout")
    private Integer urlConnectTimeout = 5000;

    @Column(name = "url_read_timeout")
    private Integer urlReadTimeout = 8000;

    @Column(name = "llm_timeout")
    private Integer llmTimeout = 300000;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProxyType() { return proxyType; }
    public void setProxyType(String proxyType) { this.proxyType = proxyType; }
    public String getProxyHost() { return proxyHost; }
    public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }
    public Integer getProxyPort() { return proxyPort; }
    public void setProxyPort(Integer proxyPort) { this.proxyPort = proxyPort; }
    public String getProxyScope() { return proxyScope; }
    public void setProxyScope(String proxyScope) { this.proxyScope = proxyScope; }
    public Integer getUrlConnectTimeout() { return urlConnectTimeout; }
    public void setUrlConnectTimeout(Integer urlConnectTimeout) { this.urlConnectTimeout = urlConnectTimeout; }
    public Integer getUrlReadTimeout() { return urlReadTimeout; }
    public void setUrlReadTimeout(Integer urlReadTimeout) { this.urlReadTimeout = urlReadTimeout; }
    public Integer getLlmTimeout() { return llmTimeout; }
    public void setLlmTimeout(Integer llmTimeout) { this.llmTimeout = llmTimeout; }

    @Override
    public String toString() {
        return "ProxySettings{" +
                "id=" + id +
                ", proxyType='" + proxyType + '\'' +
                ", proxyHost='" + proxyHost + '\'' +
                ", proxyPort=" + proxyPort +
                ", proxyScope='" + proxyScope + '\'' +
                ", urlConnectTimeout=" + urlConnectTimeout +
                ", urlReadTimeout=" + urlReadTimeout +
                ", llmTimeout=" + llmTimeout +
                '}';
    }
}

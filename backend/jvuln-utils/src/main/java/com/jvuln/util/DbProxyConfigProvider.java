package com.jvuln.util;

import com.jvuln.store.ProxySettingsService;
import com.jvuln.store.entity.ProxySettings;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class DbProxyConfigProvider {

    public enum ProxyType { SOCKS5, SOCKS4, HTTP, NONE }
    public enum Scope { LLM, URL }

    private final ProxySettingsService settingsService;

    public DbProxyConfigProvider(ProxySettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public RuntimeProxyConfig getProxyConfig() {
        ProxySettings settings = settingsService.getSettings();
        ProxyType type = parseType(settings.getProxyType());
        Set<Scope> scopes = parseScopes(settings.getProxyScope());
        return new RuntimeProxyConfig(
                type,
                settings.getProxyHost(),
                settings.getProxyPort() != null ? settings.getProxyPort() : 0,
                scopes,
                settings.getUrlConnectTimeout(),
                settings.getUrlReadTimeout(),
                settings.getLlmTimeout()
        );
    }

    private static ProxyType parseType(String value) {
        if (value == null || value.trim().isEmpty()) {
            return ProxyType.NONE;
        }
        try {
            return ProxyType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ProxyType.NONE;
        }
    }

    private static Set<Scope> parseScopes(String value) {
        if (value == null || value.trim().isEmpty() || "all".equalsIgnoreCase(value.trim())) {
            return Stream.of(Scope.values()).collect(Collectors.toSet());
        }
        return Stream.of(value.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Scope.valueOf(s.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(s -> s != null)
                .collect(Collectors.toSet());
    }

    public static class RuntimeProxyConfig {
        private final ProxyType type;
        private final String host;
        private final int port;
        private final Set<Scope> scopes;
        private final int urlConnectTimeout;
        private final int urlReadTimeout;
        private final int llmTimeout;

        public RuntimeProxyConfig(ProxyType type, String host, int port, Set<Scope> scopes,
                                  int urlConnectTimeout, int urlReadTimeout, int llmTimeout) {
            this.type = type;
            this.host = host;
            this.port = port;
            this.scopes = scopes;
            this.urlConnectTimeout = urlConnectTimeout;
            this.urlReadTimeout = urlReadTimeout;
            this.llmTimeout = llmTimeout;
        }

        public boolean isEnabled(Scope scope) {
            return type != ProxyType.NONE
                    && host != null && !host.isEmpty()
                    && port > 0
                    && scopes.contains(scope);
        }

        public ProxyType getType() { return type; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public int getUrlConnectTimeout() { return urlConnectTimeout; }
        public int getUrlReadTimeout() { return urlReadTimeout; }
        public int getLlmTimeout() { return llmTimeout; }

        public Proxy toJavaProxy() {
            if (type == ProxyType.NONE || host == null || host.isEmpty() || port <= 0) {
                return Proxy.NO_PROXY;
            }
            Proxy.Type javaType;
            switch (type) {
                case SOCKS5:
                case SOCKS4:
                    javaType = Proxy.Type.SOCKS;
                    break;
                case HTTP:
                    javaType = Proxy.Type.HTTP;
                    break;
                default:
                    return Proxy.NO_PROXY;
            }
            return new Proxy(javaType, new InetSocketAddress(host, port));
        }
    }
}

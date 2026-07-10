package com.jvuln.llm;

public enum LlmEndpoint {
    CHAT_COMPLETIONS("/v1/chat/completions"),
    RESPONSES("/v1/responses"),
    MESSAGES("/v1/messages");

    private final String path;

    LlmEndpoint(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    public static LlmEndpoint fromPath(String path) {
        if (path != null) {
            for (LlmEndpoint endpoint : values()) {
                if (endpoint.path.equals(path)) {
                    return endpoint;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported LLM endpoint: " + path);
    }

    public String resolveUri(String baseUrl) {
        String base = trimTrailingSlashes(baseUrl);
        if (base.isEmpty()) {
            throw new IllegalArgumentException("LLM base URL is required");
        }

        for (LlmEndpoint endpoint : values()) {
            if (endsWithIgnoreCase(base, endpoint.path)) {
                base = base.substring(0, base.length() - endpoint.path.length());
                break;
            }
        }
        if (endsWithIgnoreCase(base, "/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        base = trimTrailingSlashes(base);
        if (base.isEmpty()) {
            throw new IllegalArgumentException("LLM base URL is required");
        }
        return base + path;
    }

    private static String trimTrailingSlashes(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean endsWithIgnoreCase(String value, String suffix) {
        if (value.length() < suffix.length()) {
            return false;
        }
        return value.regionMatches(true, value.length() - suffix.length(),
                suffix, 0, suffix.length());
    }
}

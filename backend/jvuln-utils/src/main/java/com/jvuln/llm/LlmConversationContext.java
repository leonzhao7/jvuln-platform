package com.jvuln.llm;

import java.util.UUID;

/**
 * Per-CVE thread-local context for LLM requests. Holds a stable user_id
 * for the entire pipeline run so the LLM server can isolate KV cache
 * and scheduling per logical session.
 */
public final class LlmConversationContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private LlmConversationContext() {
    }

    public static void init() {
        USER_ID.set(UUID.randomUUID().toString().replace("-", ""));
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}

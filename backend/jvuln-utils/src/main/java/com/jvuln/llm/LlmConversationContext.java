package com.jvuln.llm;

/**
 * Per-CVE thread-local context for LLM requests.
 */
public final class LlmConversationContext {

    private static final ThreadLocal<String> RELEVANT_DIFF = new ThreadLocal<>();

    private LlmConversationContext() {
    }

    public static void setRelevantDiff(String diff) {
        if (diff != null && !diff.trim().isEmpty()) {
            RELEVANT_DIFF.set(diff);
        }
    }

    public static String getRelevantDiff() {
        return RELEVANT_DIFF.get();
    }

    public static void clear() {
        RELEVANT_DIFF.remove();
    }
}

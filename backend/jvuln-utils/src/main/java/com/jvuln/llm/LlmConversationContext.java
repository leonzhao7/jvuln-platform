package com.jvuln.llm;

/**
 * Per-CVE holder for the most recent LLM response id, so subsequent requests in
 * the same pipeline run can echo it back and let the server reuse its cache.
 *
 * Lifecycle is bound to the pipeline thread by {@code PipelineEngine}, mirroring
 * {@link LlmAuditLogger}'s ThreadLocal context.
 */
public final class LlmConversationContext {

    private static final ThreadLocal<String> LAST_RESPONSE_ID = new ThreadLocal<>();

    private LlmConversationContext() {
    }

    public static void setLastResponseId(String id) {
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        LAST_RESPONSE_ID.set(id);
    }

    public static String getLastResponseId() {
        return LAST_RESPONSE_ID.get();
    }

    public static void clear() {
        LAST_RESPONSE_ID.remove();
    }
}

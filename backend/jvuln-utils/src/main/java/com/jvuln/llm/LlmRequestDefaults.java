package com.jvuln.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Provider payload defaults applied to every outbound LLM request. */
public final class LlmRequestDefaults {

    public static final double TEMPERATURE = 0.1;
    public static final int MAX_TOKENS = 262144;

    private LlmRequestDefaults() {}

    public static void apply(ObjectNode body, String maxTokensField) {
        body.put("temperature", TEMPERATURE);
        body.put(maxTokensField, MAX_TOKENS);
        body.put("reasoning_effort", "xhigh");
    }
}

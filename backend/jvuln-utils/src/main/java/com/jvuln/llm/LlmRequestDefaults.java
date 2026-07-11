package com.jvuln.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Provider payload defaults applied to every outbound LLM request. */
public final class LlmRequestDefaults {

    public static final double TEMPERATURE = 0.0;
    public static final int MAX_TOKENS = 65_536;

    private LlmRequestDefaults() {}

    public static void apply(ObjectNode body, String maxTokensField) {
        body.put("temperature", TEMPERATURE);
        body.put(maxTokensField, MAX_TOKENS);
    }
}

package com.jvuln.llm;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmRequestTest {

    @Test
    void resolvedCopyPreservesRequestOptionsAndKeepsCurrentPrompt() {
        List<LlmRequest.Message> messages = Collections.singletonList(LlmRequest.Message.user("user"));
        List<LlmRequest.ToolDef> tools = Collections.singletonList(
                new LlmRequest.ToolDef("tool", "description", Collections.emptyMap()));
        LlmRequest request = LlmRequest.agent(
                LlmPromptStage.ARTIFACT_GENERATION, "current", messages, tools);

        LlmRequest resolved = request.withResolvedSystemPrompt("global\n\nstage\n\ncurrent");

        assertEquals(LlmPromptStage.ARTIFACT_GENERATION, resolved.getStage());
        assertEquals("current", resolved.getCurrentSystemPrompt());
        assertEquals("global\n\nstage\n\ncurrent", resolved.getSystemPrompt());
        assertSame(messages, resolved.getMessages());
        assertSame(tools, resolved.getTools());
        assertEquals("auto", resolved.getToolChoice());
        assertEquals(0.3, resolved.getTemperature());
        assertEquals(16384, resolved.getMaxTokens());
        assertEquals(false, resolved.isJsonMode());
    }

    @Test
    void reasoningRequestRequiresStage() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmRequest.reasoning(null, "current", "user"));
    }

    @Test
    void diagnosticRequestUsesOnlyItsCurrentPromptOutsideThePipeline() {
        LlmRequest request = LlmRequest.diagnostic("diagnostic", "user");

        assertNull(request.getStage());
        assertEquals("diagnostic", request.getCurrentSystemPrompt());
        assertEquals("diagnostic", request.getSystemPrompt());
        assertEquals(0.0, request.getTemperature());
        assertEquals(64, request.getMaxTokens());
    }
}

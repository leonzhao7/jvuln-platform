package com.jvuln.llm;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmRequestTest {

    @Test
    void agentRequestExposesTaskPromptAndPreservesOptions() {
        List<LlmRequest.Message> messages = Collections.singletonList(LlmRequest.Message.user("user"));
        List<LlmRequest.ToolDef> tools = Collections.singletonList(
                new LlmRequest.ToolDef("tool", "description", Collections.emptyMap()));
        LlmRequest request = LlmRequest.agent(
                LlmPromptStage.ARTIFACT_GENERATION, "task", messages, tools);

        assertEquals(LlmPromptStage.ARTIFACT_GENERATION, request.getStage());
        assertEquals("task", request.getTaskPrompt());
        assertSame(messages, request.getMessages());
        assertSame(tools, request.getTools());
        assertEquals("auto", request.getToolChoice());
        assertFalse(request.isJsonMode());
    }

    @Test
    void reasoningRequestRequiresStage() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmRequest.reasoning(null, "task", "user"));
    }

    @Test
    void diagnosticRequestUsesTaskPromptOutsideThePipeline() {
        LlmRequest request = LlmRequest.diagnostic("diagnostic", "user");

        assertNull(request.getStage());
        assertEquals("diagnostic", request.getTaskPrompt());
    }
}

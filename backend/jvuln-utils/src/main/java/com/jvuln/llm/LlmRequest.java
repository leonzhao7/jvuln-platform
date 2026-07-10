package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

public class LlmRequest {

    private final LlmPromptStage stage;
    private final String currentSystemPrompt;
    private final String systemPrompt;
    private final List<Message> messages;
    private final double temperature;
    private final int maxTokens;
    private final boolean jsonMode;
    private final List<ToolDef> tools;
    private final String toolChoice;

    public LlmRequest(LlmPromptStage stage, String currentSystemPrompt, List<Message> messages,
                      double temperature, int maxTokens, boolean jsonMode) {
        this(requireStage(stage), currentSystemPrompt, currentSystemPrompt, messages,
                temperature, maxTokens, jsonMode, null, null);
    }

    public LlmRequest(LlmPromptStage stage, String currentSystemPrompt, List<Message> messages,
                      double temperature, int maxTokens, boolean jsonMode,
                      List<ToolDef> tools, String toolChoice) {
        this(requireStage(stage), currentSystemPrompt, currentSystemPrompt, messages,
                temperature, maxTokens, jsonMode, tools, toolChoice);
    }

    private LlmRequest(LlmPromptStage stage, String currentSystemPrompt, String systemPrompt,
                       List<Message> messages, double temperature, int maxTokens, boolean jsonMode,
                       List<ToolDef> tools, String toolChoice) {
        this.stage = stage;
        this.currentSystemPrompt = currentSystemPrompt;
        this.systemPrompt = systemPrompt;
        this.messages = messages;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.jsonMode = jsonMode;
        this.tools = tools;
        this.toolChoice = toolChoice;
    }

    public static LlmRequest reasoning(LlmPromptStage stage, String currentSystemPrompt,
                                       String userContent) {
        return new LlmRequest(stage, currentSystemPrompt,
                Collections.singletonList(Message.user(userContent)), 0.1, 8192, true);
    }

    /**
     * Creates a current-only request for provider connectivity diagnostics that are not part of a pipeline stage.
     */
    public static LlmRequest diagnostic(String currentSystemPrompt, String userContent) {
        return new LlmRequest(null, currentSystemPrompt, currentSystemPrompt,
                Collections.singletonList(Message.user(userContent)), 0.0, 64, false, null, null);
    }

    public static LlmRequest generation(LlmPromptStage stage, String currentSystemPrompt,
                                        String userContent) {
        return new LlmRequest(stage, currentSystemPrompt,
                Collections.singletonList(Message.user(userContent)), 0.3, 16384, false);
    }

    public static LlmRequest generation(LlmPromptStage stage, String currentSystemPrompt,
                                        List<Message> messages) {
        return new LlmRequest(stage, currentSystemPrompt, messages, 0.3, 16384, false);
    }

    public static LlmRequest agent(LlmPromptStage stage, String currentSystemPrompt,
                                   List<Message> messages, List<ToolDef> tools) {
        return new LlmRequest(stage, currentSystemPrompt, messages,
                0.3, 16384, false, tools, "auto");
    }

    public LlmPromptStage getStage() { return stage; }
    public String getCurrentSystemPrompt() { return currentSystemPrompt; }
    public String getSystemPrompt() { return systemPrompt; }
    public List<Message> getMessages() { return messages; }
    public double getTemperature() { return temperature; }
    public int getMaxTokens() { return maxTokens; }
    public boolean isJsonMode() { return jsonMode; }
    public List<ToolDef> getTools() { return tools; }
    public String getToolChoice() { return toolChoice; }
    public boolean hasTools() { return tools != null && !tools.isEmpty(); }

    public LlmRequest withResolvedSystemPrompt(String resolvedSystemPrompt) {
        if (stage == null) {
            throw new IllegalStateException("LLM prompt stage is required");
        }
        return new LlmRequest(stage, currentSystemPrompt, resolvedSystemPrompt, messages,
                temperature, maxTokens, jsonMode, tools, toolChoice);
    }

    private static LlmPromptStage requireStage(LlmPromptStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("LLM prompt stage is required");
        }
        return stage;
    }

    // ==================== Message ====================

    public static class Message {
        private final String role;
        private final Object content; // String or List<ContentBlock>

        public Message(String role, Object content) {
            this.role = role;
            this.content = content;
        }

        public static Message user(String content) { return new Message("user", content); }
        public static Message assistant(String content) { return new Message("assistant", content); }

        public static Message assistantWithBlocks(List<ContentBlock> blocks) {
            return new Message("assistant", blocks);
        }

        public static Message toolResults(List<ContentBlock> toolResultBlocks) {
            return new Message("user", toolResultBlocks);
        }

        public String getRole() { return role; }
        public Object getContent() { return content; }

        @SuppressWarnings("unchecked")
        public List<ContentBlock> getContentBlocks() {
            if (content instanceof List) return (List<ContentBlock>) content;
            return null;
        }

        public String getTextContent() {
            if (content instanceof String) return (String) content;
            return null;
        }
    }

    // ==================== ContentBlock ====================

    public static class ContentBlock {
        private final String type;
        private String text;
        private String toolUseId;
        private String toolName;
        private JsonNode toolInput;
        private String toolResultContent;
        private boolean isError;

        private ContentBlock(String type) { this.type = type; }

        public static ContentBlock text(String text) {
            ContentBlock b = new ContentBlock("text");
            b.text = text;
            return b;
        }

        public static ContentBlock toolUse(String id, String name, JsonNode input) {
            ContentBlock b = new ContentBlock("tool_use");
            b.toolUseId = id;
            b.toolName = name;
            b.toolInput = input;
            return b;
        }

        public static ContentBlock toolResult(String toolUseId, String content) {
            ContentBlock b = new ContentBlock("tool_result");
            b.toolUseId = toolUseId;
            b.toolResultContent = content;
            b.isError = false;
            return b;
        }

        public static ContentBlock toolResultError(String toolUseId, String content) {
            ContentBlock b = new ContentBlock("tool_result");
            b.toolUseId = toolUseId;
            b.toolResultContent = content;
            b.isError = true;
            return b;
        }

        public String getType() { return type; }
        public String getText() { return text; }
        public String getToolUseId() { return toolUseId; }
        public String getToolName() { return toolName; }
        public JsonNode getToolInput() { return toolInput; }
        public String getToolResultContent() { return toolResultContent; }
        public boolean isError() { return isError; }
    }

    // ==================== ToolDef ====================

    public static class ToolDef {
        private final String name;
        private final String description;
        private final Map<String, Object> inputSchema;

        public ToolDef(String name, String description, Map<String, Object> inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public Map<String, Object> getInputSchema() { return inputSchema; }
    }
}

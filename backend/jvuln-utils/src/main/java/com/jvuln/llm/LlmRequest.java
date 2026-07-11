package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

public class LlmRequest {

    private final LlmPromptStage stage;
    private final String taskPrompt;
    private final List<Message> messages;
    private final boolean jsonMode;
    private final List<ToolDef> tools;
    private final String toolChoice;

    public LlmRequest(LlmPromptStage stage, String taskPrompt, List<Message> messages,
                      boolean jsonMode) {
        this(requireStage(stage), taskPrompt, messages, jsonMode, null, null);
    }

    public LlmRequest(LlmPromptStage stage, String taskPrompt, List<Message> messages,
                      boolean jsonMode,
                      List<ToolDef> tools, String toolChoice) {
        this(stage, taskPrompt, messages, jsonMode, tools, toolChoice, false);
    }

    private LlmRequest(LlmPromptStage stage, String taskPrompt,
                       List<Message> messages, boolean jsonMode,
                       List<ToolDef> tools, String toolChoice, boolean allowMissingStage) {
        this.stage = allowMissingStage ? stage : requireStage(stage);
        this.taskPrompt = taskPrompt;
        this.messages = messages;
        this.jsonMode = jsonMode;
        this.tools = tools;
        this.toolChoice = toolChoice;
    }

    public static LlmRequest reasoning(LlmPromptStage stage, String taskPrompt,
                                       String userContent) {
        return new LlmRequest(stage, taskPrompt,
                Collections.singletonList(Message.user(userContent)), true);
    }

    /**
     * Creates a task request for provider connectivity diagnostics that are not part of a pipeline stage.
     */
    public static LlmRequest diagnostic(String taskPrompt, String userContent) {
        return new LlmRequest(null, taskPrompt,
                Collections.singletonList(Message.user(userContent)), false, null, null, true);
    }

    public static LlmRequest generation(LlmPromptStage stage, String taskPrompt,
                                        String userContent) {
        return new LlmRequest(stage, taskPrompt,
                Collections.singletonList(Message.user(userContent)), false);
    }

    public static LlmRequest generation(LlmPromptStage stage, String taskPrompt,
                                        List<Message> messages) {
        return new LlmRequest(stage, taskPrompt, messages, false);
    }

    public static LlmRequest agent(LlmPromptStage stage, String taskPrompt,
                                   List<Message> messages, List<ToolDef> tools) {
        return new LlmRequest(stage, taskPrompt, messages,
                false, tools, "auto");
    }

    public LlmPromptStage getStage() { return stage; }
    public String getTaskPrompt() { return taskPrompt; }
    public List<Message> getMessages() { return messages; }
    public boolean isJsonMode() { return jsonMode; }
    public List<ToolDef> getTools() { return tools; }
    public String getToolChoice() { return toolChoice; }
    public boolean hasTools() { return tools != null && !tools.isEmpty(); }

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

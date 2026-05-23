package com.jvuln.llm;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LlmRequest {

    private final String systemPrompt;
    private final List<Message> messages;
    private final double temperature;
    private final int maxTokens;
    private final boolean jsonMode;

    public LlmRequest(String systemPrompt, List<Message> messages, double temperature,
                      int maxTokens, boolean jsonMode) {
        this.systemPrompt = systemPrompt;
        this.messages = messages;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.jsonMode = jsonMode;
    }

    public static LlmRequest reasoning(String systemPrompt, String userContent) {
        return new LlmRequest(systemPrompt, Collections.singletonList(Message.user(userContent)),
                0.1, 8192, true);
    }

    public static LlmRequest generation(String systemPrompt, String userContent) {
        return new LlmRequest(systemPrompt, Collections.singletonList(Message.user(userContent)),
                0.3, 16384, false);
    }

    public String getSystemPrompt() { return systemPrompt; }
    public List<Message> getMessages() { return messages; }
    public double getTemperature() { return temperature; }
    public int getMaxTokens() { return maxTokens; }
    public boolean isJsonMode() { return jsonMode; }

    public static class Message {
        private final String role;
        private final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static Message user(String content) { return new Message("user", content); }
        public static Message assistant(String content) { return new Message("assistant", content); }

        public String getRole() { return role; }
        public String getContent() { return content; }
    }
}

package com.jvuln.generator;

import static com.jvuln.generator.ArtifactGenUtils.truncateHead;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.generator.workspace.WorkspaceFileRenderer;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

@Component
class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);
    private static final int CONTEXT_COMPACT_CHAR_LIMIT = 800_000;
    private static final int CONTEXT_DIFF_LIMIT = 240_000;
    private static final int TRANSCRIPT_ENTRY_LIMIT = 100_000;
    private static final int MEMORY_OUTPUT_TRUNCATE = 16_000;

    private final LlmHelper llmHelper;
    private final WorkspaceFileRenderer fileRenderer;
    private final AttemptMemoryManager memoryManager;
    private final ObjectMapper mapper;

    ContextBuilder(LlmHelper llmHelper, WorkspaceFileRenderer fileRenderer,
                   AttemptMemoryManager memoryManager, ObjectMapper mapper) {
        this.llmHelper = llmHelper;
        this.fileRenderer = fileRenderer;
        this.memoryManager = memoryManager;
        this.mapper = mapper;
    }

    Map<String, Object> buildSessionStartEvent(AgentContext ctx, int resumedTurns) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("cveId", ctx.pipeCtx.getCveId());
        event.put("resumedTurns", resumedTurns);
        event.put("phase", ctx.phase == null ? "" : ctx.phase.name());
        event.put("existingFiles", new ArrayList<>(ctx.existingFiles));
        if (ctx.verificationPlan != null) {
            event.put("verificationPlan", ctx.verificationPlan.toMap());
        }
        if (ctx.sessionSummary != null && !ctx.sessionSummary.trim().isEmpty()) {
            event.put("restoredSessionSummary", ctx.sessionSummary);
        }
        return event;
    }

    String buildContextPacket(AgentContext ctx, int turn, int maxAgentTurns) {
        Map<String, FileSnapshot> current = captureWorkspaceSnapshot(ctx);
        String diffFromPrevious = fileRenderer.renderSnapshotDiff(ctx.previousSnapshot, current, CONTEXT_DIFF_LIMIT / 2);
        String diffFromBaseline = fileRenderer.renderSnapshotDiff(ctx.baselineSnapshot, current, CONTEXT_DIFF_LIMIT / 2);
        ctx.previousSnapshot = new LinkedHashMap<>(current);

        StringBuilder sb = new StringBuilder();
        sb.append("BACKEND CONTEXT PACKET\n");
        sb.append("This packet is authoritative for the current workspace state. ");
        sb.append("Do not rely on memory if it conflicts with this packet.\n");
        sb.append("Turn: ").append(turn).append("/").append(maxAgentTurns).append("\n");
        sb.append("Phase: ").append(ctx.phase == null ? "" : ctx.phase.name()).append("\n");
        if (ctx.lastDirective != null) {
            sb.append("\n## Current Directive\n");
            sb.append(llmHelper.renderJson(ctx.lastDirective.toMap())).append("\n");
        }
        if (ctx.executionPlan != null) {
            sb.append("\n## Accepted Execution Plan\n");
            sb.append(llmHelper.renderJson(ctx.executionPlan.toMap())).append("\n");
        }
        if (ctx.lastValidation != null) {
            sb.append("\n## Latest Backend Validation\n");
            sb.append(llmHelper.renderJson(ctx.lastValidation.toMap())).append("\n");
        }
        if (ctx.verificationReview != null) {
            sb.append("\n## Latest Reviewer Verdict\n");
            sb.append(llmHelper.renderJson(ctx.verificationReview.toMap())).append("\n");
        }
        if (ctx.sessionSummary != null && !ctx.sessionSummary.trim().isEmpty()) {
            sb.append("\n## Compacted Session Summary\n");
            sb.append(ctx.sessionSummary.trim()).append("\n");
        }
        if (ctx.attemptMemory != null && ctx.attemptMemory.hasRecords()) {
            sb.append("\n## Prior Attempt Memory\n");
            sb.append(memoryManager.renderAttemptMemory(ctx.attemptMemory)).append("\n");
        }

        sb.append("\n## Workspace Manifest\n");
        sb.append(fileRenderer.renderFileManifest(current)).append("\n");

        sb.append("\n## Current File Contents\n");
        sb.append(fileRenderer.renderCurrentFileContents(current)).append("\n");

        sb.append("\n## Diff: Previous Turn -> Current Workspace\n");
        sb.append(diffFromPrevious.isEmpty() ? "(no file changes since previous context packet)" : diffFromPrevious).append("\n");

        sb.append("\n## Diff: Initial Workspace -> Current Workspace\n");
        sb.append(diffFromBaseline.isEmpty() ? "(no file changes from initial workspace)" : diffFromBaseline).append("\n");

        sb.append("\nUse write_files for the next concrete patch. Use read_file only when a needed file is omitted or marked truncated.");
        return sb.toString();
    }

    void compactMessagesIfNeeded(List<LlmRequest.Message> messages, AgentContext ctx, String reason) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int chars = estimateMessagesChars(messages);
        if (chars < CONTEXT_COMPACT_CHAR_LIMIT) {
            return;
        }

        String summary = buildCompactedSessionSummary(ctx, reason, chars);
        ctx.sessionSummary = summary;
        writeContextSummary(ctx, summary);

        messages.clear();
        messages.add(LlmRequest.Message.user(ctx.baseUserPrompt == null ? "" : ctx.baseUserPrompt));
        messages.add(LlmRequest.Message.user("COMPACTED SESSION SUMMARY\n" + summary
                + "\nThe next BACKEND CONTEXT PACKET is authoritative for current files and validation state."));
        ctx.compactionCount++;
        appendTranscript(ctx, "compact", ctx.turns, summary);
        log.info("Compacted Stage 4 agent context: reason={} previousChars={} compactions={}",
                reason, chars, ctx.compactionCount);
    }

    private String buildCompactedSessionSummary(AgentContext ctx, String reason, int previousChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("Reason: ").append(reason).append("\n");
        sb.append("Previous message chars: ").append(previousChars).append("\n");
        sb.append("CVE: ").append(ctx.pipeCtx.getCveId()).append("\n");
        sb.append("Phase: ").append(ctx.phase == null ? "" : ctx.phase.name()).append("\n");
        sb.append("Turns completed in this attempt: ").append(ctx.turns).append("\n");
        sb.append("Review revisions: ").append(ctx.reviewRevisions).append("\n");
        if (ctx.executionPlan != null) {
            sb.append("\nExecution plan:\n").append(llmHelper.renderJson(ctx.executionPlan.toMap())).append("\n");
        }
        if (ctx.lastDirective != null) {
            sb.append("\nCurrent directive:\n").append(llmHelper.renderJson(ctx.lastDirective.toMap())).append("\n");
        }
        if (ctx.lastValidation != null) {
            sb.append("\nLatest validation:\n").append(llmHelper.renderJson(ctx.lastValidation.toMap())).append("\n");
        }
        if (ctx.verificationReview != null) {
            sb.append("\nLatest review:\n").append(llmHelper.renderJson(ctx.verificationReview.toMap())).append("\n");
        }
        if (!ctx.buildHistory.isEmpty()) {
            sb.append("\nRecent build history:\n").append(llmHelper.renderJson(llmHelper.recentHistory(ctx.buildHistory))).append("\n");
        }
        if (!ctx.startupHistory.isEmpty()) {
            sb.append("\nRecent startup history:\n").append(llmHelper.renderJson(llmHelper.recentHistory(ctx.startupHistory))).append("\n");
        }
        if (!ctx.commandHistory.isEmpty()) {
            sb.append("\nRecent command history:\n").append(llmHelper.renderJson(llmHelper.recentHistory(ctx.commandHistory))).append("\n");
        }
        Map<String, FileSnapshot> current = captureWorkspaceSnapshot(ctx);
        sb.append("\nCurrent workspace manifest:\n").append(fileRenderer.renderFileManifest(current)).append("\n");
        if (ctx.abortReason != null && !ctx.abortReason.trim().isEmpty()) {
            sb.append("\nAbort reason:\n").append(ctx.abortReason).append("\n");
        }
        return sb.toString().trim();
    }

    private int estimateMessagesChars(List<LlmRequest.Message> messages) {
        int total = 0;
        for (LlmRequest.Message message : messages) {
            total += message == null ? 0 : estimateContentChars(message.getContent());
        }
        return total;
    }

    private int estimateContentChars(Object content) {
        if (content == null) {
            return 0;
        }
        if (content instanceof String) {
            return ((String) content).length();
        }
        if (content instanceof List) {
            int total = 0;
            for (Object item : (List<?>) content) {
                if (item instanceof LlmRequest.ContentBlock) {
                    LlmRequest.ContentBlock block = (LlmRequest.ContentBlock) item;
                    total += safeLength(block.getText());
                    total += safeLength(block.getToolName());
                    total += block.getToolInput() == null ? 0 : block.getToolInput().toString().length();
                    total += safeLength(block.getToolResultContent());
                } else if (item != null) {
                    total += item.toString().length();
                }
            }
            return total;
        }
        return content.toString().length();
    }

    private int safeLength(String s) {
        return s == null ? 0 : s.length();
    }

    Map<String, FileSnapshot> captureWorkspaceSnapshot(AgentContext ctx) {
        Map<String, FileSnapshot> files = new TreeMap<>();
        for (String prefix : Arrays.asList("vuln-demo", "poc", "report")) {
            Path dir = ctx.cvePath.resolve(prefix);
            if (!Files.exists(dir)) {
                continue;
            }
            try {
                Files.walk(dir)
                        .filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                        .forEach(p -> {
                            try {
                                String rel = ctx.cvePath.relativize(p).toString();
                                files.put(rel, FileSnapshot.fromPath(ctx.cvePath, p, rel));
                            } catch (Exception ignored) {
                                // Snapshot is best-effort; read_file remains available for exact recovery.
                            }
                        });
            } catch (Exception ignored) {
                // Continue with other workspace roots.
            }
        }
        return new LinkedHashMap<>(files);
    }

    void appendTranscript(AgentContext ctx, String type, int turn, Object payload) {
        if (ctx == null || ctx.transcriptFile == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("ts", System.currentTimeMillis());
            event.put("turn", turn);
            event.put("type", type);
            event.put("phase", ctx.phase == null ? "" : ctx.phase.name());
            event.put("payload", trimTranscriptPayload(payload));
            Files.createDirectories(ctx.transcriptFile.getParent());
            String line = mapper.writeValueAsString(event) + "\n";
            Files.write(ctx.transcriptFile, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("Failed to append Stage 4 transcript: {}", e.getMessage());
        }
    }

    private Object trimTranscriptPayload(Object payload) {
        if (payload == null) {
            return "";
        }
        if (payload instanceof String) {
            return truncateHead((String) payload, TRANSCRIPT_ENTRY_LIMIT);
        }
        try {
            String json = mapper.writeValueAsString(payload);
            if (json.length() <= TRANSCRIPT_ENTRY_LIMIT) {
                return payload;
            }
            return truncateHead(json, TRANSCRIPT_ENTRY_LIMIT);
        } catch (Exception e) {
            return truncateHead(String.valueOf(payload), TRANSCRIPT_ENTRY_LIMIT);
        }
    }

    private void writeContextSummary(AgentContext ctx, String summary) {
        if (ctx == null || ctx.contextSummaryFile == null || summary == null) {
            return;
        }
        try {
            Files.createDirectories(ctx.contextSummaryFile.getParent());
            Files.write(ctx.contextSummaryFile, summary.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Failed to write Stage 4 context summary: {}", e.getMessage());
        }
    }

    Map<String, Object> buildAssistantEvent(LlmResponse response) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("finishReason", response == null ? "" : response.getFinishReason());
        event.put("text", response == null ? "" : responsePreview(response));
        event.put("toolUses", response == null ? Collections.emptyList() : toolUseSummaries(response.getToolUses()));
        return event;
    }

    String responsePreview(LlmResponse response) {
        if (response == null) {
            return "";
        }
        String content = response.getContent();
        if (content != null && !content.trim().isEmpty()) {
            return ArtifactGenUtils.singleLine(content, 260);
        }
        StringBuilder sb = new StringBuilder();
        for (LlmRequest.ContentBlock block : response.getContentBlocks()) {
            if ("text".equals(block.getType()) && block.getText() != null && !block.getText().trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(block.getText().trim());
            }
        }
        return ArtifactGenUtils.singleLine(sb.toString(), 260);
    }

    private List<Map<String, Object>> toolUseSummaries(List<LlmRequest.ContentBlock> toolUses) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (toolUses == null) {
            return out;
        }
        for (LlmRequest.ContentBlock block : toolUses) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", block.getToolName());
            item.put("input", summarizeToolInput(block.getToolName(), block.getToolInput()));
            out.add(item);
        }
        return out;
    }

    String summarizeToolInput(String toolName, com.fasterxml.jackson.databind.JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "{}";
        }
        if ("write_file".equals(toolName)) {
            return "{path=" + input.path("path").asText("") + ", contentLength="
                    + input.path("content").asText("").length() + "}";
        }
        if ("write_files".equals(toolName)) {
            List<String> paths = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode files = input.path("files");
            if (files.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode file : files) {
                    paths.add(file.path("path").asText(""));
                }
            }
            return "{count=" + paths.size() + ", paths=" + paths + "}";
        }
        if ("read_file".equals(toolName)) {
            return "{path=" + input.path("path").asText("") + "}";
        }
        if ("read_log".equals(toolName)) {
            return "{path=" + input.path("path").asText("")
                    + ", tailBytes=" + input.path("tailBytes").asInt(4000) + "}";
        }
        if ("validate_artifacts".equals(toolName)) {
            return "{focus=" + input.path("focus").asText("full") + "}";
        }
        if ("submit_plan".equals(toolName)) {
            return "{goal=" + ArtifactGenUtils.singleLine(input.path("goal").asText(""), 120)
                    + ", firstBatchFiles=" + input.path("firstBatchFiles").size()
                    + ", validationSequence=" + input.path("validationSequence").size() + "}";
        }
        return ArtifactGenUtils.singleLine(input.toString(), 260);
    }

    List<Map<String, Object>> buildToolResultEvent(List<LlmRequest.ContentBlock> toolResults) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (toolResults == null) {
            return out;
        }
        for (LlmRequest.ContentBlock block : toolResults) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("isError", block.isError());
            item.put("content", truncateHead(block.getToolResultContent(), MEMORY_OUTPUT_TRUNCATE));
            out.add(item);
        }
        return out;
    }
}

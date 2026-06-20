package com.jvuln.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
class AttemptMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(AttemptMemoryManager.class);

    private final ObjectMapper mapper;
    private final LlmHelper llmHelper;

    AttemptMemoryManager(ObjectMapper mapper, LlmHelper llmHelper) {
        this.mapper = mapper;
        this.llmHelper = llmHelper;
    }

    AttemptMemory loadAttemptMemory(Path file) {
        if (!Files.exists(file)) {
            return new AttemptMemory();
        }
        try {
            return AttemptMemory.fromJson(mapper.readTree(file.toFile()));
        } catch (Exception e) {
            log.warn("Could not load Stage 4 memory: {}", e.getMessage());
            return new AttemptMemory();
        }
    }

    void persistAttemptMemory(Path file, AgentContext ctx, String event, String reason) {
        try {
            AttemptMemory memory = ctx.attemptMemory != null ? ctx.attemptMemory : new AttemptMemory();
            memory.turns = ctx.turns;
            memory.reviewRevisions = ctx.reviewRevisions;
            memory.verificationPlan = ctx.verificationPlan;
            memory.executionPlan = ctx.executionPlan;
            memory.lastValidation = ctx.lastValidation;
            memory.lastReview = ctx.verificationReview;
            memory.sessionSummary = ctx.sessionSummary;
            memory.buildHistory.clear();
            memory.buildHistory.addAll(ctx.buildHistory);
            memory.startupHistory.clear();
            memory.startupHistory.addAll(ctx.startupHistory);
            memory.commandHistory.clear();
            memory.commandHistory.addAll(ctx.commandHistory);
            memory.addRecord(AttemptRecord.fromContext(event, reason,
                    ctx.deriveCompileStatus(), ctx.deriveStartupStatus(),
                    ctx.verificationReview != null ? ctx.verificationReview.pocStatus
                            : (ctx.summary != null ? ctx.summary.path("poc_status").asText("") : ""),
                    ctx.turns, ctx.reviewRevisions, ctx.lastValidation));
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), memory.toMap());
            ctx.attemptMemory = memory;
        } catch (Exception e) {
            log.warn("Failed to persist Stage 4 memory: {}", e.getMessage());
        }
    }

    void restoreAgentContextFromMemory(AgentContext ctx, AttemptMemory memory) {
        if (memory == null) {
            return;
        }
        // Do NOT restore executionPlan — Agent must always create a fresh plan
        // based on the current CVE context. Prior plans may be stale or wrong.
        ctx.lastValidation = memory.lastValidation;
        ctx.verificationReview = memory.lastReview;
        ctx.sessionSummary = memory.sessionSummary == null ? "" : memory.sessionSummary;
        restoreToolRuns(ctx.buildHistory, memory.buildHistory);
        restoreToolRuns(ctx.startupHistory, memory.startupHistory);
        restoreToolRuns(ctx.commandHistory, memory.commandHistory);
    }

    private void restoreToolRuns(List<ToolRun> target, List<ToolRun> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        target.clear();
        target.addAll(source);
    }

    String renderAttemptMemory(AttemptMemory memory) {
        StringBuilder sb = new StringBuilder();
        if (memory.executionPlan != null) {
            sb.append("Execution plan:\n");
            sb.append(llmHelper.renderJson(memory.executionPlan.toMap())).append("\n");
        }
        if (memory.lastValidation != null) {
            sb.append("Latest validator result:\n");
            sb.append(llmHelper.renderJson(memory.lastValidation.toMap())).append("\n");
        }
        if (memory.lastReview != null) {
            sb.append("Latest review result:\n");
            sb.append(llmHelper.renderJson(memory.lastReview.toMap())).append("\n");
        }
        if (memory.sessionSummary != null && !memory.sessionSummary.trim().isEmpty()) {
            sb.append("Compacted session summary:\n");
            sb.append(memory.sessionSummary.trim()).append("\n");
        }
        if (!memory.records.isEmpty()) {
            sb.append("Recent failures and decisions:\n");
            for (AttemptRecord record : memory.records) {
                sb.append("- [").append(record.event).append("] ");
                if (!record.reason.isEmpty()) {
                    sb.append(record.reason).append(" | ");
                }
                sb.append("compile=").append(record.compileStatus)
                        .append(", startup=").append(record.startupStatus)
                        .append(", poc=").append(record.pocStatus)
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }
}

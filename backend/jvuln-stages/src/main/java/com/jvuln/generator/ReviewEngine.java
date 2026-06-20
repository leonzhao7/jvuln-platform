package com.jvuln.generator;

import static com.jvuln.generator.ArtifactGenUtils.singleLine;
import static com.jvuln.generator.ArtifactGenUtils.truncate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.pipeline.model.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Component
class ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(ReviewEngine.class);
    private static final int REVIEW_FILE_SNIPPET = 2000;
    private static final int MAX_REVIEW_REVISIONS = 4;

    private final PromptRegistry promptRegistry;
    private final ObjectMapper mapper;
    private final LlmHelper llmHelper;

    ReviewEngine(PromptRegistry promptRegistry, ObjectMapper mapper, LlmHelper llmHelper) {
        this.promptRegistry = promptRegistry;
        this.mapper = mapper;
        this.llmHelper = llmHelper;
    }

    VerificationReview reviewGeneratedArtifacts(PipelineContext ctx, AgentContext agentCtx, JsonNode finishSummary,
                                                String intelligence, String vulnerabilityFacts,
                                                String triggerChain, String rootCause, String patchDiff,
                                                String artifact) {
        try {
            String systemPrompt = promptRegistry.getSystemPrompt("gen_verifier");
            String userTemplate = promptRegistry.getUserPrompt("gen_verifier");
            Map<String, String> vars = new HashMap<>();
            vars.put("intelligence", intelligence);
            vars.put("vulnerability_facts", vulnerabilityFacts);
            vars.put("trigger_chain", triggerChain);
            vars.put("root_cause", rootCause);
            vars.put("patch_diff", patchDiff);
            vars.put("artifact", artifact);
            vars.put("verification_plan", llmHelper.renderJson(agentCtx.verificationPlan.toMap()));
            vars.put("finish_summary", finishSummary == null ? "{}" : finishSummary.toPrettyString());
            vars.put("execution_evidence", llmHelper.renderJson(buildVerificationEvidence(agentCtx, finishSummary)));
            String userPrompt = promptRegistry.render(userTemplate, vars);
            LlmResponse response = llmHelper.chatWithRetry(ctx, LlmRequest.reasoning(systemPrompt, userPrompt), 2);
            return reconcileReviewWithBackend(
                    VerificationReview.fromJson(llmHelper.parseJsonObject(response.getContent())), agentCtx, finishSummary);
        } catch (Exception e) {
            log.warn("Verification review failed, using fallback: {}", e.getMessage());
            return VerificationReview.fallback(finishSummary, agentCtx.deriveCompileStatus(),
                    agentCtx.deriveStartupStatus(), agentCtx.lastValidation);
        }
    }

    private VerificationReview reconcileReviewWithBackend(VerificationReview review, AgentContext agentCtx,
                                                          JsonNode finishSummary) {
        ValidationResult validation = agentCtx.lastValidation;
        boolean backendVerified = validation != null
                && validation.compileOk && validation.startupOk && validation.pocVerified;
        if (!backendVerified || review == null || "verified".equals(review.pocStatus)) {
            return review;
        }

        log.warn("Reviewer returned {} despite backend validation success; using backend verified verdict. reason={}",
                review.pocStatus, singleLine(review.reason, 300));
        return VerificationReview.fallback(finishSummary, agentCtx.deriveCompileStatus(),
                agentCtx.deriveStartupStatus(), validation);
    }

    private Map<String, Object> buildVerificationEvidence(AgentContext ctx, JsonNode finishSummary) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("compileStatus", ctx.deriveCompileStatus());
        evidence.put("startupStatus", ctx.deriveStartupStatus());
        evidence.put("writtenFiles", new ArrayList<>(ctx.writtenFiles));
        evidence.put("reviewRevisions", ctx.reviewRevisions);
        evidence.put("finishSummary", finishSummary == null ? mapper.createObjectNode() : finishSummary);
        evidence.put("buildHistory", llmHelper.recentHistory(ctx.buildHistory));
        evidence.put("startupHistory", llmHelper.recentHistory(ctx.startupHistory));
        evidence.put("commandHistory", llmHelper.recentHistory(ctx.commandHistory));

        Map<String, String> snippets = collectKeyFileSnippets(ctx);
        if (!snippets.isEmpty()) {
            evidence.put("keyFileSnippets", snippets);
        }
        return evidence;
    }

    private Map<String, String> collectKeyFileSnippets(AgentContext ctx) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add("vuln-demo/pom.xml");
        candidates.add("vuln-demo/src/main/resources/application.properties");
        candidates.add("poc/exploit.sh");
        candidates.add("report/report.md");
        for (String path : ctx.writtenFiles) {
            if (path.startsWith("vuln-demo/src/main/resources/")) {
                candidates.add(path);
            }
        }
        for (String path : ctx.writtenFiles) {
            if (path.startsWith("vuln-demo/src/main/java/")) {
                candidates.add(path);
            }
        }

        Map<String, String> snippets = new LinkedHashMap<>();
        for (String path : candidates) {
            if (snippets.size() >= 6) break;
            Path file = ctx.cvePath.resolve(path);
            if (!Files.exists(file) || Files.isDirectory(file)) {
                continue;
            }
            try {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                snippets.put(path, truncate(content, REVIEW_FILE_SNIPPET));
            } catch (IOException ignored) {
                // Ignore unreadable evidence files and continue with the rest.
            }
        }
        return snippets;
    }

    String buildReviewerFeedback(VerificationReview review, int revision) {
        StringBuilder sb = new StringBuilder();
        sb.append("finish() rejected by reviewer.\n");
        sb.append("Verdict: ").append(review.verdict).append("\n");
        if (!review.reason.isEmpty()) {
            sb.append("Reason: ").append(review.reason).append("\n");
        }
        appendBulletSection(sb, "Matched evidence", review.matchedSignals);
        appendBulletSection(sb, "Missing evidence", review.missingEvidence);
        appendBulletSection(sb, "False-positive risks", review.falsePositiveRisks);
        appendBulletSection(sb, "Required next actions", review.nextActions);
        sb.append("Revision budget used: ").append(revision).append("/").append(MAX_REVIEW_REVISIONS).append(".\n");
        sb.append("Modify vuln-demo and/or poc files, rerun build/start/command, then call finish() again.");
        return sb.toString();
    }

    String buildFinishAcceptedMessage(VerificationReview review) {
        if (review == null) {
            return "finish() accepted";
        }
        return "finish() accepted. Reviewer verdict: " + review.verdict
                + (review.reason.isEmpty() ? "" : ". " + review.reason);
    }

    private void appendBulletSection(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append(title).append(":\n");
        for (String item : items) {
            sb.append("- ").append(item).append("\n");
        }
    }

    JsonNode mergeSummaryWithReview(JsonNode summary, VerificationReview review) {
        ObjectNode merged = summary != null && summary.isObject()
                ? ((ObjectNode) summary.deepCopy())
                : mapper.createObjectNode();
        if (review != null && !review.pocStatus.isEmpty()) {
            merged.put("poc_status", review.pocStatus);
        }

        String reviewerReason = review != null ? review.reason : "";
        String existingNotes = merged.path("notes").asText("").trim();
        if (!existingNotes.isEmpty() && !reviewerReason.isEmpty() && !existingNotes.contains(reviewerReason)) {
            merged.put("notes", existingNotes + " | Reviewer: " + reviewerReason);
        } else if (existingNotes.isEmpty() && !reviewerReason.isEmpty()) {
            merged.put("notes", reviewerReason);
        }

        if (review != null && !review.missingEvidence.isEmpty()) {
            merged.put("remaining_gap", joinItems(review.missingEvidence, "; "));
        }
        return merged;
    }

    private String joinItems(List<String> items, String delimiter) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (item == null || item.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(delimiter);
            }
            sb.append(item.trim());
        }
        return sb.toString();
    }
}

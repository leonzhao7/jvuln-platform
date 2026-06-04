package com.jvuln.pipeline.stage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class ReasoningStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(ReasoningStage.class);
    private static final int MAX_RETRIES = 2;
    private final PromptRegistry promptRegistry;
    private final ObjectMapper mapper;

    public ReasoningStage(PromptRegistry promptRegistry, ObjectMapper mapper) {
        this.promptRegistry = promptRegistry;
        this.mapper = mapper;
    }

    @Override
    public int number() { return 4; }

    @Override
    public String name() { return "Vulnerability Reasoning"; }

    // Diff size caps for each attempt: shrink on retries to reduce load
    private static final int[] DIFF_CAPS = {6000, 3000, 1000};

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        ctx.reportProgress("Starting AI vulnerability reasoning");

        String systemPrompt = promptRegistry.getSystemPrompt("reasoning");
        String userTemplate = promptRegistry.getUserPrompt("reasoning");

        String intelligence = trimIntelligence(ctx.getCompletedStages().get(1).getData());
        Object rawDiffData  = ctx.getCompletedStages().get(2).getData();
        StageResult stage3  = ctx.getCompletedStages().get(3);
        String codeAnalysis = trimCodeAnalysis(stage3 != null ? stage3.getData() : null);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                // Brief backoff before retry
                try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }

            int diffCap = DIFF_CAPS[Math.min(attempt, DIFF_CAPS.length - 1)];
            String patchDiff = extractDiff(ctx, rawDiffData, diffCap);

            log.info("Reasoning attempt {}: intel={}c diff={}c (cap={}) code={}c",
                    attempt + 1, intelligence.length(), patchDiff.length(), diffCap, codeAnalysis.length());

            Map<String, String> vars = new HashMap<>();
            vars.put("intelligence", intelligence);
            vars.put("patch_diff", patchDiff);
            vars.put("code_analysis", codeAnalysis);
            String userPrompt = promptRegistry.render(userTemplate, vars);
            LlmRequest request = LlmRequest.reasoning(systemPrompt, userPrompt);

            try {
                ctx.reportProgress("AI reasoning attempt " + (attempt + 1));
                LlmResponse response = ctx.getLlmClient().chat(request);
                String content = response.getContent();
                if (content == null || content.trim().isEmpty()) {
                    throw new RuntimeException("LLM returned empty content");
                }
                String json = stripMarkdownFence(content);
                if (json.isEmpty()) {
                    throw new RuntimeException("LLM response is empty after stripping markdown fence");
                }
                Object parsed = mapper.readValue(json, Object.class);
                ctx.getWorkspaceManager().writeStageData(ctx.getCveId(), 4, parsed);
                ctx.reportProgress("AI reasoning completed (tokens: "
                        + response.getPromptTokens() + "/" + response.getCompletionTokens() + ")");
                return StageResult.success(4, name(), parsed);
            } catch (Exception e) {
                log.warn("Reasoning attempt {} failed: {}", attempt + 1, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    return StageResult.failure(4, name(),
                            "AI reasoning failed after " + (MAX_RETRIES + 1) + " attempts: " + e.getMessage());
                }
            }
        }
        return StageResult.failure(4, name(), "Unexpected reasoning failure");
    }

    /** Keep only the fields the reasoning prompt cares about. */
    private String trimIntelligence(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        ObjectNode out = mapper.createObjectNode();
        copyField(root, out, "cveId");
        copyField(root, out, "cweId");
        copyField(root, out, "description");
        copyField(root, out, "cvss");
        copyField(root, out, "fixedVersion");
        copyField(root, out, "artifact");
        copyField(root, out, "fixCommits");
        copyField(root, out, "affectedVersions");
        return mapper.writeValueAsString(out);
    }

    /** Extract just the raw diff text from the patch stage result. */
    private String extractDiff(PipelineContext ctx, Object data, int cap) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode rawDiff = root.path("rawDiff");
        if (!rawDiff.isMissingNode() && rawDiff.isTextual()) {
            String d = rawDiff.asText();
            return d.length() > cap ? d.substring(0, cap) + "\n...[truncated]" : d;
        }
        Path diffFile = ctx.getWorkspacePath().resolve("patches/fix.diff");
        if (Files.exists(diffFile)) {
            String d = new String(Files.readAllBytes(diffFile), StandardCharsets.UTF_8);
            return d.length() > cap ? d.substring(0, cap) + "\n...[truncated]" : d;
        }
        // fallback: full JSON but capped
        String full = mapper.writeValueAsString(data);
        return full.length() > cap ? full.substring(0, cap) + "\n...[truncated]" : full;
    }

    /** Keep method analysis + CWE matches, drop verbose call-chain details. */
    private String trimCodeAnalysis(Object data) throws Exception {
        if (data == null) return "{}";
        JsonNode root = mapper.valueToTree(data);
        // analyzedFiles[].methods + analyzedFiles[].cweMatches only
        return mapper.writeValueAsString(root.path("analyzedFiles"));
    }

    private void copyField(JsonNode src, ObjectNode dst, String field) {
        JsonNode v = src.path(field);
        if (!v.isMissingNode()) dst.set(field, v);
    }

    /** Strip ```json ... ``` or ``` ... ``` markdown fences if present. */
    private String stripMarkdownFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline < 0) return s;
            s = s.substring(firstNewline + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            return s.trim();
        }
        return s;
    }
}

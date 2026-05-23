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

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        ctx.reportProgress("Starting AI vulnerability reasoning");

        String systemPrompt = promptRegistry.getSystemPrompt("reasoning");
        String userTemplate = promptRegistry.getUserPrompt("reasoning");

        // Trim each input to only what the reasoning prompt actually needs
        String intelligence = trimIntelligence(ctx.getCompletedStages().get(1).getData());
        String patchDiff    = extractDiff(ctx.getCompletedStages().get(2).getData());
        StageResult stage3  = ctx.getCompletedStages().get(3);
        String codeAnalysis = trimCodeAnalysis(stage3 != null ? stage3.getData() : null);

        log.info("Reasoning input sizes: intel={}c diff={}c code={}c",
                intelligence.length(), patchDiff.length(), codeAnalysis.length());

        Map<String, String> vars = new HashMap<>();
        vars.put("intelligence", intelligence);
        vars.put("patch_diff", patchDiff);
        vars.put("code_analysis", codeAnalysis);
        String userPrompt = promptRegistry.render(userTemplate, vars);

        LlmRequest request = LlmRequest.reasoning(systemPrompt, userPrompt);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                ctx.reportProgress("AI reasoning attempt " + (attempt + 1));
                LlmResponse response = ctx.getLlmClient().chat(request);
                String json = stripMarkdownFence(response.getContent());
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
        return mapper.writeValueAsString(out);
    }

    /** Extract just the raw diff text from the patch stage result. */
    private String extractDiff(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        // patch stage stores {rawDiff, diffs, commitInfo, ...}
        JsonNode rawDiff = root.path("rawDiff");
        if (!rawDiff.isMissingNode() && rawDiff.isTextual()) {
            return rawDiff.asText();
        }
        // fallback: full JSON but capped
        String full = mapper.writeValueAsString(data);
        return full.length() > 6000 ? full.substring(0, 6000) + "\n...[truncated]" : full;
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

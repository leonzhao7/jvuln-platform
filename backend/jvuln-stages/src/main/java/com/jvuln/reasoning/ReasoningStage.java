package com.jvuln.reasoning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
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
    public int number() { return 3; }

    @Override
    public String name() { return "Vulnerability Reasoning"; }

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        ctx.reportProgress("Starting AI vulnerability reasoning");

        String taskPrompt = promptRegistry.getPrompt("current/reasoning-system");
        String userTemplate = promptRegistry.getPrompt("current/reasoning-user");

        String intelligence = trimIntelligence(ctx.getCompletedStages().get(1).getData());
        StageResult stage2  = ctx.getCompletedStages().get(2);
        String codeAnalysis = trimCodeAnalysis(stage2 != null ? stage2.getData() : null);
        String vulnerabilityFacts = extractVulnerabilityFacts(stage2 != null ? stage2.getData() : null);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }

            log.info("Reasoning attempt {}: intel={}c facts={}c code={}c",
                    attempt + 1, intelligence.length(), vulnerabilityFacts.length(),
                    codeAnalysis.length());

            Map<String, String> vars = new HashMap<>();
            vars.put("intelligence", intelligence);
            vars.put("vulnerability_facts", vulnerabilityFacts);
            vars.put("code_analysis", codeAnalysis);
            String userPrompt = promptRegistry.render(userTemplate, vars);
            LlmRequest request = LlmRequest.reasoning(LlmPromptStage.REASONING, taskPrompt, userPrompt);

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
                ctx.getWorkspaceManager().writeStageData(ctx.getCveId(), 3, parsed);
                ctx.reportProgress("AI reasoning completed (tokens: "
                        + response.getPromptTokens() + "/" + response.getCompletionTokens() + ")");
                return StageResult.success(3, name(), parsed);
            } catch (Exception e) {
                log.warn("Reasoning attempt {} failed: {}", attempt + 1, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    return StageResult.failure(3, name(),
                            "AI reasoning failed after " + (MAX_RETRIES + 1) + " attempts: " + e.getMessage());
                }
            }
        }
        return StageResult.failure(3, name(), "Unexpected reasoning failure");
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


    /** Keep Stage 3's reconciled facts as the authoritative vulnerability identity. */
    private String extractVulnerabilityFacts(Object data) throws Exception {
        if (data == null) return "{}";
        JsonNode root = mapper.valueToTree(data);
        JsonNode facts = root.path("vulnerabilityFacts");
        return facts.isMissingNode() ? "{}" : mapper.writeValueAsString(facts);
    }

    /** Keep reconciled facts + method analysis + CWE matches, drop verbose call-chain details. */
    private String trimCodeAnalysis(Object data) throws Exception {
        if (data == null) return "{}";
        JsonNode root = mapper.valueToTree(data);
        ObjectNode out = mapper.createObjectNode();
        JsonNode facts = root.path("vulnerabilityFacts");
        if (!facts.isMissingNode()) {
            out.set("vulnerabilityFacts", facts);
        }
        out.set("analyzedFiles", root.path("analyzedFiles"));
        return mapper.writeValueAsString(out);
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

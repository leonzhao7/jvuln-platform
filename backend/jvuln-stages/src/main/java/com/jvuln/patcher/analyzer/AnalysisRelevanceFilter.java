package com.jvuln.patcher.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.store.model.CodeAnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class AnalysisRelevanceFilter {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRelevanceFilter.class);

    private static final String SYSTEM_PROMPT =
            "You review Java patch-analysis results and remove modifications unrelated to the CVE.\n" +
            "Return ONLY strict JSON in this shape:\n" +
            "{\n" +
            "  \"files\": [\n" +
            "    {\n" +
            "      \"fileName\": \"path/File.java\",\n" +
            "      \"relevant\": true,\n" +
            "      \"layer\": \"root_cause | enforcement_guard | policy_config | propagation_or_api_wiring | optional_support | noise\",\n" +
            "      \"reason\": \"one sentence\",\n" +
            "      \"keepMethods\": [\"methodA\", \"methodB\"]\n" +
            "    }\n" +
            "  ]\n" +
            "}\n" +
            "Rules:\n" +
            "- patchScope is the full Stage 2 patch-file set. files is the Stage 3 per-file diff/AST summary.\n" +
            "- layer meanings:\n" +
            "  * root_cause: vulnerable sink / parser / constructor / evaluator / direct exploit path.\n" +
            "  * enforcement_guard: validation, deny/allow-list, guard, inspector, sanitizer, security check.\n" +
            "  * policy_config: option, config, policy carrier, settings object.\n" +
            "  * propagation_or_api_wiring: boundary objects, tag/node metadata, API wiring, propagation glue.\n" +
            "  * optional_support: alternate mode, trusted-mode helper, variant constructor, convenience support.\n" +
            "  * noise: unrelated refactor or release-noise.\n" +
            "- Keep files that are on the exploit path, implement the security guard, or define the security boundary.\n" +
            "- Exclude refactors, deprecations, compatibility cleanups, logging-only edits, null checks, and release-noise changes.\n" +
            "- Added/deleted security-support files can be relevant even if they have no vulnerable method body.\n" +
            "- If a relevant file has no specific method focus, return an empty keepMethods array.\n" +
            "- If unsure, prefer relevant=true only when the diff summary clearly aligns with the CVE mechanism.\n" +
            "- Prefer the code-change mechanism over advisory wording when they conflict.";

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final AnalysisLayerClassifier analysisLayerClassifier;

    public AnalysisRelevanceFilter(LlmClient llmClient, ObjectMapper mapper,
                                   AnalysisLayerClassifier analysisLayerClassifier) {
        this.llmClient = llmClient;
        this.mapper = mapper;
        this.analysisLayerClassifier = analysisLayerClassifier;
    }

    public List<CodeAnalysisResult> filter(String cveId, String cveDescription, String affectedComponent,
                                           Object stage2Data, List<CodeAnalysisResult> results) {
        if (results == null || results.size() <= 1) {
            return results;
        }
        try {
            String userPrompt = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    buildPrompt(cveId, cveDescription, affectedComponent, stage2Data, results));
            LlmResponse response = llmClient.chat(LlmRequest.reasoning(SYSTEM_PROMPT, userPrompt));
            if (response == null || response.getContent() == null || response.getContent().trim().isEmpty()) {
                return results;
            }
            return applyDecisions(results, extractDecisions(response.getContent()));
        } catch (Exception e) {
            log.warn("Analysis relevance filter failed, keeping traditional results: {}", e.getMessage());
            return results;
        }
    }

    private ObjectNode buildPrompt(String cveId, String cveDescription, String affectedComponent,
                                   Object stage2Data, List<CodeAnalysisResult> results) {
        JsonNode stage2 = mapper.valueToTree(stage2Data);
        ObjectNode root = mapper.createObjectNode();
        root.put("cveId", cveId);
        if (cveDescription != null && !cveDescription.trim().isEmpty()) {
            root.put("description", cveDescription);
        }
        if (affectedComponent != null && !affectedComponent.trim().isEmpty()) {
            root.put("affectedComponent", affectedComponent);
        }
        root.put("patchStrategy", stage2.path("strategy").asText(""));
        root.set("patchScope", extractPatchScope(stage2));

        ArrayNode files = mapper.createArrayNode();
        for (CodeAnalysisResult result : results) {
            ObjectNode file = mapper.createObjectNode();
            file.put("fileName", result.getFileName());
            file.put("changeType", defaultText(result.getChangeType(), "modified"));
            ArrayNode methodNames = mapper.createArrayNode();
            if (result.getMethods() != null) {
                for (CodeAnalysisResult.MethodAnalysis method : result.getMethods()) {
                    methodNames.add(method.getMethodName());
                }
            }
            file.set("methodNames", methodNames);
            ArrayNode cweMatches = mapper.createArrayNode();
            if (result.getCweMatches() != null) {
                for (CodeAnalysisResult.CweMatch match : result.getCweMatches()) {
                    ObjectNode cwe = mapper.createObjectNode();
                    cwe.put("cweId", match.getCweId());
                    cwe.put("pattern", abbreviate(match.getMatchedPattern(), 80));
                    cwe.put("matchedCode", abbreviate(match.getMatchedCode(), 180));
                    cweMatches.add(cwe);
                }
            }
            file.set("cweMatches", cweMatches);
            file.set("callChain", mapper.valueToTree(result.getCallChain()));
            file.put("vulnerableSample", abbreviate(joinVulnerableCode(result.getMethods()), 300));
            file.put("fixedSample", abbreviate(joinFixedCode(result.getMethods()), 300));
            files.add(file);
        }
        root.set("files", files);
        return root;
    }

    private ArrayNode extractPatchScope(JsonNode stage2) {
        ArrayNode scope = mapper.createArrayNode();
        JsonNode files = stage2.path("files");
        if (!files.isArray() || files.size() == 0) {
            files = stage2.path("diffs");
        }
        if (!files.isArray()) {
            return scope;
        }
        for (JsonNode node : files) {
            String fileName = node.path("filePath").asText("").trim();
            if (fileName.isEmpty()) {
                continue;
            }
            ObjectNode file = mapper.createObjectNode();
            file.put("fileName", fileName);
            file.put("changeType", node.path("changeType").asText("modified"));
            scope.add(file);
        }
        return scope;
    }

    private List<CodeAnalysisResult> applyDecisions(List<CodeAnalysisResult> results, Map<String, Decision> decisions) {
        if (decisions.isEmpty()) {
            return results;
        }

        List<CodeAnalysisResult> filtered = new ArrayList<>();
        for (CodeAnalysisResult result : results) {
            Decision decision = decisions.get(result.getFileName());
            if (decision == null) {
                decision = decisions.get(shortName(result.getFileName()));
            }
            if (decision == null) {
                filtered.add(result);
                continue;
            }
            if (!decision.relevant) {
                continue;
            }
            List<CodeAnalysisResult.MethodAnalysis> methods = result.getMethods();
            if (decision.keepMethods != null) {
                methods = filterMethods(methods, decision.keepMethods);
            }
            filtered.add(new CodeAnalysisResult(
                    result.getFileName(),
                    result.getChangeType(),
                    decision.reason,
                    analysisLayerClassifier.mergeLayer(decision.layer, result, decision.reason),
                    methods,
                    result.getCweMatches(),
                    filterCallChain(result.getCallChain(), decision.keepMethods)));
        }

        if (filtered.isEmpty()) {
            log.warn("Analysis relevance filter returned zero files; keeping traditional results");
            return results;
        }
        return filtered;
    }

    private Map<String, Decision> extractDecisions(String raw) throws Exception {
        String json = raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("(?s)^```[a-zA-Z0-9_-]*\\n?", "")
                    .replaceAll("```\\s*$", "").trim();
        }
        int startObj = json.indexOf('{');
        int endObj = json.lastIndexOf('}');
        if (startObj >= 0 && endObj >= startObj) {
            json = json.substring(startObj, endObj + 1);
        }
        JsonNode root = mapper.readTree(json);
        JsonNode files = root.path("files");
        Map<String, Decision> out = new LinkedHashMap<>();
        if (!files.isArray()) {
            return out;
        }
        for (JsonNode node : files) {
            String fileName = node.path("fileName").asText("").trim();
            if (fileName.isEmpty()) {
                continue;
            }
            Decision decision = new Decision();
            decision.relevant = node.path("relevant").asBoolean(false);
            decision.layer = node.path("layer").asText("").trim();
            decision.reason = node.path("reason").asText("").trim();
            if (node.has("keepMethods") && node.path("keepMethods").isArray()) {
                decision.keepMethods = new LinkedHashSet<>();
                for (JsonNode method : node.path("keepMethods")) {
                    String name = method.asText("").trim();
                    if (!name.isEmpty()) {
                        decision.keepMethods.add(name);
                    }
                }
            }
            out.put(fileName, decision);
        }
        return out;
    }

    private List<CodeAnalysisResult.MethodAnalysis> filterMethods(List<CodeAnalysisResult.MethodAnalysis> methods,
                                                                  Set<String> keepMethods) {
        if (methods == null) {
            return null;
        }
        if (keepMethods == null) {
            return methods;
        }
        List<CodeAnalysisResult.MethodAnalysis> out = new ArrayList<>();
        for (CodeAnalysisResult.MethodAnalysis method : methods) {
            if (keepMethods.contains(method.getMethodName())) {
                out.add(method);
            }
        }
        return out;
    }

    private List<String> filterCallChain(List<String> callChain, Set<String> keepMethods) {
        if (callChain == null || keepMethods == null) {
            return callChain;
        }
        List<String> out = new ArrayList<>();
        for (String item : callChain) {
            if (item == null || item.trim().isEmpty()) {
                continue;
            }
            String lower = item.toLowerCase(Locale.ROOT);
            for (String method : keepMethods) {
                if (lower.contains(method.toLowerCase(Locale.ROOT))) {
                    out.add(item);
                    break;
                }
            }
        }
        return out;
    }

    private String joinVulnerableCode(List<CodeAnalysisResult.MethodAnalysis> methods) {
        if (methods == null || methods.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CodeAnalysisResult.MethodAnalysis method : methods) {
            if (method.getVulnerableCode() == null || method.getVulnerableCode().trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(method.getMethodName()).append(": ").append(method.getVulnerableCode().trim());
        }
        return sb.toString();
    }

    private String joinFixedCode(List<CodeAnalysisResult.MethodAnalysis> methods) {
        if (methods == null || methods.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CodeAnalysisResult.MethodAnalysis method : methods) {
            if (method.getFixedCode() == null || method.getFixedCode().trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(method.getMethodName()).append(": ").append(method.getFixedCode().trim());
        }
        return sb.toString();
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().replace("\r", "");
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "...";
    }

    private String defaultText(String text, String fallback) {
        return text == null || text.trim().isEmpty() ? fallback : text;
    }

    private String shortName(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('/');
        return idx >= 0 ? fileName.substring(idx + 1) : fileName;
    }

    private static class Decision {
        boolean relevant;
        String layer;
        String reason;
        Set<String> keepMethods;
    }
}

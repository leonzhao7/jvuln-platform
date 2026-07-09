package com.jvuln.patcher.analyzer;

import com.jvuln.store.model.CodeAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AnalysisLayerClassifier {

    public static final String ROOT_CAUSE = "root_cause";
    public static final String ENFORCEMENT_GUARD = "enforcement_guard";
    public static final String POLICY_CONFIG = "policy_config";
    public static final String PROPAGATION_OR_API_WIRING = "propagation_or_api_wiring";
    public static final String OPTIONAL_SUPPORT = "optional_support";
    public static final String UNCATEGORIZED = "uncategorized";

    private static final List<String> ORDER = Collections.unmodifiableList(Arrays.asList(
            ROOT_CAUSE,
            ENFORCEMENT_GUARD,
            POLICY_CONFIG,
            PROPAGATION_OR_API_WIRING,
            OPTIONAL_SUPPORT,
            UNCATEGORIZED
    ));

    public List<CodeAnalysisResult> classify(List<CodeAnalysisResult> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }
        List<CodeAnalysisResult> classified = new ArrayList<>();
        for (CodeAnalysisResult result : results) {
            String layer = resolveLayer(result);
            classified.add(new CodeAnalysisResult(
                    result.getFileName(),
                    result.getChangeType(),
                    result.getRelevanceReason(),
                    layer,
                    result.getMethods(),
                    result.getCweMatches(),
                    result.getCallChain()));
        }
        classified.sort(new Comparator<CodeAnalysisResult>() {
            @Override
            public int compare(CodeAnalysisResult left, CodeAnalysisResult right) {
                int byLayer = Integer.compare(priority(left.getRelevanceLayer()), priority(right.getRelevanceLayer()));
                if (byLayer != 0) {
                    return byLayer;
                }
                String leftName = left.getFileName() == null ? "" : left.getFileName();
                String rightName = right.getFileName() == null ? "" : right.getFileName();
                return leftName.compareTo(rightName);
            }
        });
        return classified;
    }

    public List<Map<String, Object>> summarize(List<CodeAnalysisResult> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String layer : ORDER) {
            counts.put(layer, 0);
        }
        if (results != null) {
            for (CodeAnalysisResult result : results) {
                String layer = normalizeLayer(result == null ? null : result.getRelevanceLayer());
                counts.put(layer, counts.containsKey(layer) ? counts.get(layer) + 1 : 1);
            }
        }

        List<Map<String, Object>> summary = new ArrayList<>();
        for (String layer : ORDER) {
            int count = counts.get(layer);
            if (count <= 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("layer", layer);
            item.put("count", count);
            summary.add(item);
        }
        return summary;
    }

    public String mergeLayer(String candidateLayer, CodeAnalysisResult result, String overrideReason) {
        String normalized = normalizeLayer(candidateLayer);
        if (!UNCATEGORIZED.equals(normalized)) {
            return normalized;
        }
        if (result == null) {
            return UNCATEGORIZED;
        }
        CodeAnalysisResult probe = new CodeAnalysisResult(
                result.getFileName(),
                result.getChangeType(),
                overrideReason != null && !overrideReason.trim().isEmpty() ? overrideReason : result.getRelevanceReason(),
                result.getRelevanceLayer(),
                result.getMethods(),
                result.getCweMatches(),
                result.getCallChain());
        return resolveLayer(probe);
    }

    private String resolveLayer(CodeAnalysisResult result) {
        String existing = normalizeLayer(result == null ? null : result.getRelevanceLayer());
        if (!UNCATEGORIZED.equals(existing)) {
            return existing;
        }
        if (result == null) {
            return UNCATEGORIZED;
        }

        String fileName = lower(result.getFileName());
        String reason = lower(result.getRelevanceReason());
        String methods = lower(joinMethodNames(result));
        String cwe = lower(joinCweIds(result));
        String combined = fileName + "\n" + reason + "\n" + methods + "\n" + cwe;

        if (containsAny(fileName,
                "trustedprefixestaginspector", "trustedtaginspector", "customclassloaderconstructor",
                "/internal/logger", "compat", "legacy", "adapter", "bridge")) {
            return OPTIONAL_SUPPORT;
        }

        if (containsAny(fileName, "loaderoptions", "config", "settings", "policy", "options")
                || containsAny(reason, "policy configuration", "policy config", "configuration", "option", "setting")) {
            return POLICY_CONFIG;
        }

        if (containsAny(combined,
                "inspector", "validator", "guard", "deny", "allowlist", "whitelist",
                "isglobaltagallowed", "sanitize", "escape", "permission", "trusted-tag policy",
                "safe-construction boundary", "blocks unsafe", "restrict", "enforces")) {
            return ENFORCEMENT_GUARD;
        }

        if ((result.getCweMatches() != null && !result.getCweMatches().isEmpty())
                || containsAny(combined,
                "directly implicated", "unsafe java object constructor", "object construction path",
                "arbitrary type instantiation", "eval(", "readobject", "constructobject",
                "constructmapping", "sink", "query execution", "runtime.exec", "path traversal")) {
            return ROOT_CAUSE;
        }

        if (containsAny(combined,
                "wires", "wire", "propagat", "carries", "api boundary", "tag semantics",
                "parse-to-node", "/nodes/", "/yaml.java", "/tag.java", "/node.java", "/composer/")) {
            return PROPAGATION_OR_API_WIRING;
        }

        return UNCATEGORIZED;
    }

    private String normalizeLayer(String layer) {
        String lower = lower(layer);
        for (String known : ORDER) {
            if (known.equals(lower)) {
                return known;
            }
        }
        return UNCATEGORIZED;
    }

    private int priority(String layer) {
        int idx = ORDER.indexOf(normalizeLayer(layer));
        return idx >= 0 ? idx : ORDER.size();
    }

    private boolean containsAny(String source, String... tokens) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        for (String token : tokens) {
            if (source.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String joinMethodNames(CodeAnalysisResult result) {
        if (result == null || result.getMethods() == null || result.getMethods().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CodeAnalysisResult.MethodAnalysis method : result.getMethods()) {
            if (method == null || method.getMethodName() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(method.getMethodName());
        }
        return sb.toString();
    }

    private String joinCweIds(CodeAnalysisResult result) {
        if (result == null || result.getCweMatches() == null || result.getCweMatches().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CodeAnalysisResult.CweMatch match : result.getCweMatches()) {
            if (match == null || match.getCweId() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(match.getCweId());
        }
        return sb.toString();
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

package com.jvuln.patcher.analyzer;

import com.jvuln.store.model.CodeAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class PatchEvidenceSynthesizer {

    private static final int MAX_SIGNALS = 40;

    private static final List<TokenRule> TOKEN_RULES = Arrays.asList(
            rule("expression_injection", 5,
                    "expression engine or class allow-list change",
                    "aviator", "allowed_class_set", "allowedclassset", "allowclass",
                    "allowlist", "whitelist", "eval(", "spel", "ognl", "jexl",
                    "mvel", "rhino", "nashorn", "scriptengine", "elprocessor"),
            rule("deserialization", 5,
                    "deserialization or unsafe type construction change",
                    "objectinputstream", "readobject(", "xmldecoder", "deserializ",
                    "taginspector", "untrustedtaginspector", "trustedtaginspector",
                    "isglobaltagallowed", "constructobject", "constructmapping",
                    "yamlconstructor", "loaderoptions", "typedescription",
                    "class.forname", "loadclass(", "newinstance("),
            rule("sql_injection", 4,
                    "database query construction or execution change",
                    "preparedstatement", "createnativequery", "createquery",
                    "sqlsession", "jdbctemplate", "select ", "update ", "delete ",
                    "insert ", " where(", " where "),
            rule("path_traversal", 4,
                    "path normalization or file access guard change",
                    "../", "..\\", "normalize(", "getcanonicalpath", "pathtofile",
                    "paths.get(", "resolve(", "path traversal"),
            rule("command_injection", 4,
                    "process execution or shell command construction change",
                    "runtime.exec", "processbuilder", "/bin/sh", "cmd.exe",
                    "commandline"),
            rule("template_injection", 3,
                    "template evaluation or rendering change",
                    "freemarker", "velocity", "thymeleaf", "template", "render("),
            rule("xss", 3,
                    "HTML escaping or sanitization change",
                    "xss", "escapehtml", "sanitize", "htmlpolicybuilder", "owasp"),
            rule("ssrf_or_redirect", 3,
                    "URL fetch, redirect, or target validation change",
                    "openconnection", "httpclient", "resttemplate", "redirect",
                    "location", "url "),
            rule("resource_exhaustion", 3,
                    "resource exhaustion guard change",
                    "maxaliasesforcollections", "codelimit", "maxdepth",
                    "maxsizediff", "maxsize", "zip bomb", "zipbomb")
    );

    public Map<String, Object> build(List<JavaFileChange> fileChanges, List<CodeAnalysisResult> analysisResults) {
        List<JavaFileChange> focusChanges = focusChanges(fileChanges, analysisResults);
        Set<String> changedFiles = new LinkedHashSet<>();
        Set<String> modules = new LinkedHashSet<>();
        List<Map<String, Object>> signals = new ArrayList<>();
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, List<String>> reasons = new LinkedHashMap<>();

        for (JavaFileChange change : focusChanges) {
            if (change == null) {
                continue;
            }
            changedFiles.add(change.filePath);
            addModule(modules, change.filePath);
            scanContent(change.filePath, change.combinedText(), scores, reasons, signals);
        }

        if (analysisResults != null) {
            for (CodeAnalysisResult result : analysisResults) {
                if (result == null) {
                    continue;
                }
                applyResultSignals(result, scores, reasons, signals);
            }
        }

        List<Map<String, Object>> votes = buildVotes(scores, reasons);
        String primary = votes.isEmpty() ? "unknown" : stringValue(votes.get(0).get("category"), "unknown");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", buildSummary(primary, votes, changedFiles, modules));
        out.put("primaryCategory", primary);
        out.put("changedFileCount", changedFiles.size());
        out.put("javaFileCount", focusChanges.size());
        out.put("changedFiles", new ArrayList<>(changedFiles));
        out.put("modules", new ArrayList<>(modules));
        out.put("categoryVotes", votes);
        out.put("signals", limitSignals(signals));
        return out;
    }

    private List<JavaFileChange> focusChanges(List<JavaFileChange> fileChanges, List<CodeAnalysisResult> analysisResults) {
        List<JavaFileChange> safeChanges = fileChanges != null ? fileChanges : Collections.<JavaFileChange>emptyList();
        if (analysisResults == null || analysisResults.isEmpty()) {
            return safeChanges;
        }
        Set<String> names = new LinkedHashSet<>();
        for (CodeAnalysisResult result : analysisResults) {
            if (result != null && result.getFileName() != null && !result.getFileName().trim().isEmpty()) {
                names.add(result.getFileName().trim());
            }
        }
        if (names.isEmpty()) {
            return safeChanges;
        }

        List<JavaFileChange> focus = new ArrayList<>();
        for (JavaFileChange change : safeChanges) {
            if (change == null || change.filePath == null) {
                continue;
            }
            if (names.contains(change.filePath)) {
                focus.add(change);
                continue;
            }
            String shortName = shortName(change.filePath);
            if (names.contains(shortName)) {
                focus.add(change);
            }
        }
        return focus.isEmpty() ? safeChanges : focus;
    }

    private void scanContent(String filePath, String content, Map<String, Integer> scores,
                             Map<String, List<String>> reasons, List<Map<String, Object>> signals) {
        String lowerPath = filePath == null ? "" : filePath.toLowerCase(Locale.ROOT);
        String lowerContent = content == null ? "" : content.toLowerCase(Locale.ROOT);
        String combined = lowerPath + "\n" + lowerContent;

        for (TokenRule rule : TOKEN_RULES) {
            for (String token : rule.tokens) {
                if (combined.contains(token)) {
                    add(rule.category, filePath, token, rule.weight, rule.reason, scores, reasons, signals);
                    break;
                }
            }
        }
    }

    private void applyResultSignals(CodeAnalysisResult result, Map<String, Integer> scores,
                                    Map<String, List<String>> reasons, List<Map<String, Object>> signals) {
        String fileName = result.getFileName();
        if (result.getCweMatches() != null) {
            for (CodeAnalysisResult.CweMatch match : result.getCweMatches()) {
                String category = mapCwe(match.getCweId());
                if (!"unknown".equals(category)) {
                    add(category, fileName, match.getCweId(), 5,
                            "CWE match in Stage 3 analysis", scores, reasons, signals);
                }
            }
        }
        if (result.getMethods() != null) {
            for (CodeAnalysisResult.MethodAnalysis method : result.getMethods()) {
                scanContent(fileName,
                        safe(method.getSignature()) + "\n" + safe(method.getVulnerableCode()) + "\n" + safe(method.getFixedCode()),
                        scores, reasons, signals);
            }
        }
    }

    private List<Map<String, Object>> buildVotes(Map<String, Integer> scores, Map<String, List<String>> reasons) {
        List<Map<String, Object>> votes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            Map<String, Object> vote = new LinkedHashMap<>();
            vote.put("category", entry.getKey());
            vote.put("score", entry.getValue());
            vote.put("reason", join(reasons.get(entry.getKey())));
            votes.add(vote);
        }
        votes.sort(new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                Integer a = (Integer) left.get("score");
                Integer b = (Integer) right.get("score");
                return Integer.compare(b == null ? 0 : b, a == null ? 0 : a);
            }
        });
        return votes;
    }

    private List<Map<String, Object>> limitSignals(List<Map<String, Object>> signals) {
        if (signals.size() <= MAX_SIGNALS) {
            return signals;
        }
        return new ArrayList<>(signals.subList(0, MAX_SIGNALS));
    }

    private String buildSummary(String primary, List<Map<String, Object>> votes,
                                Set<String> changedFiles, Set<String> modules) {
        if ("unknown".equals(primary)) {
            return "Stage 3 did not extract a strong vulnerability-category signal from the filtered patch scope.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Stage 3 evidence points to ").append(primary);
        if (!modules.isEmpty()) {
            sb.append(" in ").append(join(new ArrayList<>(modules)));
        }
        sb.append(" (").append(changedFiles.size()).append(" relevant Java file(s)");
        if (!votes.isEmpty()) {
            sb.append(", top score=").append(votes.get(0).get("score"));
        }
        sb.append(").");
        return sb.toString();
    }

    private void addModule(Set<String> modules, String file) {
        if (file == null || file.isEmpty()) {
            return;
        }
        String normalized = file.replace('\\', '/');
        if (normalized.contains("/extra/expression/")) {
            modules.add("extra/expression");
        } else if (normalized.contains("/db/") || normalized.contains("/sql/")) {
            modules.add("db/sql");
        } else if (normalized.contains("/yaml/")) {
            modules.add("yaml");
        } else if (normalized.contains("/json/")) {
            modules.add("json");
        } else if (normalized.contains("/core/")) {
            modules.add("core");
        } else {
            int slash = normalized.indexOf('/');
            modules.add(slash > 0 ? normalized.substring(0, slash) : normalized);
        }
    }

    private void add(String category, String filePath, String token, int weight, String reason,
                     Map<String, Integer> scores, Map<String, List<String>> reasons,
                     List<Map<String, Object>> signals) {
        scores.put(category, scores.containsKey(category) ? scores.get(category) + weight : weight);
        if (!reasons.containsKey(category)) {
            reasons.put(category, new ArrayList<String>());
        }
        if (!reasons.get(category).contains(reason)) {
            reasons.get(category).add(reason);
        }

        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("category", category);
        signal.put("filePath", filePath);
        signal.put("token", token);
        signal.put("weight", weight);
        signal.put("reason", reason);
        signals.add(signal);
    }

    private String mapCwe(String cweId) {
        String lower = safe(cweId).toLowerCase(Locale.ROOT);
        if (lower.contains("89")) return "sql_injection";
        if (lower.contains("94")) return "expression_injection";
        if (lower.contains("502")) return "deserialization";
        if (lower.contains("22")) return "path_traversal";
        if (lower.contains("78")) return "command_injection";
        if (lower.contains("79")) return "xss";
        if (lower.contains("918") || lower.contains("601")) return "ssrf_or_redirect";
        if (lower.contains("400")) return "resource_exhaustion";
        return "unknown";
    }

    private static TokenRule rule(String category, int weight, String reason, String... tokens) {
        return new TokenRule(category, weight, reason, Arrays.asList(tokens));
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(value.trim());
        }
        return sb.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String shortName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static class TokenRule {
        private final String category;
        private final int weight;
        private final String reason;
        private final List<String> tokens;

        private TokenRule(String category, int weight, String reason, List<String> tokens) {
            this.category = category;
            this.weight = weight;
            this.reason = reason;
            this.tokens = tokens;
        }
    }
}

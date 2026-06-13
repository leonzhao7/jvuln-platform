package com.jvuln.patcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PatchEvidenceExtractor {

    private static final int MAX_SIGNALS = 40;

    private PatchEvidenceExtractor() {}

    public static PatchInfo.PatchEvidence extract(List<PatchInfo.FileDiff> diffs, String rawDiff) {
        List<PatchInfo.FileDiff> safeDiffs = diffs != null ? diffs : Collections.<PatchInfo.FileDiff>emptyList();
        Set<String> changedFiles = new LinkedHashSet<>();
        Set<String> modules = new LinkedHashSet<>();
        List<PatchInfo.EvidenceSignal> signals = new ArrayList<>();
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, List<String>> reasons = new LinkedHashMap<>();

        for (PatchInfo.FileDiff diff : safeDiffs) {
            String file = diff.getFilePath();
            changedFiles.add(file);
            addModule(modules, file);

            String content = ((diff.getDiffContent() == null ? "" : diff.getDiffContent()) + "\n"
                    + join(diff.getAddedLines()) + "\n" + join(diff.getRemovedLines())).toLowerCase(Locale.ROOT);
            scanFile(file, content, scores, reasons, signals);
        }

        if (signals.isEmpty() && rawDiff != null && !rawDiff.trim().isEmpty()) {
            scanFile("(rawDiff)", rawDiff.toLowerCase(Locale.ROOT), scores, reasons, signals);
        }

        List<PatchInfo.CategoryVote> votes = buildVotes(scores, reasons);
        String primary = votes.isEmpty() ? "unknown" : votes.get(0).getCategory();
        String summary = buildSummary(primary, votes, changedFiles, modules);

        if (signals.size() > MAX_SIGNALS) {
            signals = new ArrayList<>(signals.subList(0, MAX_SIGNALS));
        }

        return new PatchInfo.PatchEvidence(summary, primary, changedFiles.size(), safeDiffs.size(),
                new ArrayList<>(changedFiles), new ArrayList<>(modules), votes, signals);
    }

    private static void scanFile(String file, String content, Map<String, Integer> scores,
                                 Map<String, List<String>> reasons,
                                 List<PatchInfo.EvidenceSignal> signals) {
        String path = file == null ? "" : file.toLowerCase(Locale.ROOT);

        if (path.contains("/extra/expression/") || path.contains("expressionengine")
                || content.contains("aviator") || content.contains("allowed_class_set")
                || content.contains("allowclass") || content.contains("allowclassset")
                || content.contains("eval(") || content.contains("spel") || content.contains("jexl")
                || content.contains("mvel") || content.contains("ognl") || content.contains("rhino")) {
            add("expression_injection", file, "expression/eval/class allow-list", 5,
                    "expression engine or class whitelist change", scores, reasons, signals);
        }
        if (path.contains("/db/") || path.contains("/sql/") || content.contains("sqlbuilder")
                || content.contains("preparedstatement") || content.contains("where(")
                || content.contains("query(") || content.contains("entity.") || content.contains("sql ")) {
            add("sql_injection", file, "sql/db/query", 4,
                    "database query construction change", scores, reasons, signals);
        }
        if (content.contains("objectinputstream") || content.contains("readobject(")
                || content.contains("xmldecoder") || content.contains("defaulttyping")
                || content.contains("deserializ")) {
            add("deserialization", file, "deserialization API", 4,
                    "deserialization attack surface change", scores, reasons, signals);
        }
        if (content.contains("../") || content.contains("path traversal")
                || content.contains("normalize(") || content.contains("canonical")
                || content.contains("defaultservlet") || content.contains("replace('/', '.')")) {
            add("path_traversal", file, "path normalization", 4,
                    "path handling or traversal-related change", scores, reasons, signals);
        }
        if (content.contains("runtime.exec") || content.contains("processbuilder")
                || content.contains("command injection")) {
            add("command_injection", file, "process execution", 4,
                    "process execution or command construction change", scores, reasons, signals);
        }
        if (content.contains("template") || content.contains("freemarker")
                || content.contains("velocity") || content.contains("thymeleaf")) {
            add("template_injection", file, "template engine", 3,
                    "template engine change", scores, reasons, signals);
        }
        if (content.contains("xss") || content.contains("escapehtml")
                || content.contains("sanitize") || content.contains("unescape")) {
            add("xss", file, "html escaping", 3,
                    "HTML escaping or sanitization change", scores, reasons, signals);
        }
        if (content.contains("ssrf") || content.contains("openredirect")
                || content.contains("urlutil") || content.contains("redirect")) {
            add("ssrf_or_redirect", file, "url/network handling", 2,
                    "URL or network target handling change", scores, reasons, signals);
        }
        if (content.contains("zip bomb") || content.contains("zipbomb")
                || content.contains("compressedsize") || content.contains("maxsizediff")) {
            add("resource_exhaustion", file, "zip/resource guard", 3,
                    "resource exhaustion guard change", scores, reasons, signals);
        }
    }

    private static void add(String category, String file, String token, int weight, String reason,
                            Map<String, Integer> scores, Map<String, List<String>> reasons,
                            List<PatchInfo.EvidenceSignal> signals) {
        scores.put(category, scores.containsKey(category) ? scores.get(category) + weight : weight);
        if (!reasons.containsKey(category)) {
            reasons.put(category, new ArrayList<String>());
        }
        if (!reasons.get(category).contains(reason)) {
            reasons.get(category).add(reason);
        }
        signals.add(new PatchInfo.EvidenceSignal(category, file, token, weight, reason));
    }

    private static List<PatchInfo.CategoryVote> buildVotes(Map<String, Integer> scores,
                                                           Map<String, List<String>> reasons) {
        List<PatchInfo.CategoryVote> votes = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            List<String> rs = reasons.get(entry.getKey());
            String reason = rs == null || rs.isEmpty() ? "" : join(rs);
            votes.add(new PatchInfo.CategoryVote(entry.getKey(), entry.getValue(), reason));
        }
        votes.sort(new Comparator<PatchInfo.CategoryVote>() {
            @Override
            public int compare(PatchInfo.CategoryVote a, PatchInfo.CategoryVote b) {
                return Integer.compare(b.getScore(), a.getScore());
            }
        });
        return votes;
    }

    private static String buildSummary(String primary, List<PatchInfo.CategoryVote> votes,
                                       Set<String> changedFiles, Set<String> modules) {
        if ("unknown".equals(primary)) {
            return "No strong vulnerability-category signal was extracted from patch files.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Patch evidence points to ").append(primary);
        if (!modules.isEmpty()) {
            sb.append(" in ").append(join(new ArrayList<>(modules)));
        }
        sb.append(" (").append(changedFiles.size()).append(" Java file(s)");
        if (!votes.isEmpty()) {
            sb.append(", top score=").append(votes.get(0).getScore());
        }
        sb.append(").");
        return sb.toString();
    }

    private static void addModule(Set<String> modules, String file) {
        if (file == null || file.isEmpty()) return;
        String normalized = file.replace('\\', '/');
        if (normalized.contains("/extra/expression/")) {
            modules.add("extra/expression");
        } else if (normalized.contains("/db/") || normalized.contains("/sql/")) {
            modules.add("db/sql");
        } else if (normalized.contains("/json/")) {
            modules.add("json");
        } else if (normalized.contains("/core/")) {
            modules.add("core");
        } else {
            int slash = normalized.indexOf('/');
            modules.add(slash > 0 ? normalized.substring(0, slash) : normalized);
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(value);
        }
        return sb.toString();
    }
}

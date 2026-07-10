package com.jvuln.patcher.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filters a noisy maven-source-diff file list down to CVE-relevant files.
 *
 * Two-phase approach:
 *   1. Traditional pre-filter: fast, synchronous. Excludes files that are
 *      clearly pure new-feature additions (0 removed lines, no security keywords).
 *   2. AI filter: classifies remaining candidates using the LLM when the
 *      pre-filter result still has more than THRESHOLD files.
 *
 * Both phases are skipped when the input already has ≤ THRESHOLD files (i.e.
 * commit-based diffs that are already precise).
 */
@Component
public class DiffRelevanceFilter {

    private static final Logger log = LoggerFactory.getLogger(DiffRelevanceFilter.class);

    /** Below this count, diff is already small enough — skip all filtering. */
    static final int FILE_THRESHOLD = 6;

    private static final Set<String> SECURITY_KEYWORDS = new HashSet<>(Arrays.asList(
            "inject", "injection", "escape", "unescape", "sanitize", "sanitise",
            "sql", "xss", "csrf", "ssrf", "rce", "deserializ", "classloader",
            "reflection", "permission", "authori", "authen", "bypass", "traversal",
            "upload", "template", "vuln", "security", "cve", "fix", "patch",
            "remediat", "exploit", "attack", "malicious", "arbitrary",
            // expression / script injection
            "expression", "sandbox", "whitelist", "allowclass", "classset",
            "scriptengine", "groovy", "ognl", "spel", "jexl", "mvel", "aviator",
            "xmldecoder", "rhino", "nashorn"
    ));


    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptRegistry promptRegistry;

    public DiffRelevanceFilter(LlmClient llmClient, PromptRegistry promptRegistry) {
        this.llmClient = llmClient;
        this.promptRegistry = promptRegistry;
    }

    /**
     * Main entry point. Returns a filtered (or original) list.
     *
     * @param changes       all Java file changes parsed from the diff
     * @param cveId         e.g. "CVE-2023-25330"
     * @param cveDescription short CVE description from Stage 1 (may be null)
     * @param affectedComponent e.g. "com.baomidou:mybatis-plus" (may be null)
     */
    public List<JavaFileChange> filter(List<JavaFileChange> changes,
                                        String cveId,
                                        String cveDescription,
                                        String affectedComponent) {
        if (changes.size() <= FILE_THRESHOLD) {
            log.debug("DiffFilter: {} files ≤ threshold {}, skipping", changes.size(), FILE_THRESHOLD);
            return changes;
        }

        log.info("DiffFilter: {} files — applying pre-filter (cve={})", changes.size(), cveId);
        List<JavaFileChange> preFiltered = preFilterOnly(changes, cveDescription);
        log.info("DiffFilter: pre-filter kept {}/{} files", preFiltered.size(), changes.size());

        if (preFiltered.size() <= FILE_THRESHOLD) {
            return preFiltered;
        }

        // Pre-filter still left too many — ask AI
        log.info("DiffFilter: {} files after pre-filter, asking AI", preFiltered.size());
        List<JavaFileChange> aiFiltered = aiFilter(preFiltered, cveId, cveDescription, affectedComponent);

        if (aiFiltered.isEmpty()) {
            log.warn("DiffFilter: AI returned 0 relevant files — falling back to pre-filter result");
            return preFiltered;
        }
        log.info("DiffFilter: AI kept {}/{} files", aiFiltered.size(), preFiltered.size());
        return aiFiltered;
    }

    // ── Phase 1: traditional pre-filter ──────────────────────────────────────

    /**
     * Excludes a file only when ALL of the following are true:
     *   - It has no (or almost no) removed lines  → looks like a pure addition
     *   - Its combined diff content contains no security keywords
     * This is intentionally conservative: ambiguous files are kept.
     */
    public List<JavaFileChange> preFilterOnly(List<JavaFileChange> changes, String cveDescription) {
        // Build CVE-specific tokens from the description (component names, class names)
        Set<String> cveTokens = extractCveTokens(cveDescription);

        List<JavaFileChange> kept = new ArrayList<>();
        for (JavaFileChange c : changes) {
            if (isObviouslyUnrelated(c, cveTokens)) {
                log.debug("DiffFilter: pre-filter excluded '{}'", c.filePath);
            } else {
                kept.add(c);
            }
        }
        return kept;
    }

    private boolean isObviouslyUnrelated(JavaFileChange c, Set<String> cveTokens) {
        // Keep if it has substantial removed code (rewrites / deletions)
        if (c.removedLineCount() >= 5) return false;
        // Keep if it adds a substantial new implementation file; Stage 3 will decide later.
        if (c.addedLineCount() >= 20) return false;

        String combined = (c.filePath + " " + c.removedCode + " " + c.addedCode).toLowerCase();

        // Keep if any security keyword appears
        for (String kw : SECURITY_KEYWORDS) {
            if (combined.contains(kw)) return false;
        }

        // Keep if any CVE-specific token appears (class/component names from description)
        for (String token : cveTokens) {
            if (combined.contains(token)) return false;
        }

        // Pure addition with no security signal → likely a new feature
        return true;
    }

    /** Extract lowercase tokens from the CVE description worth matching against. */
    private Set<String> extractCveTokens(String description) {
        Set<String> tokens = new HashSet<>();
        if (description == null || description.isEmpty()) return tokens;
        // Split on non-alphanumeric; keep tokens ≥ 4 chars that look like class/component names
        for (String word : description.split("[^a-zA-Z0-9]+")) {
            if (word.length() >= 4) tokens.add(word.toLowerCase());
        }
        return tokens;
    }

    // ── Phase 2: AI filter ────────────────────────────────────────────────────

    private List<JavaFileChange> aiFilter(List<JavaFileChange> candidates,
                                           String cveId,
                                           String cveDescription,
                                           String affectedComponent) {
        String prompt = buildAiPrompt(candidates, cveId, cveDescription, affectedComponent);
        try {
            LlmResponse response = llmClient.chat(LlmRequest.reasoning(LlmPromptStage.PATCH_ANALYSIS,
                    promptRegistry.getPrompt("current/patch-diff-relevance-filter"), prompt));
            if (response == null || response.getContent() == null) return candidates;
            return parseAiResponse(response.getContent(), candidates);
        } catch (Exception e) {
            log.warn("DiffFilter: AI call failed ({}), keeping all pre-filtered files", e.getMessage());
            return candidates;
        }
    }

    private String buildAiPrompt(List<JavaFileChange> candidates,
                                  String cveId, String cveDescription, String affectedComponent) {
        StringBuilder sb = new StringBuilder();
        sb.append("CVE: ").append(cveId).append("\n");
        if (cveDescription != null) sb.append("Description: ").append(cveDescription).append("\n");
        if (affectedComponent != null) sb.append("Affected: ").append(affectedComponent).append("\n");
        sb.append("\nClassify each file change:\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            JavaFileChange c = candidates.get(i);
            String shortPath = c.filePath.contains("/")
                    ? c.filePath.substring(c.filePath.lastIndexOf('/') + 1)
                    : c.filePath;
            sb.append(i + 1).append(". ").append(c.filePath).append("\n");
            sb.append("   +").append(c.addedCode.split("\n").length)
              .append("/-").append(c.removedLineCount()).append(" lines");
            if (!c.methodNames.isEmpty()) {
                sb.append(", methods: ").append(String.join(", ", c.methodNames));
            }
            // Include first 3 lines of removed code as context
            String[] removedLines = c.removedCode.split("\n");
            if (removedLines.length > 0) {
                sb.append("\n   removed sample: ");
                for (int j = 0; j < Math.min(3, removedLines.length); j++) {
                    String line = removedLines[j].trim();
                    if (!line.isEmpty()) sb.append(line).append(" ");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private List<JavaFileChange> parseAiResponse(String json, List<JavaFileChange> candidates) {
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```\\s*$", "").trim();
            }
            // Find the JSON array
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start < 0 || end < 0) return candidates;
            cleaned = cleaned.substring(start, end + 1);

            JsonNode array = mapper.readTree(cleaned);
            Set<String> relevant = new HashSet<>();
            for (JsonNode node : array) {
                if (node.path("relevant").asBoolean(false)) {
                    relevant.add(node.path("file").asText(""));
                    String reason = node.path("reason").asText("");
                    log.info("DiffFilter: AI kept '{}' — {}", node.path("file").asText(""), reason);
                } else {
                    log.info("DiffFilter: AI excluded '{}' — {}",
                            node.path("file").asText(""), node.path("reason").asText(""));
                }
            }

            List<JavaFileChange> result = new ArrayList<>();
            for (JavaFileChange c : candidates) {
                // Match by full path or just filename
                String shortName = c.filePath.contains("/")
                        ? c.filePath.substring(c.filePath.lastIndexOf('/') + 1)
                        : c.filePath;
                if (relevant.contains(c.filePath) || relevant.contains(shortName)) {
                    result.add(c);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("DiffFilter: failed to parse AI response: {}", e.getMessage());
            return candidates;
        }
    }
}

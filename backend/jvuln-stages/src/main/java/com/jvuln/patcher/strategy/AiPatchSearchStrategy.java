package com.jvuln.patcher.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.patcher.strategy.LocateStrategy.PatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI-assisted fallback: asks the LLM to reason about the CVE and produce search hints
 * plus missing intelligence, then retries the existing deterministic strategies with those
 * enriched fields. No AI-generated diffs — the AI only surfaces new leads.
 */
@Component
public class AiPatchSearchStrategy {

    private static final Logger log = LoggerFactory.getLogger(AiPatchSearchStrategy.class);
    private static final Pattern GITHUB_REPO_PAT = Pattern.compile("github\\.com/([^/]+)/([^/.]+?)(?:\\.git)?$");
    private static final Pattern VERSION_TOKEN_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)+");


    private final LlmClient llmClient;
    private final MavenSourceDiffStrategy mavenStrategy;
    private final WebClient webClient;
    private final PromptRegistry promptRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiPatchSearchStrategy(LlmClient llmClient,
                                  MavenSourceDiffStrategy mavenStrategy,
                                  PromptRegistry promptRegistry,
                                  @Value("${jvuln.github.token:}") String token) {
        this.llmClient = llmClient;
        this.mavenStrategy = mavenStrategy;
        this.promptRegistry = promptRegistry;
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(30))
                .followRedirect(true);
        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://api.github.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .defaultHeader("User-Agent", "JVuln-Platform/1.0");
        if (token != null && !token.trim().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.webClient = builder.build();
    }

    /**
     * Main entry point called by PatchAnalysisStage when all other strategies have failed.
     */
    public Optional<AiPatchOutcome> locateWithAiHints(String cveId, String sourceRepo,
                                                      String groupId, String artifactId,
                                                      String description, String affectedTo,
                                                      String fixedVersion,
                                                      List<String> articleUrls) {
        log.info("AiPatchSearch: asking LLM for hints on {}", cveId);

        AiHints hints = queryAiForHints(cveId, description, sourceRepo,
                groupId, artifactId, affectedTo, fixedVersion, articleUrls);
        if (hints == null) {
            log.warn("AiPatchSearch: no hints returned by LLM");
            return Optional.empty();
        }
        AiEnrichment enrichment = new AiEnrichment(
                choose(hints.sourceRepo, sourceRepo),
                choose(hints.groupId, groupId),
                choose(hints.artifactId, artifactId),
                choose(hints.affectedTo, affectedTo),
                choose(hints.fixedVersion, fixedVersion),
                hints.releaseTag,
                hints.commitSearchTerms,
                hints.reasoning
        );
        log.info("AiPatchSearch: enrichment — sourceRepo={} artifact={}:{} affectedTo={} fixedVersion={} releaseTag={} terms={} reasoning={}",
                enrichment.sourceRepo, enrichment.groupId, enrichment.artifactId, enrichment.affectedTo,
                enrichment.fixedVersion, enrichment.releaseTag, enrichment.commitSearchTerms, enrichment.reasoning);

        // 1. Retry GitHub commit search with AI-suggested terms
        if (enrichment.sourceRepo != null && enrichment.sourceRepo.contains("github.com")
                && !enrichment.commitSearchTerms.isEmpty()) {
            Matcher m = GITHUB_REPO_PAT.matcher(enrichment.sourceRepo);
            if (m.find()) {
                String fullName = resolveCanonicalFullName(m.group(1), m.group(2));
                for (String term : enrichment.commitSearchTerms) {
                    try {
                        Optional<PatchResult> result = searchCommitsByTerm(term, fullName, cveId);
                        if (result.isPresent()) {
                            log.info("AiPatchSearch: found patch via commit search term '{}'", term);
                            return Optional.of(new AiPatchOutcome(result.get(), enrichment));
                        }
                    } catch (Exception e) {
                        log.warn("AiPatchSearch: commit search for '{}' failed: {}", term, e.getMessage());
                    }
                }
            }
        }

        // 2. Retry maven-source-diff with AI-inferred fixed version (only if different from what we tried)
        if (enrichment.groupId != null && !enrichment.groupId.isEmpty()
                && enrichment.artifactId != null && !enrichment.artifactId.isEmpty()
                && enrichment.fixedVersion != null
                && !enrichment.fixedVersion.equals(fixedVersion)) {
            if (!isVersionAfterAffected(enrichment.fixedVersion, enrichment.affectedTo)) {
                log.warn("AiPatchSearch: AI fixedVersion={} is not strictly after affectedTo='{}', rejecting",
                        enrichment.fixedVersion, enrichment.affectedTo);
            } else {
                log.info("AiPatchSearch: retrying maven-source-diff with AI version={}", enrichment.fixedVersion);
                try {
                    Optional<PatchResult> result = mavenStrategy.locateByArtifact(
                            cveId, enrichment.groupId, enrichment.artifactId, enrichment.fixedVersion);
                    if (result.isPresent()) {
                        log.info("AiPatchSearch: found patch via maven-source-diff with AI version");
                        return Optional.of(new AiPatchOutcome(result.get(), enrichment));
                    }
                } catch (Exception e) {
                    log.warn("AiPatchSearch: maven-source-diff with AI version failed: {}", e.getMessage());
                }
            }
        }

        // 3. Retry release tag comparison with AI-suggested tag
        if (enrichment.sourceRepo != null && enrichment.sourceRepo.contains("github.com")
                && enrichment.releaseTag != null) {
            Matcher m = GITHUB_REPO_PAT.matcher(enrichment.sourceRepo);
            if (m.find()) {
                String fullName = resolveCanonicalFullName(m.group(1), m.group(2));
                String[] parts = fullName.split("/", 2);
                String owner = parts[0], repo = parts.length > 1 ? parts[1] : m.group(2);
                log.info("AiPatchSearch: retrying release tag comparison tag={}", enrichment.releaseTag);
                try {
                    Optional<PatchResult> result = diffFromReleaseTag(owner, repo, enrichment.releaseTag, cveId);
                    if (result.isPresent()) {
                        log.info("AiPatchSearch: found patch via release tag {}", enrichment.releaseTag);
                        return Optional.of(new AiPatchOutcome(result.get(), enrichment));
                    }
                } catch (Exception e) {
                    log.warn("AiPatchSearch: release tag diff failed: {}", e.getMessage());
                }
            }
        }

        log.info("AiPatchSearch: no patch found after exhausting all AI-guided retries");
        return Optional.of(new AiPatchOutcome(null, enrichment));
    }

    // ── AI query ────────────────────────────────────────────────────────────────

    private AiHints queryAiForHints(String cveId, String description, String sourceRepo,
                                     String groupId, String artifactId,
                                     String affectedTo, String fixedVersion,
                                     List<String> articleUrls) {
        StringBuilder user = new StringBuilder();
        user.append("CVE ID: ").append(cveId).append("\n");
        if (description != null) user.append("Description: ").append(description).append("\n");
        if (groupId != null)     user.append("Artifact: ").append(groupId).append(":").append(artifactId).append("\n");
        if (sourceRepo != null)  user.append("Source repo: ").append(sourceRepo).append("\n");
        if (affectedTo != null)  user.append("Affected versions: ").append(affectedTo).append("\n");
        if (fixedVersion != null && !fixedVersion.isEmpty())
            user.append("Known fixed version: ").append(fixedVersion).append("\n");
        if (articleUrls != null && !articleUrls.isEmpty()) {
            user.append("Reference URLs:\n");
            articleUrls.stream().limit(5).forEach(u -> user.append("  ").append(u).append("\n"));
        }

        try {
            LlmResponse response = llmClient.chat(LlmRequest.reasoning(LlmPromptStage.PATCH_ANALYSIS,
                    promptRegistry.getPrompt("current/patch-ai-patch-search"), user.toString()));
            if (response == null || response.getContent() == null) return null;
            return parseHints(response.getContent());
        } catch (Exception e) {
            log.warn("AiPatchSearch: LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    private AiHints parseHints(String json) {
        try {
            JsonNode node = mapper.readTree(extractJsonObject(json));
            List<String> terms = new ArrayList<>();
            JsonNode termsNode = node.path("commitSearchTerms");
            if (termsNode.isArray()) {
                for (JsonNode t : termsNode) {
                    String s = t.asText("").trim();
                    if (!s.isEmpty()) terms.add(s);
                }
            }
            String sourceRepo = normalizeRepoUrl(asNullableText(node, "sourceRepo"));
            String groupId = asNullableText(node, "groupId");
            String artifactId = asNullableText(node, "artifactId");
            String affectedTo = normalizeAffectedTo(asNullableText(node, "affectedTo"));
            String fixedVer = node.path("fixedVersion").isNull() ? null : node.path("fixedVersion").asText(null);
            String releaseTag = node.path("releaseTag").isNull() ? null : node.path("releaseTag").asText(null);
            String reasoning = node.path("reasoning").asText("");
            return new AiHints(sourceRepo, groupId, artifactId, affectedTo, terms, fixedVer, releaseTag, reasoning);
        } catch (Exception e) {
            AiHints fallback = parseHintsFromMarkdown(json);
            if (fallback != null) {
                log.info("AiPatchSearch: parsed hints from markdown fallback");
                return fallback;
            }
            log.warn("AiPatchSearch: failed to parse LLM hints: {} | raw={}", e.getMessage(),
                    json.length() > 200 ? json.substring(0, 200) : json);
            return null;
        }
    }

    private AiHints parseHintsFromMarkdown(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String sourceRepo = firstMatch(raw, "(https?://(?:github|gitee)\\.com/[^\\s)]+)");
        String artifactId = firstMatch(raw, "(?i)artifact\\s*[:：]\\s*`?([a-zA-Z0-9_.-]+)`?");
        String groupId = firstMatch(raw, "(?i)groupId\\s*[:：]\\s*`?([a-zA-Z0-9_.-]+)`?");
        String affectedVersion = firstMatch(raw, "(?i)affected versions?\\s*\\|\\s*v?(\\d+(?:\\.\\d+)+)\\s+and below");
        if (affectedVersion == null) {
            affectedVersion = firstMatch(raw, "(?i)v?(\\d+(?:\\.\\d+)+)\\s+and below");
        }
        String fixedVersion = firstMatch(raw, "(?i)fixed version\\s*[:：]\\s*`?v?(\\d+(?:\\.\\d+)+)`?");
        List<String> terms = new ArrayList<>();
        String component = firstMatch(raw, "(?i)Affected Component\\s*\\|\\s*`?([a-zA-Z0-9_.-]+)`?");
        if (component != null) {
            terms.add(component);
        }
        String reasoning = firstMatch(raw, "(?i)Summary\\s*(.+)");
        if (sourceRepo == null && artifactId == null && affectedVersion == null && fixedVersion == null) {
            return null;
        }
        return new AiHints(
                normalizeRepoUrl(sourceRepo),
                groupId,
                artifactId,
                affectedVersion != null ? "<= " + affectedVersion : null,
                terms,
                fixedVersion,
                null,
                reasoning == null ? "" : reasoning
        );
    }

    // ── GitHub helpers ───────────────────────────────────────────────────────────

    private Optional<PatchResult> searchCommitsByTerm(String term, String fullName, String cveId) {
        String searchJson = webClient.get()
                .uri("/search/commits?q=" + encode(term) + "+repo:" + fullName)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (searchJson == null) return Optional.empty();
        JsonNode result;
        try { result = mapper.readTree(searchJson); } catch (Exception e) { return Optional.empty(); }

        JsonNode items = result.path("items");
        if (!items.isArray() || items.isEmpty()) return Optional.empty();
        log.info("AiPatchSearch: term '{}' → {} commit(s)", term, items.size());

        String[] parts = fullName.split("/", 2);
        String owner = parts[0], repo = parts.length > 1 ? parts[1] : fullName;

        for (JsonNode item : items) {
            String sha = item.path("sha").asText("");
            String message = item.path("commit").path("message").asText("");
            if (sha.isEmpty()) continue;
            try {
                String diff = webClient.get()
                        .uri("/repos/" + owner + "/" + repo + "/commits/" + sha)
                        .header("Accept", "application/vnd.github.diff")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                if (diff != null && diff.contains("diff --git")) {
                    String commitUrl = "https://github.com/" + owner + "/" + repo + "/commit/" + sha;
                    return Optional.of(new PatchResult(commitUrl, sha, message, diff));
                }
            } catch (Exception e) {
                log.warn("AiPatchSearch: diff fetch failed for {}: {}", sha, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<PatchResult> diffFromReleaseTag(String owner, String repo,
                                                       String fixedTag, String cveId) throws Exception {
        String tagsJson = webClient.get()
                .uri("/repos/" + owner + "/" + repo + "/tags?per_page=100")
                .header("Accept", "application/vnd.github+json")
                .retrieve().bodyToMono(String.class).block();
        if (tagsJson == null) return Optional.empty();
        JsonNode tags = mapper.readTree(tagsJson);
        if (!tags.isArray()) return Optional.empty();

        String prevTag = null;
        for (int i = 0; i < tags.size(); i++) {
            if (fixedTag.equals(tags.path(i).path("name").asText(""))) {
                if (i + 1 < tags.size()) prevTag = tags.path(i + 1).path("name").asText(null);
                break;
            }
        }
        if (prevTag == null) {
            log.warn("AiPatchSearch: tag '{}' not found or has no predecessor in {}/{}", fixedTag, owner, repo);
            return Optional.empty();
        }
        log.info("AiPatchSearch: comparing {}/{}  {}...{}", owner, repo, prevTag, fixedTag);

        String compareJson = webClient.get()
                .uri("/repos/" + owner + "/" + repo + "/compare/" + prevTag + "..." + fixedTag)
                .header("Accept", "application/vnd.github+json")
                .retrieve().bodyToMono(String.class).block();
        if (compareJson == null) return Optional.empty();
        JsonNode compare = mapper.readTree(compareJson);
        JsonNode commits = compare.path("commits");
        if (!commits.isArray() || commits.isEmpty()) return Optional.empty();

        StringBuilder combinedDiff = new StringBuilder();
        String firstUrl = null, firstHash = null;
        for (JsonNode c : commits) {
            String sha = c.path("sha").asText("");
            String msg = c.path("commit").path("message").asText("").toLowerCase();
            boolean relevant = msg.contains("fix") || msg.contains("security") || msg.contains("cve")
                    || msg.contains("inject") || msg.contains("patch") || msg.contains("vuln");
            if (!relevant && commits.size() > 5) continue;
            try {
                String diff = webClient.get()
                        .uri("/repos/" + owner + "/" + repo + "/commits/" + sha)
                        .header("Accept", "application/vnd.github.diff")
                        .retrieve().bodyToMono(String.class).block();
                if (diff != null && diff.contains("diff --git")) {
                    if (firstUrl == null) {
                        firstUrl = "https://github.com/" + owner + "/" + repo + "/commit/" + sha;
                        firstHash = sha;
                    }
                    combinedDiff.append(diff).append("\n");
                }
            } catch (Exception e) {
                log.warn("AiPatchSearch: diff fetch failed for {}: {}", sha, e.getMessage());
            }
        }
        if (combinedDiff.length() == 0) return Optional.empty();
        return Optional.of(new PatchResult(firstUrl, firstHash, "", combinedDiff.toString()));
    }

    private String resolveCanonicalFullName(String owner, String repo) {
        try {
            String json = webClient.get().uri("/repos/" + owner + "/" + repo)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve().bodyToMono(String.class).block();
            if (json != null) {
                JsonNode node = mapper.readTree(json);
                String fn = node.path("full_name").asText(null);
                if (fn != null && !fn.isEmpty()) return fn;
            }
        } catch (Exception e) {
            log.debug("AiPatchSearch: repo resolution failed for {}/{}: {}", owner, repo, e.getMessage());
        }
        return owner + "/" + repo;
    }

    private String choose(String preferred, String fallback) {
        return preferred != null && !preferred.trim().isEmpty() ? preferred.trim() : fallback;
    }

    private String normalizeRepoUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        Matcher github = GITHUB_REPO_PAT.matcher(trimmed);
        if (github.find()) {
            return "https://github.com/" + github.group(1) + "/" + github.group(2);
        }
        Matcher gitee = Pattern.compile("gitee\\.com/([^/]+)/([^/.]+?)(?:\\.git)?$").matcher(trimmed);
        if (gitee.find()) {
            return "https://gitee.com/" + gitee.group(1) + "/" + gitee.group(2);
        }
        return trimmed;
    }

    private String normalizeAffectedTo(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("<=") || trimmed.startsWith("<")) {
            return trimmed;
        }
        Matcher matcher = VERSION_TOKEN_PATTERN.matcher(trimmed);
        if (!matcher.find()) {
            return trimmed;
        }
        return "<= " + matcher.group();
    }

    private String asNullableText(JsonNode node, String field) {
        JsonNode valueNode = node.path(field);
        if (valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        String value = valueNode.asText(null);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String firstMatch(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractJsonObject(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z0-9_-]*\\n?", "")
                    .replaceAll("```\\s*$", "")
                    .trim();
        }
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
            return cleaned;
        }
        int start = cleaned.indexOf('{');
        if (start < 0) {
            return cleaned;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int i = start; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                escaping = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return cleaned.substring(start, i + 1);
                }
            }
        }
        return cleaned;
    }

    private static String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s.replace(" ", "+"); }
    }

    /**
     * Returns true only if candidate is strictly greater than the last affected version
     * implied by affectedTo (e.g. "<= 5.8.11" → candidate must be > 5.8.11).
     * Returns true when affectedTo is null/empty (no constraint to check against).
     */
    static boolean isVersionAfterAffected(String candidate, String affectedTo) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (affectedTo == null || affectedTo.trim().isEmpty()) return true;
        String trimmed = affectedTo.trim();
        String lastAffected;
        if (trimmed.startsWith("<= ")) {
            lastAffected = trimmed.substring(3).trim();
            // candidate must be strictly > lastAffected
            return compareVersions(candidate, lastAffected) > 0;
        } else if (trimmed.startsWith("< ")) {
            lastAffected = trimmed.substring(2).trim();
            // candidate must be >= lastAffected (lastAffected itself is already the fix)
            return compareVersions(candidate, lastAffected) >= 0;
        } else {
            // Best-effort: extract version token and treat as "<="
            Matcher m = Pattern.compile("[\\d]+(?:\\.[\\d]+)+").matcher(trimmed);
            if (!m.find()) return true;
            lastAffected = m.group();
            return compareVersions(candidate, lastAffected) > 0;
        }
    }

    /** Numeric segment-by-segment version comparison. Returns negative/0/positive like compareTo. */
    static int compareVersions(String v1, String v2) {
        String[] p1 = v1.split("[.\\-]");
        String[] p2 = v2.split("[.\\-]");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < p1.length ? parseSegment(p1[i]) : 0;
            int n2 = i < p2.length ? parseSegment(p2[i]) : 0;
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    private static int parseSegment(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    // ── Hints DTO ────────────────────────────────────────────────────────────────

    private static class AiHints {
        final String sourceRepo;
        final String groupId;
        final String artifactId;
        final String affectedTo;
        final List<String> commitSearchTerms;
        final String fixedVersion;
        final String releaseTag;
        final String reasoning;

        AiHints(String sourceRepo, String groupId, String artifactId, String affectedTo,
                List<String> commitSearchTerms, String fixedVersion,
                String releaseTag, String reasoning) {
            this.sourceRepo = sourceRepo;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.affectedTo = affectedTo;
            this.commitSearchTerms = commitSearchTerms != null ? commitSearchTerms : Collections.emptyList();
            this.fixedVersion = fixedVersion;
            this.releaseTag = releaseTag;
            this.reasoning = reasoning;
        }
    }

    public static class AiEnrichment {
        private final String sourceRepo;
        private final String groupId;
        private final String artifactId;
        private final String affectedTo;
        private final String fixedVersion;
        private final String releaseTag;
        private final List<String> commitSearchTerms;
        private final String reasoning;

        AiEnrichment(String sourceRepo, String groupId, String artifactId, String affectedTo,
                     String fixedVersion, String releaseTag, List<String> commitSearchTerms,
                     String reasoning) {
            this.sourceRepo = sourceRepo;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.affectedTo = affectedTo;
            this.fixedVersion = fixedVersion;
            this.releaseTag = releaseTag;
            this.commitSearchTerms = commitSearchTerms != null ? commitSearchTerms : Collections.emptyList();
            this.reasoning = reasoning;
        }

        public String getSourceRepo() { return sourceRepo; }
        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public String getAffectedTo() { return affectedTo; }
        public String getFixedVersion() { return fixedVersion; }
        public String getReleaseTag() { return releaseTag; }
        public List<String> getCommitSearchTerms() { return commitSearchTerms; }
        public String getReasoning() { return reasoning; }
    }

    public static class AiPatchOutcome {
        private final PatchResult patchResult;
        private final AiEnrichment enrichment;

        AiPatchOutcome(PatchResult patchResult, AiEnrichment enrichment) {
            this.patchResult = patchResult;
            this.enrichment = enrichment;
        }

        public PatchResult getPatchResult() { return patchResult; }
        public AiEnrichment getEnrichment() { return enrichment; }
    }
}

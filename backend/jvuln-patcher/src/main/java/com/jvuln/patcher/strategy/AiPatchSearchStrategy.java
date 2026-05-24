package com.jvuln.patcher.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
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
 * (commit keywords, fixed version, release tag), then retries the existing deterministic
 * strategies with those hints.  No AI-generated diffs — the AI only surfaces new leads.
 */
@Component
public class AiPatchSearchStrategy {

    private static final Logger log = LoggerFactory.getLogger(AiPatchSearchStrategy.class);
    private static final Pattern REPO_PAT = Pattern.compile("github\\.com/([^/]+)/([^/.]+?)(?:\\.git)?$");

    private static final String SYSTEM_PROMPT =
            "You are a security patch research assistant with deep knowledge of open-source Java CVEs.\n" +
            "Given CVE details, output ONLY valid JSON matching this schema — no markdown, no explanation:\n" +
            "{\n" +
            "  \"commitSearchTerms\": [\"keyword1\", \"keyword2\"],\n" +
            "  \"fixedVersion\": \"1.2.3\",\n" +
            "  \"releaseTag\": \"v1.2.3\",\n" +
            "  \"reasoning\": \"one sentence\"\n" +
            "}\n" +
            "Rules:\n" +
            "- commitSearchTerms: 1-3 keywords LIKELY IN THE FIX COMMIT MESSAGE (not the CVE ID itself).\n" +
            "  Think about what a developer would write: class names, method names, feature names.\n" +
            "- fixedVersion: the exact Maven version string that first contains the fix, or null.\n" +
            "- releaseTag: the exact GitHub release tag for the fix (e.g. 'v3.5.3.1' or '3.5.3.1'), or null.\n" +
            "- If you are not confident about a field, set it to null rather than guessing.";

    private final LlmClient llmClient;
    private final MavenSourceDiffStrategy mavenStrategy;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiPatchSearchStrategy(LlmClient llmClient,
                                  MavenSourceDiffStrategy mavenStrategy,
                                  @Value("${jvuln.github.token:}") String token) {
        this.llmClient = llmClient;
        this.mavenStrategy = mavenStrategy;
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
     * Main entry point called by PatchLocateStage when all other strategies have failed.
     */
    public Optional<PatchResult> locateWithAiHints(String cveId, String sourceRepo,
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
        log.info("AiPatchSearch: hints — terms={} fixedVersion={} releaseTag={} reasoning={}",
                hints.commitSearchTerms, hints.fixedVersion, hints.releaseTag, hints.reasoning);

        // 1. Retry GitHub commit search with AI-suggested terms
        if (sourceRepo != null && sourceRepo.contains("github.com") && !hints.commitSearchTerms.isEmpty()) {
            Matcher m = REPO_PAT.matcher(sourceRepo);
            if (m.find()) {
                String fullName = resolveCanonicalFullName(m.group(1), m.group(2));
                for (String term : hints.commitSearchTerms) {
                    try {
                        Optional<PatchResult> result = searchCommitsByTerm(term, fullName, cveId);
                        if (result.isPresent()) {
                            log.info("AiPatchSearch: found patch via commit search term '{}'", term);
                            return result;
                        }
                    } catch (Exception e) {
                        log.warn("AiPatchSearch: commit search for '{}' failed: {}", term, e.getMessage());
                    }
                }
            }
        }

        // 2. Retry maven-source-diff with AI-inferred fixed version (only if different from what we tried)
        if (groupId != null && !groupId.isEmpty() && hints.fixedVersion != null
                && !hints.fixedVersion.equals(fixedVersion)) {
            log.info("AiPatchSearch: retrying maven-source-diff with AI version={}", hints.fixedVersion);
            try {
                Optional<PatchResult> result = mavenStrategy.locateByArtifact(
                        cveId, groupId, artifactId, hints.fixedVersion);
                if (result.isPresent()) {
                    log.info("AiPatchSearch: found patch via maven-source-diff with AI version");
                    return result;
                }
            } catch (Exception e) {
                log.warn("AiPatchSearch: maven-source-diff with AI version failed: {}", e.getMessage());
            }
        }

        // 3. Retry release tag comparison with AI-suggested tag
        if (sourceRepo != null && sourceRepo.contains("github.com") && hints.releaseTag != null) {
            Matcher m = REPO_PAT.matcher(sourceRepo);
            if (m.find()) {
                String fullName = resolveCanonicalFullName(m.group(1), m.group(2));
                String[] parts = fullName.split("/", 2);
                String owner = parts[0], repo = parts.length > 1 ? parts[1] : m.group(2);
                log.info("AiPatchSearch: retrying release tag comparison tag={}", hints.releaseTag);
                try {
                    Optional<PatchResult> result = diffFromReleaseTag(owner, repo, hints.releaseTag, cveId);
                    if (result.isPresent()) {
                        log.info("AiPatchSearch: found patch via release tag {}", hints.releaseTag);
                        return result;
                    }
                } catch (Exception e) {
                    log.warn("AiPatchSearch: release tag diff failed: {}", e.getMessage());
                }
            }
        }

        log.info("AiPatchSearch: no patch found after exhausting all AI-guided retries");
        return Optional.empty();
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
            LlmResponse response = llmClient.chat(
                    LlmRequest.reasoning(SYSTEM_PROMPT, user.toString()));
            if (response == null || response.getContent() == null) return null;
            return parseHints(response.getContent());
        } catch (Exception e) {
            log.warn("AiPatchSearch: LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    private AiHints parseHints(String json) {
        try {
            // Strip markdown fences if present
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```\\s*$", "").trim();
            }
            JsonNode node = mapper.readTree(cleaned);
            List<String> terms = new ArrayList<>();
            JsonNode termsNode = node.path("commitSearchTerms");
            if (termsNode.isArray()) {
                for (JsonNode t : termsNode) {
                    String s = t.asText("").trim();
                    if (!s.isEmpty()) terms.add(s);
                }
            }
            String fixedVer = node.path("fixedVersion").isNull() ? null : node.path("fixedVersion").asText(null);
            String releaseTag = node.path("releaseTag").isNull() ? null : node.path("releaseTag").asText(null);
            String reasoning = node.path("reasoning").asText("");
            return new AiHints(terms, fixedVer, releaseTag, reasoning);
        } catch (Exception e) {
            log.warn("AiPatchSearch: failed to parse LLM hints: {} | raw={}", e.getMessage(),
                    json.length() > 200 ? json.substring(0, 200) : json);
            return null;
        }
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

    private static String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s.replace(" ", "+"); }
    }

    // ── Hints DTO ────────────────────────────────────────────────────────────────

    private static class AiHints {
        final List<String> commitSearchTerms;
        final String fixedVersion;
        final String releaseTag;
        final String reasoning;

        AiHints(List<String> commitSearchTerms, String fixedVersion,
                String releaseTag, String reasoning) {
            this.commitSearchTerms = commitSearchTerms != null ? commitSearchTerms : Collections.emptyList();
            this.fixedVersion = fixedVersion;
            this.releaseTag = releaseTag;
            this.reasoning = reasoning;
        }
    }
}

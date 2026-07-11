package com.jvuln.patcher.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.util.RequestLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches GitHub commit history for commits whose message contains the CVE ID.
 * Handles cases where neither GHSA nor NVD provide commit or release tag references.
 */
@Component
public class CveCommitSearchStrategy implements LocateStrategy {

    private static final Logger log = LoggerFactory.getLogger(CveCommitSearchStrategy.class);
    private static final Pattern REPO_PAT = Pattern.compile("github\\.com/([^/]+)/([^/.]+?)(?:\\.git)?$");

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public CveCommitSearchStrategy(@Value("${jvuln.github.token:}") String token) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(30))
                .followRedirect(true);
        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://api.github.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .filter(RequestLogContext.webRequestFilter())
                .defaultHeader("User-Agent", "JVuln-Platform/1.0");
        if (token != null && !token.trim().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.webClient = builder.build();
    }

    @Override
    public String name() { return "cve-commit-search"; }

    @Override
    public int priority() { return 3; }

    @Override
    public Optional<PatchResult> locate(String cveId, String sourceRepo, List<String> knownCommits)
            throws Exception {
        if (sourceRepo == null || !sourceRepo.contains("github.com")) return Optional.empty();

        Matcher m = REPO_PAT.matcher(sourceRepo);
        if (!m.find()) return Optional.empty();
        String owner = m.group(1);
        String repo  = m.group(2);

        // Resolve canonical owner/repo — the repo may have been transferred/renamed
        String fullName = resolveCanonicalFullName(owner, repo);
        log.info("CveCommitSearch: searching for {} in {} (sourceRepo={})", cveId, fullName, owner + "/" + repo);

        String searchJson = webClient.get()
                .uri("/search/commits?q=" + cveId + "+repo:" + fullName)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (searchJson == null) return Optional.empty();
        JsonNode result = mapper.readTree(searchJson);
        JsonNode items = result.path("items");
        if (!items.isArray() || items.isEmpty()) {
            log.info("CveCommitSearch: no commits found for {} in {}", cveId, fullName);
            return Optional.empty();
        }

        log.info("CveCommitSearch: found {} commit(s) mentioning {}", items.size(), cveId);

        // Use the canonical owner/repo for subsequent diff fetches
        String[] parts = fullName.split("/", 2);
        String canonOwner = parts[0], canonRepo = parts.length > 1 ? parts[1] : repo;

        // Collect all candidates with their diffs, then pick the best-scoring one.
        // Avoids returning a doc/changelog commit when a real code fix is also present.
        List<PatchResult> candidates = new ArrayList<>();
        for (JsonNode item : items) {
            String sha     = item.path("sha").asText("");
            String message = item.path("commit").path("message").asText("");
            if (sha.isEmpty()) continue;

            try {
                String diff = webClient.get()
                        .uri("/repos/" + canonOwner + "/" + canonRepo + "/commits/" + sha)
                        .header("Accept", "application/vnd.github.diff")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                if (diff != null && diff.contains("diff --git")) {
                    String shortSha = sha.substring(0, Math.min(10, sha.length()));
                    log.info("CveCommitSearch: candidate {} ('{}'), score={}, size={}c",
                            shortSha, message.split("\n")[0],
                            scoreCommit(message, diff), diff.length());
                    String commitUrl = "https://github.com/" + canonOwner + "/" + canonRepo + "/commit/" + sha;
                    candidates.add(new PatchResult(commitUrl, sha, message, diff));
                }
            } catch (Exception e) {
                log.warn("CveCommitSearch: failed to fetch diff for {}: {}", sha, e.getMessage());
            }
        }

        return candidates.stream()
                .filter(pr -> pr.getRawDiff().contains(".java b/"))  // must have Java source changes
                .max(Comparator.comparingInt(pr -> scoreCommit(pr.getCommitMessage(), pr.getRawDiff())))
                .map(pr -> {
                    log.info("CveCommitSearch: selected '{}' (score={})",
                            pr.getCommitMessage().split("\n")[0],
                            scoreCommit(pr.getCommitMessage(), pr.getRawDiff()));
                    return pr;
                });
    }

    /**
     * Score a commit candidate. Higher = more likely to be the real security fix.
     * - Penalise documentation/changelog/release commits
     * - Reward commits that change .java files
     * - Reward larger diffs (real fixes tend to be bigger than doc edits)
     */
    private int scoreCommit(String message, String diff) {
        int score = 0;
        String msgLower = message.toLowerCase();

        // Penalise obvious non-fix commit types
        if (msgLower.matches("(?s)^\\[(docs?|doc|changelog|release|test|ci)\\].*")) score -= 20;
        if (msgLower.contains("release note") || msgLower.contains("changelog")
                || msgLower.startsWith("bump ") || msgLower.startsWith("prepare release")) score -= 15;
        if (msgLower.contains("readme") || msgLower.contains("[doc") || msgLower.startsWith("docs:")) score -= 10;

        // Reward Java source file changes
        if (diff.contains(".java\n") || diff.contains(".java b/")) score += 10;

        // Reward larger diffs up to a cap (docs edits are usually tiny)
        score += Math.min(diff.length() / 500, 10);

        return score;
    }

    /** Resolves the canonical "owner/repo" by following any GitHub repo transfers/renames. */
    private String resolveCanonicalFullName(String owner, String repo) {        try {
            String repoJson = webClient.get()
                    .uri("/repos/" + owner + "/" + repo)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (repoJson != null) {
                JsonNode node = mapper.readTree(repoJson);
                String fullName = node.path("full_name").asText(null);
                if (fullName != null && !fullName.isEmpty()) return fullName;
            }
        } catch (Exception e) {
            log.debug("CveCommitSearch: repo resolution failed for {}/{}: {}", owner, repo, e.getMessage());
        }
        return owner + "/" + repo;
    }
}

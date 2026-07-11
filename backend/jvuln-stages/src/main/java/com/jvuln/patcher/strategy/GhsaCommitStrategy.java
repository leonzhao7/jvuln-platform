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
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent of Stage 1 results: queries GHSA directly for the CVE,
 * extracts fix commits from advisory references, then fetches each diff.
 * Works even when Stage 1 didn't run or didn't collect commits.
 * Also handles release tag references when no commit URLs are present.
 */
@Component
public class GhsaCommitStrategy implements LocateStrategy {

    private static final Logger log = LoggerFactory.getLogger(GhsaCommitStrategy.class);
    private static final Pattern COMMIT_PAT = Pattern.compile(
            "github\\.com/([^/]+)/([^/]+)/commit/([a-f0-9]{7,40})");
    private static final Pattern RELEASE_PAT = Pattern.compile(
            "github\\.com/([^/]+)/([^/]+)/releases/tag/([^/\\s]+)");

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GhsaCommitStrategy(@Value("${jvuln.github.token:}") String token) {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(30));
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
    public String name() { return "ghsa-commit"; }

    @Override
    public int priority() { return 2; }

    @Override
    public Optional<PatchResult> locate(String cveId, String sourceRepo, List<String> knownCommits)
            throws Exception {
        log.info("GhsaCommitStrategy: querying GHSA for {}", cveId);

        String raw = webClient.get()
                .uri("/advisories?cve_id=" + cveId)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (raw == null) return Optional.empty();
        JsonNode advisories = mapper.readTree(raw);
        if (!advisories.isArray() || advisories.isEmpty()) return Optional.empty();

        List<String> commitUrls = new ArrayList<>();
        for (JsonNode ref : advisories.path(0).path("references")) {
            String url = ref.asText("");
            if (COMMIT_PAT.matcher(url).find()) commitUrls.add(url);
        }
        log.info("GhsaCommitStrategy: found {} commit refs for {}", commitUrls.size(), cveId);

        for (String url : commitUrls) {
            Matcher m = COMMIT_PAT.matcher(url);
            if (!m.find()) continue;
            String owner = m.group(1);
            String repo  = m.group(2);
            String hash  = m.group(3);

            try {
                String diff = webClient.get()
                        .uri("/repos/" + owner + "/" + repo + "/commits/" + hash)
                        .header("Accept", "application/vnd.github.diff")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                if (diff != null && diff.contains("diff --git")) {
                    log.info("GhsaCommitStrategy: got diff for {}, size={}c", hash, diff.length());
                    return Optional.of(new PatchResult(url, hash, "", diff));
                }
            } catch (Exception e) {
                log.warn("GhsaCommitStrategy: failed to fetch commit {}: {}", hash, e.getMessage());
            }
        }

        // Fallback: handle release tag references — compare adjacent tags to find security commits
        return locateByReleaseTag(cveId, advisories.path(0).path("references"));
    }

    private Optional<PatchResult> locateByReleaseTag(String cveId, JsonNode references) {
        for (JsonNode ref : references) {
            String url = ref.asText("");
            Matcher m = RELEASE_PAT.matcher(url);
            if (!m.find()) continue;
            String owner   = m.group(1);
            String repo    = m.group(2);
            String fixedTag = m.group(3);
            log.info("GhsaCommitStrategy: trying release tag fallback {}/{} tag={}", owner, repo, fixedTag);
            try {
                Optional<PatchResult> result = diffFromReleaseTag(owner, repo, fixedTag, cveId);
                if (result.isPresent()) return result;
            } catch (Exception e) {
                log.warn("GhsaCommitStrategy: release tag fallback failed for {}: {}", fixedTag, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<PatchResult> diffFromReleaseTag(String owner, String repo,
                                                       String fixedTag, String cveId) throws Exception {
        // Fetch tag list to find the previous tag
        String tagsJson = webClient.get()
                .uri("/repos/" + owner + "/" + repo + "/tags?per_page=100")
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .bodyToMono(String.class)
                .block();

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
            log.warn("GhsaCommitStrategy: cannot find tag before {} in {}/{}", fixedTag, owner, repo);
            return Optional.empty();
        }
        log.info("GhsaCommitStrategy: comparing {}/{}  {}...{}", owner, repo, prevTag, fixedTag);

        // Get commits between prevTag and fixedTag
        String compareJson = webClient.get()
                .uri("/repos/" + owner + "/" + repo + "/compare/" + prevTag + "..." + fixedTag)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (compareJson == null) return Optional.empty();
        JsonNode compare = mapper.readTree(compareJson);
        JsonNode commits = compare.path("commits");
        if (!commits.isArray() || commits.isEmpty()) return Optional.empty();

        // Pick security-relevant commits (prefer fix/security/cve keywords in message)
        StringBuilder combinedDiff = new StringBuilder();
        String firstCommitUrl = null;
        String firstHash = null;
        for (JsonNode c : commits) {
            String sha     = c.path("sha").asText("");
            String message = c.path("commit").path("message").asText("").toLowerCase();
            boolean relevant = message.contains("fix") || message.contains("security")
                    || message.contains("cve") || message.contains("jndi")
                    || message.contains("inject") || message.contains("patch");
            if (!relevant && commits.size() > 5) continue; // skip noise when many commits
            try {
                String diff = webClient.get()
                        .uri("/repos/" + owner + "/" + repo + "/commits/" + sha)
                        .header("Accept", "application/vnd.github.diff")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                if (diff != null && diff.contains("diff --git")) {
                    if (firstCommitUrl == null) {
                        firstCommitUrl = "https://github.com/" + owner + "/" + repo + "/commit/" + sha;
                        firstHash = sha;
                    }
                    combinedDiff.append(diff).append("\n");
                }
            } catch (Exception e) {
                log.warn("GhsaCommitStrategy: failed to fetch diff for commit {}: {}", sha, e.getMessage());
            }
        }

        if (combinedDiff.length() == 0) return Optional.empty();
        log.info("GhsaCommitStrategy: release tag fallback produced diff size={}c", combinedDiff.length());
        return Optional.of(new PatchResult(firstCommitUrl, firstHash, "", combinedDiff.toString()));
    }
}

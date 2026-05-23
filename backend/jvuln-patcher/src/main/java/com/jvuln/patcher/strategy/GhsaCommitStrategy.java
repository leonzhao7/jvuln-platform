package com.jvuln.patcher.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Component
public class GhsaCommitStrategy implements LocateStrategy {

    private static final Logger log = LoggerFactory.getLogger(GhsaCommitStrategy.class);
    private static final Pattern COMMIT_PAT = Pattern.compile(
            "github\\.com/([^/]+)/([^/]+)/commit/([a-f0-9]{7,40})");

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GhsaCommitStrategy(@Value("${jvuln.github.token:}") String token) {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(30));
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
        return Optional.empty();
    }
}

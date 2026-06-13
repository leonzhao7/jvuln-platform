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
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches commit diffs via GitHub REST API (api.github.com), not the web UI.
 * Using the API endpoint avoids proxy/redirect issues with github.com.
 */
@Component
public class RefCommitStrategy implements LocateStrategy {

    private static final Logger log = LoggerFactory.getLogger(RefCommitStrategy.class);
    private static final Pattern COMMIT_URL = Pattern.compile(
            "github\\.com/([^/]+)/([^/]+)/commit/([a-f0-9]+)");
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public RefCommitStrategy(@Value("${jvuln.github.token:}") String token) {
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
    public String name() { return "reference-commit"; }

    @Override
    public int priority() { return 1; }

    @Override
    public Optional<PatchResult> locate(String cveId, String sourceRepo, List<String> knownCommits) {
        for (String url : knownCommits) {
            Matcher m = COMMIT_URL.matcher(url);
            if (!m.find()) continue;

            String owner = m.group(1);
            String repo  = m.group(2);
            String hash  = m.group(3);

            log.info("Fetching commit diff via API: {}/{}/{}", owner, repo, hash);
            try {
                // Use GitHub API with diff Accept header — same domain as GHSA, avoids proxy issues
                String diff = webClient.get()
                        .uri("/repos/" + owner + "/" + repo + "/commits/" + hash)
                        .header("Accept", "application/vnd.github.diff")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                if (diff != null && diff.contains("diff --git")) {
                    log.info("Got diff for commit {}, size={}c", hash, diff.length());
                    String message = fetchCommitMessage(owner, repo, hash);
                    return Optional.of(new PatchResult(url, hash, message, diff));
                }
            } catch (Exception e) {
                log.warn("Failed to fetch commit {} via API: {}", hash, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private String fetchCommitMessage(String owner, String repo, String hash) {
        try {
            String json = webClient.get()
                    .uri("/repos/" + owner + "/" + repo + "/commits/" + hash)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (json != null) {
                JsonNode node = mapper.readTree(json);
                return node.path("commit").path("message").asText("");
            }
        } catch (Exception ignored) {}
        return "";
    }
}

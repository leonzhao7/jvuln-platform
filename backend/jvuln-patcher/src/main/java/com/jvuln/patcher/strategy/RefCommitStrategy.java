package com.jvuln.patcher.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RefCommitStrategy implements LocateStrategy {

    private static final Logger log = LoggerFactory.getLogger(RefCommitStrategy.class);
    private static final Pattern COMMIT_URL = Pattern.compile(
            "github\\.com/([^/]+)/([^/]+)/commit/([a-f0-9]+)");
    private final WebClient webClient = WebClient.builder().build();

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
            String repo = m.group(2);
            String hash = m.group(3);

            log.info("Fetching commit diff: {}/{}/{}", owner, repo, hash);
            try {
                String diff = webClient.get()
                        .uri("https://github.com/" + owner + "/" + repo + "/commit/" + hash + ".diff")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                return Optional.of(new PatchResult(url, hash, "", diff));
            } catch (Exception e) {
                log.warn("Failed to fetch commit {}: {}", hash, e.getMessage());
            }
        }
        return Optional.empty();
    }
}

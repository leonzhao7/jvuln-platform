package com.jvuln.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.store.model.CveIntelligence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//@Component  // Disabled: Gitee search returns too many false positives from affected products' issues
public class GiteeSource implements IntelSource {

    private static final Logger log = LoggerFactory.getLogger(GiteeSource.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_IN_MEMORY_SIZE = 5 * 1024 * 1024;
    private static final Pattern GITEE_URL_PATTERN =
            Pattern.compile("https?://gitee\\.com/([^/]+)/([^/#?]+)(?:/.*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(?:<=?|before|prior to|through|upto|up to|versions?)[^\\d]{0,20}(\\d+(?:\\.\\d+)+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern COORDINATE_PATTERN =
            Pattern.compile("([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.-]+)");

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GiteeSource() {
        HttpClient httpClient = HttpClient.create().responseTimeout(REQUEST_TIMEOUT);
        this.webClient = WebClient.builder()
                .baseUrl("https://gitee.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                        .build())
                .defaultHeader("User-Agent", "JVuln-Platform/1.0")
                .build();
    }

    @Override
    public String name() { return "Gitee"; }

    @Override
    public IntelFragment collect(String cveId) throws Exception {
        log.info("Fetching from Gitee search: {}", cveId);

        String raw = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v5/search/issues")
                        .queryParam("q", cveId)
                        .queryParam("page", 1)
                        .queryParam("per_page", 20)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block(REQUEST_TIMEOUT.plusSeconds(5));

        JsonNode root = mapper.readTree(raw);
        JsonNode issues = root.isArray() ? root : root.path("issues");
        if (!issues.isArray() || issues.isEmpty()) {
            return new IntelFragment(name(), false, "", "", "", "", "", "", "", "", "", "",
                    Collections.<String>emptyList(), Collections.<CveIntelligence.Article>emptyList(), raw);
        }

        String description = "";
        String sourceRepo = "";
        String artifactId = "";
        String affectedTo = "";
        Set<String> articleUrls = new LinkedHashSet<>();
        List<String> fixCommits = new ArrayList<>();
        List<CveIntelligence.Article> articles = new ArrayList<>();

        for (JsonNode issue : issues) {
            String issueUrl = issue.path("html_url").asText("");
            if (issueUrl.isEmpty()) {
                issueUrl = issue.path("url").asText("");
            }
            if (!issueUrl.isEmpty() && articleUrls.add(issueUrl)) {
                articles.add(new CveIntelligence.Article(issue.path("title").asText(""), issueUrl, "Gitee", ""));
            }

            if (description.isEmpty()) {
                description = firstNonBlank(
                        issue.path("title").asText(""),
                        issue.path("body").asText(""));
            }

            String repoUrl = issue.path("repository").path("html_url").asText("");
            if (sourceRepo.isEmpty()) {
                sourceRepo = normalizeRepoUrl(repoUrl);
            }
            if (artifactId.isEmpty() && !sourceRepo.isEmpty()) {
                artifactId = repoNameFromUrl(sourceRepo);
            }
            if (affectedTo.isEmpty()) {
                affectedTo = detectAffectedTo(issue.path("title").asText(""), issue.path("body").asText(""));
            }

            String body = issue.path("body").asText("");
            Matcher commitMatcher = Pattern.compile("https?://gitee\\.com/[^\\s)]+/commit/[0-9a-fA-F]+").matcher(body);
            while (commitMatcher.find()) {
                fixCommits.add(commitMatcher.group());
            }
        }

        if (sourceRepo.isEmpty()) {
            for (CveIntelligence.Article article : articles) {
                Matcher matcher = GITEE_URL_PATTERN.matcher(article.getUrl());
                if (matcher.find()) {
                    sourceRepo = "https://gitee.com/" + matcher.group(1) + "/" + matcher.group(2);
                    if (artifactId.isEmpty()) {
                        artifactId = matcher.group(2);
                    }
                    break;
                }
            }
        }

        return new IntelFragment(name(), true, description, "", "", "",
                detectGroupId(description), artifactId, "", affectedTo, "",
                sourceRepo, fixCommits, articles, raw);
    }

    private String detectAffectedTo(String... texts) {
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            Matcher matcher = VERSION_PATTERN.matcher(text);
            if (matcher.find()) {
                return "<= " + matcher.group(1);
            }
        }
        return "";
    }

    private String detectGroupId(String... texts) {
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            Matcher matcher = COORDINATE_PATTERN.matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private String repoNameFromUrl(String url) {
        Matcher matcher = GITEE_URL_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return "";
    }

    private String normalizeRepoUrl(String url) {
        Matcher matcher = GITEE_URL_PATTERN.matcher(url == null ? "" : url);
        if (matcher.find()) {
            return "https://gitee.com/" + matcher.group(1) + "/" + matcher.group(2);
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}

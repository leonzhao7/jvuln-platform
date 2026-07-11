package com.jvuln.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.util.HttpUtil;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.store.model.SourceData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GhsaSource implements IntelSource {

    private static final Logger log = LoggerFactory.getLogger(GhsaSource.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern RELEASE_TAG =
            Pattern.compile("github\\.com/[^/]+/[^/]+/releases/tag/([^/\\s]+)");
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GhsaSource(@Value("${jvuln.github.token:}") String token) {
        HttpClient httpClient = HttpClient.create().responseTimeout(REQUEST_TIMEOUT);
        WebClient.Builder builder = WebClient.builder().baseUrl("https://api.github.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (token != null && !token.trim().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.webClient = builder.build();
    }

    @Override
    public String name() { return "GHSA"; }

    @Override
    public IntelFragment collect(String cveId) throws Exception {
        log.info("Fetching from GitHub Advisory: {}", cveId);
        String raw;
        try {
            raw = HttpUtil.executeBlockingWithRetry(
                    client -> client.get().uri("/advisories?cve_id=" + cveId)
                            .accept(MediaType.APPLICATION_JSON).retrieve().bodyToMono(String.class),
                    webClient, "GitHub Advisory API request for " + cveId);
        } catch (WebClientResponseException e) {
            if (e.getRawStatusCode() == 404) {
                return IntelFragment.notFound(name(), e.getResponseBodyAsString());
            }
            throw new SourceException("HTTP_" + e.getRawStatusCode(),
                    "GitHub Advisory request failed with HTTP " + e.getRawStatusCode(),
                    e.getResponseBodyAsString(), e);
        }
        try {
            return parsePayload(raw);
        } catch (Exception e) {
            throw new SourceException("PARSE_ERROR",
                    "GitHub Advisory response could not be parsed", raw, e);
        }
    }

    IntelFragment parsePayload(String raw) throws Exception {
        JsonNode advisories = mapper.readTree(raw);
        if (!advisories.isArray() || advisories.size() == 0) {
            return IntelFragment.notFound(name(), raw);
        }
        JsonNode advisory = advisories.path(0);
        PackageFacts facts = packageFacts(advisory.path("vulnerabilities"));
        List<String> fixCommits = new ArrayList<>();
        List<CveIntelligence.Article> articles = new ArrayList<>();
        for (JsonNode ref : advisory.path("references")) {
            String url = ref.isTextual() ? ref.asText("") : ref.path("url").asText("");
            if (url.contains("/commit/")) {
                fixCommits.add(url);
            }
            articles.add(new CveIntelligence.Article("", url, "GHSA", ""));
        }
        if (facts.fixedVersion.isEmpty()) {
            facts.fixedVersion = releaseVersion(articles);
        }
        String description = advisory.path("summary").asText(
                advisory.path("description").asText(""));
        String sourceRepo = advisory.path("source_code_location").asText("");
        SourceData data = new SourceData("", "", "", "", facts.groupId,
                facts.artifactId, "", facts.affectedTo, facts.fixedVersion, sourceRepo,
                fixCommits, articles);
        return IntelFragment.success(name(), description, data, raw);
    }

    private PackageFacts packageFacts(JsonNode vulnerabilities) {
        PackageFacts facts = new PackageFacts();
        if (!vulnerabilities.isArray()) {
            return facts;
        }
        for (JsonNode vulnerability : vulnerabilities) {
            String packageName = vulnerability.path("package").path("name").asText("");
            int separator = packageName.indexOf(':');
            if (separator > 0) {
                facts.groupId = packageName.substring(0, separator);
                facts.artifactId = packageName.substring(separator + 1);
            }
            facts.affectedTo = vulnerability.path("vulnerable_version_range").asText("");
            JsonNode patched = vulnerability.path("first_patched_version");
            if (patched.isTextual()) {
                facts.fixedVersion = patched.asText("");
            } else {
                facts.fixedVersion = patched.path("identifier").asText("");
            }
        }
        return facts;
    }

    private String releaseVersion(List<CveIntelligence.Article> articles) {
        for (CveIntelligence.Article article : articles) {
            Matcher matcher = RELEASE_TAG.matcher(article.getUrl());
            if (!matcher.find()) {
                continue;
            }
            String version = matcher.group(1)
                    .replaceFirst("^(?:version[-_]?|v|release[-_]?)", "");
            if (version.matches("\\d+\\..*")) {
                return version;
            }
        }
        return "";
    }

    private static class PackageFacts {
        private String groupId = "";
        private String artifactId = "";
        private String affectedTo = "";
        private String fixedVersion = "";
    }
}

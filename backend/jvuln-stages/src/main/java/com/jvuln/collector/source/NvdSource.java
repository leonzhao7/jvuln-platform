package com.jvuln.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.store.model.SourceData;
import com.jvuln.util.RequestLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
public class NvdSource implements IntelSource {

    private static final Logger log = LoggerFactory.getLogger(NvdSource.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public NvdSource(@Value("${jvuln.nvd.api-key:}") String apiKey) {
        HttpClient httpClient = HttpClient.create().responseTimeout(REQUEST_TIMEOUT);
        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://services.nvd.nist.gov/rest/json/cves/2.0")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(RequestLogContext.webRequestFilter());
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader("apiKey", apiKey);
        }
        this.webClient = builder.build();
    }

    @Override
    public String name() { return "NVD"; }

    @Override
    public IntelFragment collect(String cveId) throws Exception {
        log.info("Fetching from NVD: {}", cveId);
        String raw;
        try {
            raw = webClient.get()
                    .uri(uriBuilder -> uriBuilder.queryParam("cveId", cveId).build())
                    .retrieve().bodyToMono(String.class)
                    .block(REQUEST_TIMEOUT.plusSeconds(5));
        } catch (WebClientResponseException e) {
            if (e.getRawStatusCode() == 404) {
                return IntelFragment.notFound(name(), e.getResponseBodyAsString());
            }
            throw new SourceException("HTTP_" + e.getRawStatusCode(),
                    "NVD request failed with HTTP " + e.getRawStatusCode(),
                    e.getResponseBodyAsString(), e);
        }
        try {
            return parsePayload(raw);
        } catch (Exception e) {
            throw new SourceException("PARSE_ERROR", "NVD response could not be parsed", raw, e);
        }
    }

    IntelFragment parsePayload(String raw) throws Exception {
        JsonNode root = mapper.readTree(raw);
        JsonNode vulnerabilities = root.path("vulnerabilities");
        if (!vulnerabilities.isArray() || vulnerabilities.size() == 0) {
            return IntelFragment.notFound(name(), raw);
        }
        JsonNode vuln = vulnerabilities.path(0).path("cve");
        if (vuln.isMissingNode() || vuln.isNull()) {
            return IntelFragment.notFound(name(), raw);
        }

        String description = englishDescription(vuln);
        String cweId = vuln.path("weaknesses").path(0)
                .path("description").path(0).path("value").asText("");
        JsonNode cvssData = cvssData(vuln.path("metrics"));
        String cvssScore = cvssData.path("baseScore").asText("");
        String cvssVector = cvssData.path("vectorString").asText("");
        String cvssSeverity = cvssData.path("baseSeverity").asText("");

        ConfigurationFacts facts = configurationFacts(vuln.path("configurations"));
        if (facts.affectedTo.isEmpty()) {
            Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)+) and below",
                    Pattern.CASE_INSENSITIVE).matcher(description);
            if (matcher.find()) {
                facts.affectedTo = "<= " + matcher.group(1);
            }
        }

        List<CveIntelligence.Article> articles = new ArrayList<>();
        List<String> fixCommits = new ArrayList<>();
        for (JsonNode ref : vuln.path("references")) {
            String url = ref.path("url").asText("");
            if (url.contains("github.com") && url.contains("/commit/")) {
                fixCommits.add(url);
            }
            articles.add(new CveIntelligence.Article("", url, "NVD", ""));
        }
        SourceData data = new SourceData(cweId, cvssScore, cvssVector, cvssSeverity,
                facts.groupId, facts.artifactId, "", facts.affectedTo, "", "",
                fixCommits, articles);
        return IntelFragment.success(name(), description, data, raw);
    }

    private String englishDescription(JsonNode vuln) {
        for (JsonNode description : vuln.path("descriptions")) {
            if ("en".equals(description.path("lang").asText())) {
                return description.path("value").asText("");
            }
        }
        return "";
    }

    private JsonNode cvssData(JsonNode metrics) {
        JsonNode result = metrics.path("cvssMetricV31").path(0).path("cvssData");
        if (result.isMissingNode()) {
            result = metrics.path("cvssMetricV30").path(0).path("cvssData");
        }
        if (result.isMissingNode()) {
            result = metrics.path("cvssMetricV2").path(0).path("cvssData");
        }
        return result;
    }

    private ConfigurationFacts configurationFacts(JsonNode configurations) {
        ConfigurationFacts facts = new ConfigurationFacts();
        if (!configurations.isArray()) {
            return facts;
        }
        for (JsonNode configuration : configurations) {
            for (JsonNode node : configuration.path("nodes")) {
                for (JsonNode match : node.path("cpeMatch")) {
                    applyCpe(match, facts);
                }
            }
        }
        return facts;
    }

    private void applyCpe(JsonNode match, ConfigurationFacts facts) {
        String criteria = match.path("criteria").asText("");
        if (criteria.contains(":maven:")) {
            String[] parts = criteria.split(":");
            if (parts.length >= 6) {
                facts.groupId = parts[4];
                facts.artifactId = parts[5];
            }
        }
        if (facts.affectedTo.isEmpty()) {
            String inclusive = match.path("versionEndIncluding").asText("");
            String exclusive = match.path("versionEndExcluding").asText("");
            if (!inclusive.isEmpty()) {
                facts.affectedTo = "<= " + inclusive;
            } else if (!exclusive.isEmpty()) {
                facts.affectedTo = "< " + exclusive;
            }
        }
    }

    private static class ConfigurationFacts {
        private String groupId = "";
        private String artifactId = "";
        private String affectedTo = "";
    }
}

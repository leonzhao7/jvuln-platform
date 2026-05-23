package com.jvuln.collector.source;

import com.jvuln.store.model.CveIntelligence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class NvdSource implements IntelSource {

    private static final Logger log = LoggerFactory.getLogger(NvdSource.class);
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public NvdSource(@Value("${jvuln.nvd.api-key:}") String apiKey) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://services.nvd.nist.gov/rest/json/cves/2.0");
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

        String raw = webClient.get()
                .uri(uriBuilder -> uriBuilder.queryParam("cveId", cveId).build())
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode root = mapper.readTree(raw);
        JsonNode vuln = root.path("vulnerabilities").path(0).path("cve");

        String description = "";
        for (JsonNode desc : vuln.path("descriptions")) {
            if ("en".equals(desc.path("lang").asText())) {
                description = desc.path("value").asText();
                break;
            }
        }

        String cweId = "";
        JsonNode weaknesses = vuln.path("weaknesses");
        if (weaknesses.isArray() && weaknesses.size() > 0) {
            cweId = weaknesses.path(0).path("description").path(0).path("value").asText("");
        }

        String cvssScore = "";
        String cvssSeverity = "";
        JsonNode metrics = vuln.path("metrics");
        JsonNode cvssData = metrics.path("cvssMetricV31").path(0).path("cvssData");
        if (cvssData.isMissingNode()) {
            cvssData = metrics.path("cvssMetricV2").path(0).path("cvssData");
        }
        if (!cvssData.isMissingNode()) {
            cvssScore = cvssData.path("baseScore").asText("");
            cvssSeverity = cvssData.path("baseSeverity").asText("");
        }

        List<CveIntelligence.Article> articles = new ArrayList<>();
        List<String> fixCommits = new ArrayList<>();
        for (JsonNode ref : vuln.path("references")) {
            String url = ref.path("url").asText();
            if (url.contains("github.com") && url.contains("/commit/")) {
                fixCommits.add(url);
            }
            articles.add(new CveIntelligence.Article(
                    ref.path("source").asText(""), url, "NVD", ""));
        }

        return new IntelFragment(name(), true, description, cweId, cvssScore, cvssSeverity,
                "", "", "", "", "", "", fixCommits, articles, raw);
    }
}

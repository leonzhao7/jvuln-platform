package com.jvuln.collector.source;

import com.jvuln.store.model.CveIntelligence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class GhsaSource implements IntelSource {

    private static final Logger log = LoggerFactory.getLogger(GhsaSource.class);
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GhsaSource(@Value("${jvuln.github.token:}") String token) {
        WebClient.Builder builder = WebClient.builder().baseUrl("https://api.github.com");
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

        String raw = webClient.get()
                .uri("/advisories?cve_id=" + cveId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode advisories = mapper.readTree(raw);
        if (!advisories.isArray() || advisories.size() == 0) {
            return new IntelFragment(name(), false, "", "", "", "", "", "", "", "", "", "",
                    Collections.<String>emptyList(), Collections.<CveIntelligence.Article>emptyList(), raw);
        }

        JsonNode adv = advisories.path(0);
        String description = adv.path("summary").asText("");
        String groupId = "";
        String artifactId = "";
        String affectedTo = "";
        String fixedVersion = "";
        String sourceRepo = "";
        List<String> fixCommits = new ArrayList<>();
        List<CveIntelligence.Article> articles = new ArrayList<>();

        JsonNode vulnerabilities = adv.path("vulnerabilities");
        if (vulnerabilities.isArray()) {
            for (JsonNode v : vulnerabilities) {
                String pkgName = v.path("package").path("name").asText("");
                if (pkgName.contains(":")) {
                    String[] parts = pkgName.split(":");
                    groupId = parts[0];
                    artifactId = parts.length > 1 ? parts[1] : "";
                }
                affectedTo = v.path("vulnerable_version_range").asText("");
                JsonNode patched = v.path("first_patched_version");
                if (!patched.isMissingNode() && patched.has("identifier")) {
                    fixedVersion = patched.path("identifier").asText("");
                }
            }
        }

        for (JsonNode ref : adv.path("references")) {
            String url = ref.asText("");
            if (url.contains("/commit/")) fixCommits.add(url);
            articles.add(new CveIntelligence.Article("", url, "GHSA", ""));
        }

        sourceRepo = adv.path("source_code_location").asText("");

        return new IntelFragment(name(), true, description, "", "", "",
                groupId, artifactId, "", affectedTo, fixedVersion, sourceRepo,
                fixCommits, articles, raw);
    }
}

package com.jvuln.collector.source;

import com.jvuln.store.model.CveIntelligence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class OsvSource implements IntelSource {

    private static final Logger log = LoggerFactory.getLogger(OsvSource.class);
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OsvSource() {
        this.webClient = WebClient.builder()
                .baseUrl("https://api.osv.dev/v1")
                .build();
    }

    @Override
    public String name() { return "OSV"; }

    @Override
    public IntelFragment collect(String cveId) throws Exception {
        log.info("Fetching from OSV: {}", cveId);

        String raw = webClient.get()
                .uri("/vulns/" + cveId)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode root = mapper.readTree(raw);
        String description = root.path("summary").asText(root.path("details").asText(""));

        String groupId = "";
        String artifactId = "";
        String affectedFrom = "";
        String fixedVersion = "";
        List<String> fixCommits = new ArrayList<>();
        List<CveIntelligence.Article> articles = new ArrayList<>();

        for (JsonNode a : root.path("affected")) {
            if (!"Maven".equalsIgnoreCase(a.path("package").path("ecosystem").asText(""))) continue;

            String pkgName = a.path("package").path("name").asText("");
            if (pkgName.contains(":")) {
                groupId = pkgName.substring(0, pkgName.indexOf(':'));
                artifactId = pkgName.substring(pkgName.indexOf(':') + 1);
            }

            for (JsonNode range : a.path("ranges")) {
                for (JsonNode event : range.path("events")) {
                    if (event.has("introduced")) affectedFrom = event.path("introduced").asText("");
                    if (event.has("fixed")) fixedVersion = event.path("fixed").asText("");
                }
            }
            break;
        }

        for (JsonNode ref : root.path("references")) {
            String url = ref.path("url").asText("");
            if (url.contains("/commit/")) fixCommits.add(url);
            articles.add(new CveIntelligence.Article(ref.path("type").asText(""), url, "OSV", ""));
        }

        return new IntelFragment(name(), true, description, "", "", "",
                groupId, artifactId, affectedFrom, "", fixedVersion, "",
                fixCommits, articles, raw);
    }
}

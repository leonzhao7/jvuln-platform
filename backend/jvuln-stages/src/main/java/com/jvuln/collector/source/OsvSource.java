package com.jvuln.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.store.model.SourceData;
import com.jvuln.util.RequestLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class OsvSource implements IntelSource {

    private static final Logger log = LoggerFactory.getLogger(OsvSource.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OsvSource() {
        HttpClient httpClient = HttpClient.create().responseTimeout(REQUEST_TIMEOUT);
        this.webClient = WebClient.builder().baseUrl("https://api.osv.dev/v1")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(RequestLogContext.webRequestFilter()).build();
    }

    @Override
    public String name() { return "OSV"; }

    @Override
    public IntelFragment collect(String cveId) throws Exception {
        log.info("Fetching from OSV: {}", cveId);
        try {
            String raw = webClient.get().uri("/vulns/" + cveId).retrieve()
                    .bodyToMono(String.class).block(REQUEST_TIMEOUT.plusSeconds(5));
            return parsePayload(raw);
        } catch (WebClientResponseException.NotFound e) {
            return IntelFragment.notFound(name(), e.getResponseBodyAsString());
        } catch (WebClientResponseException e) {
            throw new SourceException("HTTP_" + e.getRawStatusCode(),
                    "OSV request failed with HTTP " + e.getRawStatusCode(),
                    e.getResponseBodyAsString(), e);
        }
    }

    IntelFragment parsePayload(String raw) throws Exception {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            throw new SourceException("PARSE_ERROR", "OSV response could not be parsed", raw, e);
        }
        if (root == null || !root.isObject()
                || (!root.hasNonNull("id") && !root.hasNonNull("summary")
                && !root.hasNonNull("details") && !root.has("affected"))) {
            return IntelFragment.notFound(name(), raw);
        }
        String description = root.path("summary").asText(root.path("details").asText(""));
        PackageFacts facts = packageFacts(root.path("affected"));
        List<String> fixCommits = new ArrayList<>();
        List<CveIntelligence.Article> articles = new ArrayList<>();
        for (JsonNode ref : root.path("references")) {
            String url = ref.path("url").asText("");
            if (url.contains("/commit/")) {
                fixCommits.add(url);
            }
            articles.add(new CveIntelligence.Article(
                    ref.path("type").asText(""), url, "OSV", ""));
        }
        SourceData data = new SourceData("", "", "", "", facts.groupId,
                facts.artifactId, facts.affectedFrom, "", facts.fixedVersion, "",
                fixCommits, articles);
        return IntelFragment.success(name(), description, data, raw);
    }

    private PackageFacts packageFacts(JsonNode affected) {
        PackageFacts facts = new PackageFacts();
        for (JsonNode item : affected) {
            if (!"Maven".equalsIgnoreCase(item.path("package").path("ecosystem").asText(""))) {
                continue;
            }
            String packageName = item.path("package").path("name").asText("");
            int separator = packageName.indexOf(':');
            if (separator > 0) {
                facts.groupId = packageName.substring(0, separator);
                facts.artifactId = packageName.substring(separator + 1);
            }
            for (JsonNode range : item.path("ranges")) {
                for (JsonNode event : range.path("events")) {
                    if (event.has("introduced")) {
                        facts.affectedFrom = event.path("introduced").asText("");
                    }
                    if (event.has("fixed")) {
                        facts.fixedVersion = event.path("fixed").asText("");
                    }
                }
            }
            break;
        }
        return facts;
    }

    private static class PackageFacts {
        private String groupId = "";
        private String artifactId = "";
        private String affectedFrom = "";
        private String fixedVersion = "";
    }
}

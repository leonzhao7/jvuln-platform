package com.jvuln.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.util.RequestLogContext;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReferenceEnricher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReferenceEnricher.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_FETCHED_REFERENCES = 12;

    private static final Pattern GITHUB_COMMIT = Pattern.compile(
            "https?://github\\.com/([^/]+)/([^/]+)/commit/([a-f0-9]{7,40})", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITEE_COMMIT = Pattern.compile(
            "https?://gitee\\.com/([^/]+)/([^/]+)/commit/([a-f0-9]{7,40})", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_COMPARE = Pattern.compile(
            "https?://github\\.com/([^/]+)/([^/]+)/compare/([^\\s?#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITEE_COMPARE = Pattern.compile(
            "https?://gitee\\.com/([^/]+)/([^/]+)/compare/([^\\s?#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_RELEASE = Pattern.compile(
            "https?://github\\.com/([^/]+)/([^/]+)/releases/tag/([^\\s?#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITEE_RELEASE = Pattern.compile(
            "https?://gitee\\.com/([^/]+)/([^/]+)/releases/tag/([^\\s?#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_ISSUE = Pattern.compile(
            "https?://github\\.com/([^/]+)/([^/]+)/(issues|pull)/([0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITEE_ISSUE = Pattern.compile(
            "https?://gitee\\.com/([^/]+)/([^/]+)/issues/([^\\s/#?]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_ADVISORY = Pattern.compile(
            "https?://github\\.com/advisories/([A-Za-z0-9-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

    private final WebClient githubApiClient;
    private final WebClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReferenceEnricher(@Value("${jvuln.github.token:}") String token) {
        HttpClient reactorClient = HttpClient.create().responseTimeout(REQUEST_TIMEOUT);

        WebClient.Builder apiBuilder = WebClient.builder()
                .baseUrl("https://api.github.com")
                .clientConnector(new ReactorClientHttpConnector(reactorClient))
                .defaultHeader("User-Agent", "JVuln-Platform/1.0")
                .filter(RequestLogContext.webRequestFilter())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024));
        if (token != null && !token.trim().isEmpty()) {
            apiBuilder.defaultHeader("Authorization", "Bearer " + token.trim());
        }
        this.githubApiClient = apiBuilder.build();

        this.httpClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(reactorClient))
                .defaultHeader("User-Agent", "JVuln-Platform/1.0")
                .filter(RequestLogContext.webRequestFilter())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }

    public EnrichmentResult enrich(String cveId, String sourceRepo, String fixedVersion,
                                   List<CveIntelligence.Article> articles,
                                   Collection<String> existingFixCommits) {
        LinkedHashSet<String> fixCommits = new LinkedHashSet<String>();
        if (existingFixCommits != null) {
            for (String commit : existingFixCommits) {
                String normalized = normalizeUrl(commit);
                if (!normalized.isEmpty()) {
                    fixCommits.add(normalized);
                }
            }
        }

        LinkedHashMap<String, CveIntelligence.ReferenceFinding> findings =
                new LinkedHashMap<String, CveIntelligence.ReferenceFinding>();
        String resolvedFixedVersion = fixedVersion == null ? "" : fixedVersion;
        Set<String> fetched = new LinkedHashSet<String>();
        Set<String> visited = new LinkedHashSet<String>();

        if (articles != null) {
            for (CveIntelligence.Article article : articles) {
                if (article == null || article.getUrl() == null || article.getUrl().trim().isEmpty()) {
                    continue;
                }
                resolvedFixedVersion = processUrl(cveId, article.getUrl(), article.getUrl(), article.getSource(),
                        sourceRepo, resolvedFixedVersion, fixCommits, findings, fetched, visited, 0);
            }
        }

        return new EnrichmentResult(sourceRepo, resolvedFixedVersion,
                new ArrayList<String>(fixCommits),
                new ArrayList<CveIntelligence.ReferenceFinding>(findings.values()));
    }

    private String processUrl(String cveId, String url, String discoveredFrom, String source,
                              String sourceRepo, String fixedVersion,
                              Set<String> fixCommits,
                              Map<String, CveIntelligence.ReferenceFinding> findings,
                              Set<String> fetched, Set<String> visited, int depth) {
        String normalized = normalizeUrl(url);
        if (normalized.isEmpty()) {
            return fixedVersion;
        }

        String visitKey = depth + "|" + normalized;
        if (!visited.add(visitKey)) {
            return fixedVersion;
        }

        String kind = classify(normalized);
        addFinding(findings, kind, normalized, discoveredFrom, source,
                depth == 0 ? "high" : "medium");

        if ("commit".equals(kind)) {
            fixCommits.add(normalized);
            return fixedVersion;
        }
        if ("release_tag".equals(kind)) {
            String candidateVersion = extractVersionFromTag(normalized);
            if (!candidateVersion.isEmpty() && (fixedVersion == null || fixedVersion.isEmpty())) {
                fixedVersion = candidateVersion;
            }
            return fixedVersion;
        }
        if ("compare".equals(kind)) {
            if (normalized.contains("github.com/")) {
                expandGitHubCompare(cveId, normalized, discoveredFrom, source, fixCommits, findings);
            }
            return fixedVersion;
        }
        if (depth >= 1 || fetched.size() >= MAX_FETCHED_REFERENCES) {
            return fixedVersion;
        }

        String fetchKey = stripFragment(normalized);
        if (!fetched.add(fetchKey)) {
            return fixedVersion;
        }

        try {
            if ("issue".equals(kind) || "pull_request".equals(kind)) {
                if (normalized.contains("github.com/")) {
                    ReferenceContent content = fetchGitHubConversation(fetchKey);
                    fixedVersion = processExtractedUrls(cveId, content.getExtractedUrls(), normalized, source,
                            sourceRepo, fixedVersion, fixCommits, findings, fetched, visited, depth + 1);
                } else if (normalized.contains("gitee.com/")) {
                    ReferenceContent content = fetchHtmlContent(fetchKey);
                    fixedVersion = processExtractedUrls(cveId, content.getExtractedUrls(), normalized, source,
                            sourceRepo, fixedVersion, fixCommits, findings, fetched, visited, depth + 1);
                }
            }
        } catch (Exception e) {
            log.debug("Reference enrichment skipped {} due to {}", normalized, e.getMessage());
        }
        return fixedVersion;
    }

    private String processExtractedUrls(String cveId, List<String> urls, String discoveredFrom, String source,
                                        String sourceRepo, String fixedVersion, Set<String> fixCommits,
                                        Map<String, CveIntelligence.ReferenceFinding> findings,
                                        Set<String> fetched, Set<String> visited, int depth) {
        if (urls == null) {
            return fixedVersion;
        }
        for (String extracted : urls) {
            fixedVersion = processUrl(cveId, extracted, discoveredFrom, source, sourceRepo, fixedVersion,
                    fixCommits, findings, fetched, visited, depth);
        }
        return fixedVersion;
    }

    private void expandGitHubCompare(String cveId, String compareUrl, String discoveredFrom, String source,
                                     Set<String> fixCommits,
                                     Map<String, CveIntelligence.ReferenceFinding> findings) {
        Matcher matcher = GITHUB_COMPARE.matcher(compareUrl);
        if (!matcher.find()) {
            return;
        }
        String owner = matcher.group(1);
        String repo = matcher.group(2);
        String range = matcher.group(3);

        try {
            String raw = githubApiClient.get()
                    .uri("/repos/" + owner + "/" + repo + "/compare/" + range)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(REQUEST_TIMEOUT.plusSeconds(5));
            if (raw == null || raw.trim().isEmpty()) {
                return;
            }
            JsonNode root = mapper.readTree(raw);
            JsonNode commits = root.path("commits");
            if (!commits.isArray()) {
                return;
            }
            for (JsonNode commit : commits) {
                String sha = commit.path("sha").asText("");
                if (sha.isEmpty()) {
                    continue;
                }
                String url = "https://github.com/" + owner + "/" + repo + "/commit/" + sha;
                fixCommits.add(url);
                addFinding(findings, "commit", url, discoveredFrom, source, "high");
            }
        } catch (Exception e) {
            log.debug("Compare expansion failed for {} ({}): {}", cveId, compareUrl, e.getMessage());
        }
    }

    private ReferenceContent fetchGitHubConversation(String issueUrl) throws Exception {
        Matcher matcher = GITHUB_ISSUE.matcher(issueUrl);
        if (!matcher.find()) {
            return ReferenceContent.empty();
        }
        String owner = matcher.group(1);
        String repo = matcher.group(2);
        String number = matcher.group(4);

        List<String> urls = new ArrayList<String>();

        String issueRaw = githubApiClient.get()
                .uri("/repos/" + owner + "/" + repo + "/issues/" + number)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .block(REQUEST_TIMEOUT.plusSeconds(5));
        if (issueRaw != null && !issueRaw.trim().isEmpty()) {
            JsonNode issue = mapper.readTree(issueRaw);
            urls.addAll(extractUrls(issue.path("title").asText("")));
            urls.addAll(extractUrls(issue.path("body").asText("")));
            String commentsUrl = issue.path("comments_url").asText("");
            if (!commentsUrl.isEmpty()) {
                String commentsRaw = httpClient.get()
                        .uri(commentsUrl)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(REQUEST_TIMEOUT.plusSeconds(5));
                if (commentsRaw != null && !commentsRaw.trim().isEmpty()) {
                    JsonNode comments = mapper.readTree(commentsRaw);
                    if (comments.isArray()) {
                        for (JsonNode comment : comments) {
                            urls.addAll(extractUrls(comment.path("body").asText("")));
                        }
                    }
                }
            }
        }
        return new ReferenceContent(urls);
    }

    private ReferenceContent fetchHtmlContent(String url) {
        List<String> urls = new ArrayList<String>();
        try {
            String html = httpClient.get()
                    .uri(url)
                    .accept(MediaType.TEXT_HTML)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(REQUEST_TIMEOUT.plusSeconds(5));
            if (html == null || html.trim().isEmpty()) {
                return ReferenceContent.empty();
            }
            Document doc = Jsoup.parse(html, url);
            for (Element element : doc.select("a[href]")) {
                String href = element.absUrl("href");
                if (href != null && !href.trim().isEmpty()) {
                    urls.add(href.trim());
                }
            }
            urls.addAll(extractUrls(doc.text()));
        } catch (Exception e) {
            log.debug("HTML reference fetch failed for {}: {}", url, e.getMessage());
        }
        return new ReferenceContent(urls);
    }

    private List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<String>();
        if (text == null || text.trim().isEmpty()) {
            return urls;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String matched = normalizeUrl(matcher.group());
            if (!matched.isEmpty()) {
                urls.add(matched);
            }
        }
        return urls;
    }

    private void addFinding(Map<String, CveIntelligence.ReferenceFinding> findings, String kind, String url,
                            String discoveredFrom, String source, String confidence) {
        if (url == null || url.isEmpty()) {
            return;
        }
        String key = kind + "|" + url;
        if (!findings.containsKey(key)) {
            findings.put(key, new CveIntelligence.ReferenceFinding(
                    kind, url, discoveredFrom, source == null ? "" : source,
                    confidence == null ? "" : confidence));
        }
    }

    private String classify(String url) {
        if (GITHUB_COMMIT.matcher(url).find() || GITEE_COMMIT.matcher(url).find()) {
            return "commit";
        }
        if (GITHUB_COMPARE.matcher(url).find() || GITEE_COMPARE.matcher(url).find()) {
            return "compare";
        }
        if (GITHUB_RELEASE.matcher(url).find() || GITEE_RELEASE.matcher(url).find()) {
            return "release_tag";
        }
        Matcher githubIssue = GITHUB_ISSUE.matcher(url);
        if (githubIssue.find()) {
            return "pull".equalsIgnoreCase(githubIssue.group(3)) ? "pull_request" : "issue";
        }
        if (GITEE_ISSUE.matcher(url).find()) {
            return "issue";
        }
        if (GITHUB_ADVISORY.matcher(url).find()) {
            return "advisory";
        }
        if (url.contains("github.com/") || url.contains("gitee.com/")) {
            return "repo";
        }
        return "other";
    }

    private String extractVersionFromTag(String url) {
        Matcher matcher = GITHUB_RELEASE.matcher(url);
        if (!matcher.find()) {
            matcher = GITEE_RELEASE.matcher(url);
        }
        if (!matcher.find()) {
            return "";
        }
        String tag = matcher.group(3);
        String version = tag.replaceFirst("^(?:version[-_]?|release[-_]?|v)", "");
        return version.matches("\\d+\\..*") ? version : "";
    }

    private String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String normalized = url.trim();
        while (normalized.endsWith(".") || normalized.endsWith(",") || normalized.endsWith(")") || normalized.endsWith("]")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String stripFragment(String url) {
        int idx = url.indexOf('#');
        return idx >= 0 ? url.substring(0, idx) : url;
    }

    /**
     * 实现 DisposableBean 接口，确保 Bean 销毁时释放 WebClient 资源
     */
    @Override
    public void destroy() throws Exception {
        log.info("Disposing ReferenceEnricher resources");
        // WebClient 底层的 HttpClient 连接池会自动释放
        // 这里主要是提供一个显式的生命周期钩子，便于监控和调试
    }

    public static class EnrichmentResult {
        private final String sourceRepo;
        private final String fixedVersion;
        private final List<String> fixCommits;
        private final List<CveIntelligence.ReferenceFinding> referenceFindings;

        public EnrichmentResult(String sourceRepo, String fixedVersion,
                                List<String> fixCommits,
                                List<CveIntelligence.ReferenceFinding> referenceFindings) {
            this.sourceRepo = sourceRepo == null ? "" : sourceRepo;
            this.fixedVersion = fixedVersion == null ? "" : fixedVersion;
            this.fixCommits = fixCommits;
            this.referenceFindings = referenceFindings;
        }

        public String getSourceRepo() { return sourceRepo; }
        public String getFixedVersion() { return fixedVersion; }
        public List<String> getFixCommits() { return fixCommits; }
        public List<CveIntelligence.ReferenceFinding> getReferenceFindings() { return referenceFindings; }
    }

    private static class ReferenceContent {
        private final List<String> extractedUrls;

        ReferenceContent(List<String> extractedUrls) {
            this.extractedUrls = extractedUrls;
        }

        static ReferenceContent empty() {
            return new ReferenceContent(new ArrayList<String>());
        }

        List<String> getExtractedUrls() { return extractedUrls; }
    }
}

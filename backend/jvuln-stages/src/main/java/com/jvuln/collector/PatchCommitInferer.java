package com.jvuln.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.util.RequestLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PatchCommitInferer {

    private static final Logger log = LoggerFactory.getLogger(PatchCommitInferer.class);
    private static final Pattern TAG_PAT = Pattern.compile(
            "github\\.com/([^/]+)/([^/]+?)(?:\\.git)?$");
    private static final Pattern VERSION_PAT = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+).*");

    private final LlmClient llmClient;
    private final PromptRegistry promptRegistry;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PatchCommitInferer(LlmClient llmClient, PromptRegistry promptRegistry,
                               @Value("${jvuln.github.token:}") String token) {
        this.llmClient = llmClient;
        this.promptRegistry = promptRegistry;
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(30));
        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://api.github.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .filter(RequestLogContext.webRequestFilter())
                .defaultHeader("User-Agent", "JVuln-Platform/1.0");
        if (token != null && !token.trim().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.webClient = builder.build();
    }

    /**
     * Attempt to infer patch commit(s) from the full commit log between release tags.
     *
     * @return InferenceResult with commit URLs (possibly empty) and the chosen major's fixed version
     */
    public InferenceResult infer(String cveId, String description, String sourceRepo,
                                  List<String> fixedVersions) {
        if (sourceRepo == null || !sourceRepo.contains("github.com") || fixedVersions == null || fixedVersions.isEmpty()) {
            return InferenceResult.empty();
        }
        Matcher repoM = TAG_PAT.matcher(sourceRepo);
        if (!repoM.find()) return InferenceResult.empty();
        String owner = repoM.group(1);
        String repo = repoM.group(2);

        // Group fixedVersions by major, order low→high
        List<String> majorsByMajor = groupByMajorAscending(fixedVersions);

        for (String majorFix : majorsByMajor) {
            log.info("PatchCommitInferer: trying major fix version {} for {}", majorFix, cveId);
            try {
                InferenceResult result = tryMajor(owner, repo, majorFix, cveId, description);
                if (result.hasResult()) {
                    return result;
                }
                log.info("PatchCommitInferer: no patch found for major {}", majorFix);
            } catch (Exception e) {
                log.warn("PatchCommitInferer: major {} failed: {}", majorFix, e.getMessage());
            }
        }
        return InferenceResult.empty();
    }

    private InferenceResult tryMajor(String owner, String repo, String fixedVersion,
                                      String cveId, String description) throws Exception {
        // Resolve release tags for this version
        String tagsJson = webClient.get()
                .uri("/repos/" + owner + "/" + repo + "/tags?per_page=100")
                .header("Accept", "application/vnd.github+json")
                .retrieve().bodyToMono(String.class).block();
        if (tagsJson == null) return InferenceResult.empty();

        JsonNode tags = mapper.readTree(tagsJson);
        if (!tags.isArray()) return InferenceResult.empty();

        // Find the tag matching this version (try exact match, then prefix/suffix variations)
        String fixedTag = findMatchingTag(tags, fixedVersion);
        if (fixedTag == null) {
            log.warn("PatchCommitInferer: no tag found matching version {}", fixedVersion);
            return InferenceResult.empty();
        }

        // Find previous tag
        String prevTag = null;
        for (int i = 0; i < tags.size(); i++) {
            if (fixedTag.equals(tags.path(i).path("name").asText(""))) {
                if (i + 1 < tags.size()) prevTag = tags.path(i + 1).path("name").asText(null);
                break;
            }
        }
        if (prevTag == null) {
            log.warn("PatchCommitInferer: no previous tag before {}", fixedTag);
            return InferenceResult.empty();
        }

        // Fetch compare log
        String compareJson = webClient.get()
                .uri("/repos/" + owner + "/" + repo + "/compare/" + prevTag + "..." + fixedTag)
                .header("Accept", "application/vnd.github+json")
                .retrieve().bodyToMono(String.class).block();
        if (compareJson == null) return InferenceResult.empty();

        JsonNode compare = mapper.readTree(compareJson);
        JsonNode commits = compare.path("commits");
        if (!commits.isArray() || commits.isEmpty()) return InferenceResult.empty();

        // Build commit log text: "SHA | message" and collect the set of real SHAs
        StringBuilder commitLog = new StringBuilder();
        Set<String> knownShas = new LinkedHashSet<>();
        for (JsonNode c : commits) {
            String sha = c.path("sha").asText("");
            if (!sha.isEmpty()) knownShas.add(sha);
            String msg = c.path("commit").path("message").asText("");
            String firstLine = msg.split("\n")[0];
            commitLog.append(sha).append(" | ").append(firstLine).append("\n");
        }

        // Call LLM
        String taskPrompt = promptRegistry.getPrompt("current/patch-commit-inference");
        String userContent = "CVE: " + cveId + "\n"
                + "Description: " + description + "\n"
                + "Fixed version: " + fixedVersion + "\n"
                + "Commits between " + prevTag + " and " + fixedTag + ":\n"
                + commitLog.toString();

        LlmResponse response = llmClient.chat(LlmRequest.reasoning(
                LlmPromptStage.INTELLIGENCE, taskPrompt, userContent));
        if (response == null || response.getContent() == null) return InferenceResult.empty();

        // Parse response
        JsonNode result = mapper.readTree(extractJsonObject(response.getContent()));
        JsonNode commitsNode = result.path("commits");
        List<String> commitUrls = matchReturnedShas(commitsNode, knownShas, owner, repo);

        return commitUrls.isEmpty() ? InferenceResult.empty()
                : new InferenceResult(commitUrls, fixedVersion);
    }

    /**
     * Resolve LLM-returned SHAs to canonical commit URLs, accepting only values that
     * correspond to a real commit present in the fetched compare log. The LLM may return
     * a short SHA or a full one; either is matched against the known full SHAs and the URL
     * is always built from the canonical full SHA.
     */
    List<String> matchReturnedShas(JsonNode commitsNode, Set<String> knownShas, String owner, String repo) {
        Set<String> urls = new LinkedHashSet<>();
        if (commitsNode == null || !commitsNode.isArray()) {
            return new ArrayList<>(urls);
        }
        for (JsonNode shaNode : commitsNode) {
            String sha = shaNode.asText("");
            if (sha.length() < 7) continue;
            String fullSha = null;
            for (String known : knownShas) {
                if (known.equals(sha) || known.startsWith(sha) || sha.startsWith(known)) {
                    fullSha = known;
                    break;
                }
            }
            if (fullSha == null) {
                log.warn("PatchCommitInferer: LLM returned SHA {} not present in compare log — skipping", sha);
                continue;
            }
            urls.add("https://github.com/" + owner + "/" + repo + "/commit/" + fullSha);
        }
        return new ArrayList<>(urls);
    }

    private String findMatchingTag(JsonNode tags, String version) {
        // Try exact match first
        for (JsonNode tag : tags) {
            String name = tag.path("name").asText("");
            if (name.equals(version)) return name;
        }
        // Try common patterns: v{version}, version-{version}, release-{version}
        for (String prefix : new String[]{"v", "version-", "release-", "V_"}) {
            String candidate = prefix + version;
            for (JsonNode tag : tags) {
                if (tag.path("name").asText("").equals(candidate)) return candidate;
            }
        }
        return null;
    }

    List<String> groupByMajorAscending(List<String> fixedVersions) {
        Map<Integer, String> majorMap = new TreeMap<>();
        for (String v : fixedVersions) {
            Matcher m = VERSION_PAT.matcher(v);
            if (m.find()) {
                int major = Integer.parseInt(m.group(1));
                // For same major, prefer the entry already in the map (first encountered)
                majorMap.putIfAbsent(major, v);
            }
        }
        return new ArrayList<>(majorMap.values());
    }

    private String extractJsonObject(String raw) {
        // Same logic as AiPatchSearchStrategy.extractJsonObject
        String cleaned = raw == null ? "" : raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z0-9_-]*\\n?", "")
                    .replaceAll("```\\s*$", "").trim();
        }
        int start = cleaned.indexOf('{');
        if (start < 0) return cleaned;
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (ch == '\\') { i++; continue; }
            if (ch == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (ch == '{') depth++;
            else if (ch == '}') { depth--; if (depth == 0) return cleaned.substring(start, i + 1); }
        }
        return cleaned;
    }

    public static class InferenceResult {
        private final List<String> commitUrls;
        private final String chosenVersion;

        public InferenceResult(List<String> commitUrls, String chosenVersion) {
            this.commitUrls = commitUrls != null ? Collections.unmodifiableList(commitUrls) : Collections.emptyList();
            this.chosenVersion = chosenVersion;
        }

        public static InferenceResult empty() {
            return new InferenceResult(Collections.emptyList(), null);
        }

        public boolean hasResult() { return !commitUrls.isEmpty(); }
        public List<String> getCommitUrls() { return commitUrls; }
        public String getChosenVersion() { return chosenVersion; }
    }
}

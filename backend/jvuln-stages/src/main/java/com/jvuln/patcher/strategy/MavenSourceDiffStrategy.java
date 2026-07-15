package com.jvuln.patcher.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.jvuln.util.RequestLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

import org.springframework.web.util.UriComponentsBuilder;

/**
 * Downloads Maven source JARs for fixedVersion and the previous version,
 * then generates a unified diff of changed Java files.
 * Completely independent of GitHub — works as long as the artifact is on Maven Central.
 */
@Component
public class MavenSourceDiffStrategy implements LocateStrategy {

    private static final Logger log = LoggerFactory.getLogger(MavenSourceDiffStrategy.class);
    private static final String CENTRAL = "https://repo1.maven.org/maven2";
    private static final String MAVEN_SEARCH = "https://search.maven.org/solrsearch/select";

    // For umbrella artifacts (e.g. org.apache.tomcat:tomcat), try these sub-artifacts
    private static final Map<String, List<String>> ARTIFACT_EXPANSIONS = new LinkedHashMap<>();
    static {
        ARTIFACT_EXPANSIONS.put("org.apache.tomcat:tomcat",
                Arrays.asList("org.apache.tomcat.embed:tomcat-embed-core",
                              "org.apache.tomcat:tomcat-catalina",
                              "org.apache.tomcat:tomcat-coyote"));
        ARTIFACT_EXPANSIONS.put("org.springframework.boot:spring-boot",
                Arrays.asList("org.springframework:spring-webmvc",
                              "org.springframework:spring-web",
                              "org.springframework:spring-core"));
        ARTIFACT_EXPANSIONS.put("com.baomidou:mybatis-plus",
                Arrays.asList("com.baomidou:mybatis-plus-extension",
                              "com.baomidou:mybatis-plus-core"));
    }

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public MavenSourceDiffStrategy() {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(60));
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(30 * 1024 * 1024))
                .filter(RequestLogContext.webRequestFilter())
                .defaultHeader("User-Agent", "JVuln-Platform/1.0")
                .build();
    }

    @Override
    public String name() { return "maven-source-diff"; }

    @Override
    public int priority() { return 3; }

    @Override
    public Optional<PatchResult> locate(String cveId, String sourceRepo, List<String> knownCommits)
            throws Exception {

        // Need at least groupId:artifactId and a fixedVersion from sourceRepo hint
        // sourceRepo is like "https://github.com/apache/tomcat"
        // We rely on the caller also passing any mvn coordinate in sourceRepo or we try to infer
        // This strategy is triggered when the Stage 1 data is available via ctx; here we only
        // have sourceRepo and knownCommits. We encode the Maven coordinate in sourceRepo as a fallback.
        // Real coordination: PatchAnalysisStage passes intel data through dedicated fields added below.

        return Optional.empty(); // Placeholder; actual logic is in overloaded method below
    }

    /**
     * Called by PatchAnalysisStage when sourceRepo and artifact coordinates are available.
     */
    public Optional<PatchResult> locateByArtifact(String cveId, String groupId, String artifactId,
                                                    String fixedVersion) throws Exception {
        if ((groupId == null || groupId.isEmpty()) && artifactId != null && !artifactId.isEmpty()) {
            String resolvedGroup = inferGroupId(artifactId);
            if (resolvedGroup != null && !resolvedGroup.isEmpty()) {
                log.info("MavenSourceDiff: inferred groupId={} for artifactId={}", resolvedGroup, artifactId);
                groupId = resolvedGroup;
            }
        }
        if (groupId == null || groupId.isEmpty() || artifactId == null || artifactId.isEmpty()) {
            log.info("MavenSourceDiff: missing artifact coordinates groupId={} artifactId={}", groupId, artifactId);
            return Optional.empty();
        }
        if (fixedVersion == null || fixedVersion.isEmpty()) {
            log.info("MavenSourceDiff: no fixedVersion, skipping");
            return Optional.empty();
        }

        String prevVersion = inferPrevVersion(groupId, artifactId, fixedVersion);
        if (prevVersion == null) {
            log.warn("MavenSourceDiff: cannot determine previous version for {}", fixedVersion);
            return Optional.empty();
        }
        log.info("MavenSourceDiff: comparing {}:{} {} → {}", groupId, artifactId, prevVersion, fixedVersion);

        String gav = groupId + ":" + artifactId;
        List<String> candidates = new ArrayList<>();
        candidates.add(gav);
        if (ARTIFACT_EXPANSIONS.containsKey(gav)) candidates.addAll(ARTIFACT_EXPANSIONS.get(gav));

        for (String candidate : candidates) {
            String[] parts = candidate.split(":");
            if (parts.length != 2) continue;
            String g = parts[0], a = parts[1];
            try {
                Optional<PatchResult> result = diffArtifact(cveId, g, a, prevVersion, fixedVersion);
                if (result.isPresent()) return result;
            } catch (Exception e) {
                log.warn("MavenSourceDiff: artifact {} failed: {}", candidate, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private Optional<PatchResult> diffArtifact(String cveId, String groupId, String artifactId,
                                                 String prevVersion, String fixedVersion) throws Exception {
        String prevUrl  = sourcesUrl(groupId, artifactId, prevVersion);
        String fixedUrl = sourcesUrl(groupId, artifactId, fixedVersion);

        if (!urlExists(prevUrl) || !urlExists(fixedUrl)) {
            log.debug("MavenSourceDiff: sources not found for {}/{}", prevUrl, fixedUrl);
            return Optional.empty();
        }

        Path tmpDir = Files.createTempDirectory("jvuln-maven-diff-");
        try {
            Path prevDir  = tmpDir.resolve("prev");
            Path fixedDir = tmpDir.resolve("fixed");
            Files.createDirectories(prevDir);
            Files.createDirectories(fixedDir);

            extractSources(prevUrl,  prevDir);
            extractSources(fixedUrl, fixedDir);

            String diff = generateUnifiedDiff(prevDir, fixedDir, groupId, artifactId, prevVersion, fixedVersion);
            if (diff == null || !diff.contains("diff --git")) {
                log.info("MavenSourceDiff: no Java file differences found for {}", artifactId);
                return Optional.empty();
            }

            String fakeCommitUrl = "maven-central/" + groupId + "/" + artifactId + "/" + fixedVersion;
            String locateNote = "对比 " + prevVersion + " 和 " + fixedVersion + " 的代码差异";
            log.info("MavenSourceDiff: generated diff for {}:{}, size={}c", groupId, artifactId, diff.length());
            return Optional.of(new PatchResult(fakeCommitUrl, fixedVersion, locateNote, diff));
        } finally {
            deleteDir(tmpDir.toFile());
        }
    }

    private String sourcesUrl(String groupId, String artifactId, String version) {
        String groupPath = groupId.replace('.', '/');
        return CENTRAL + "/" + groupPath + "/" + artifactId + "/" + version
                + "/" + artifactId + "-" + version + "-sources.jar";
    }

    private boolean urlExists(String url) {
        try {
            String head = webClient.head().uri(url).retrieve().toBodilessEntity().block()
                    .getStatusCode().is2xxSuccessful() ? "ok" : "fail";
            return "ok".equals(head);
        } catch (Exception e) {
            return false;
        }
    }

    private void extractSources(String jarUrl, Path destDir) throws Exception {
        File tmp = File.createTempFile("jvuln-src-", ".jar");
        try {
            // Download JAR
            try (OutputStream os = new FileOutputStream(tmp)) {
                byte[] bytes = webClient.get().uri(jarUrl)
                        .retrieve().bodyToMono(byte[].class).block();
                if (bytes != null) os.write(bytes);
            }
            // Extract only .java files
            try (ZipFile zip = new ZipFile(tmp)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".java")) continue;
                    Path dest = destDir.resolve(entry.getName()).normalize();
                    if (!dest.startsWith(destDir)) continue; // zip slip guard
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = zip.getInputStream(entry);
                         OutputStream out = Files.newOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            }
        } finally {
            tmp.delete();
        }
    }

    /**
     * Run `diff -rU3` on the two directories, convert to git-style unified diff.
     */
    private String generateUnifiedDiff(Path prevDir, Path fixedDir,
                                        String groupId, String artifactId,
                                        String prevVersion, String fixedVersion) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "diff", "-rU3", "--strip-trailing-cr",
                "--exclude=*.class", "--exclude=*.properties",
                prevDir.toString(), fixedDir.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        byte[] output = readAllBytes(proc.getInputStream());
        proc.waitFor();
        String raw = new String(output, java.nio.charset.StandardCharsets.UTF_8);

        // Convert to git-style unified diff (diff --git a/... b/...)
        return convertToGitDiff(raw, prevDir, fixedDir, prevVersion, fixedVersion);
    }

    private String convertToGitDiff(String diff, Path prevDir, Path fixedDir,
                                     String prevVersion, String fixedVersion) {
        if (diff.trim().isEmpty()) return diff;
        Set<String> emittedPaths = new LinkedHashSet<>();
        // Split on each per-file block header ("diff -rU3 fileA fileB")
        // Note: diff.split("(?m)(?=^diff )") needs the (?m) flag inside the regex
        String[] sections = diff.split("(?m)(?=^diff )");
        Pattern fileHeader = Pattern.compile(
                "^---\\s+(\\S+).*\\n\\+\\+\\+\\s+(\\S+)", Pattern.MULTILINE);
        StringBuilder result = new StringBuilder();
        for (String section : sections) {
            if (section.trim().isEmpty()) continue;
            Matcher m = fileHeader.matcher(section);
            if (!m.find()) continue;
            // Strip absolute temp-dir prefixes; timestamps are already excluded by \\S+
            String prevPath = m.group(1).replace(prevDir.toString(), "").replaceAll("^/", "");
            String fixedPath = m.group(2).replace(fixedDir.toString(), "").replaceAll("^/", "");
            if (!prevPath.endsWith(".java") && !fixedPath.endsWith(".java")) continue;
            emittedPaths.add(!fixedPath.isEmpty() ? fixedPath : prevPath);
            result.append("diff --git a/").append(prevPath).append(" b/").append(fixedPath).append("\n");
            result.append("--- a/").append(prevPath).append("\n");
            result.append("+++ b/").append(fixedPath).append("\n");
            // Append hunks only (everything after the --- / +++ header block)
            String body = section.substring(m.end()).trim();
            if (!body.isEmpty()) result.append(body).append("\n");
        }
        appendOnlyInEntries(result, diff, prevDir, fixedDir, emittedPaths);
        return result.toString();
    }

    private void appendOnlyInEntries(StringBuilder result, String rawDiff, Path prevDir, Path fixedDir,
                                     Set<String> emittedPaths) {
        Pattern onlyIn = Pattern.compile("(?m)^Only in (.*?):\\s*(.+)$");
        Matcher matcher = onlyIn.matcher(rawDiff);
        while (matcher.find()) {
            Path baseDir = Paths.get(matcher.group(1).trim());
            String childName = matcher.group(2).trim();
            Path candidate = baseDir.resolve(childName).normalize();
            if (candidate.startsWith(fixedDir)) {
                appendSyntheticEntries(result, candidate, fixedDir, "added", emittedPaths);
            } else if (candidate.startsWith(prevDir)) {
                appendSyntheticEntries(result, candidate, prevDir, "deleted", emittedPaths);
            }
        }
    }

    private void appendSyntheticEntries(StringBuilder result, Path candidate, Path rootDir, String changeType,
                                        Set<String> emittedPaths) {
        if (!Files.exists(candidate)) {
            return;
        }
        try {
            if (Files.isDirectory(candidate)) {
                try (Stream<Path> walk = Files.walk(candidate)) {
                    walk.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".java"))
                            .sorted()
                            .forEach(path -> appendSyntheticFileDiff(result, path, rootDir, changeType, emittedPaths));
                }
                return;
            }
            if (candidate.getFileName().toString().endsWith(".java")) {
                appendSyntheticFileDiff(result, candidate, rootDir, changeType, emittedPaths);
            }
        } catch (Exception e) {
            log.debug("MavenSourceDiff: failed to append synthetic {} entry {}: {}",
                    changeType, candidate, e.getMessage());
        }
    }

    private void appendSyntheticFileDiff(StringBuilder result, Path file, Path rootDir, String changeType,
                                         Set<String> emittedPaths) {
        try {
            String relativePath = normalizeRelativePath(rootDir.relativize(file));
            if (!emittedPaths.add(relativePath)) {
                return;
            }
            List<String> lines = Files.readAllLines(file);
            result.append("diff --git a/").append(relativePath).append(" b/").append(relativePath).append("\n");
            if ("added".equals(changeType)) {
                result.append("new file mode 100644\n");
                result.append("--- /dev/null\n");
                result.append("+++ b/").append(relativePath).append("\n");
                result.append("@@ -0,0 +1,").append(lines.size()).append(" @@\n");
                for (String line : lines) {
                    result.append("+").append(line).append("\n");
                }
            } else {
                result.append("deleted file mode 100644\n");
                result.append("--- a/").append(relativePath).append("\n");
                result.append("+++ /dev/null\n");
                result.append("@@ -1,").append(lines.size()).append(" +0,0 @@\n");
                for (String line : lines) {
                    result.append("-").append(line).append("\n");
                }
            }
        } catch (Exception e) {
            log.debug("MavenSourceDiff: failed to synthesize {} diff for {}: {}",
                    changeType, file, e.getMessage());
        }
    }

    private String normalizeRelativePath(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    /**
     * Determine the previous release version from Maven metadata.
     * Falls back to decrementing the patch number.
     */
    private String inferPrevVersion(String groupId, String artifactId, String fixedVersion) {
        if ((groupId == null || groupId.isEmpty()) && artifactId != null && !artifactId.isEmpty()) {
            groupId = inferGroupId(artifactId);
        }
        if (groupId == null || groupId.isEmpty()) {
            return decrementVersion(fixedVersion);
        }
        try {
            String groupPath = groupId.replace('.', '/');
            String metaUrl = CENTRAL + "/" + groupPath + "/" + artifactId + "/maven-metadata.xml";
            String xml = webClient.get().uri(metaUrl)
                    .retrieve().bodyToMono(String.class).block();
            if (xml != null) {
                List<String> versions = parseVersionsFromMetadata(xml);
                int idx = versions.indexOf(fixedVersion);
                if (idx > 0) return versions.get(idx - 1);
            }
        } catch (Exception e) {
            log.debug("MavenSourceDiff: metadata lookup failed: {}", e.getMessage());
        }
        // Fallback: decrement patch version
        return decrementVersion(fixedVersion);
    }

    /**
     * Given the last affected version string from advisory (e.g. "<= 5.8.19" or "< 5.8.20"),
     * returns the expected fixed version from Maven Central.
     * Returns null if it cannot be determined.
     */
    public String inferFixedVersion(String groupId, String artifactId, String affectedTo) {
        if (affectedTo == null || affectedTo.trim().isEmpty()) return null;
        String trimmed = affectedTo.trim();
        // "< X" means X is already the fixed version
        if (trimmed.startsWith("< ")) {
            return trimmed.substring(2).trim();
        }
        // "<= X" means the fix is the next version after X
        String lastAffected;
        if (trimmed.startsWith("<= ")) {
            lastAffected = trimmed.substring(3).trim();
        } else {
            // Best-effort: extract version-like token
            Matcher m = Pattern.compile("[\\d]+(?:\\.[\\d]+)+").matcher(trimmed);
            if (!m.find()) return null;
            lastAffected = m.group();
        }
        return inferNextVersion(groupId, artifactId, lastAffected);
    }

    /** Returns the version immediately after {@code lastAffected} in Maven Central metadata. */
    public String inferNextVersion(String groupId, String artifactId, String lastAffected) {
        if ((groupId == null || groupId.isEmpty()) && artifactId != null && !artifactId.isEmpty()) {
            groupId = inferGroupId(artifactId);
        }
        if (groupId == null || groupId.isEmpty()) {
            return null;
        }
        try {
            String groupPath = groupId.replace('.', '/');
            String metaUrl = CENTRAL + "/" + groupPath + "/" + artifactId + "/maven-metadata.xml";
            String xml = webClient.get().uri(metaUrl)
                    .retrieve().bodyToMono(String.class).block();
            if (xml != null) {
                List<String> versions = parseVersionsFromMetadata(xml);
                int idx = versions.indexOf(lastAffected);
                if (idx >= 0 && idx + 1 < versions.size()) {
                    return versions.get(idx + 1);
                }
            }
        } catch (Exception e) {
            log.debug("MavenSourceDiff: next-version lookup failed: {}", e.getMessage());
        }
        return null;
    }

    private List<String> parseVersionsFromMetadata(String xml) {
        List<String> versions = new ArrayList<>();
        Pattern vp = Pattern.compile("<version>([^<]+)</version>");
        Matcher m = vp.matcher(xml);
        while (m.find()) versions.add(m.group(1).trim());
        return versions;
    }

    private String inferGroupId(String artifactId) {
        try {
            URI searchUri = UriComponentsBuilder.fromHttpUrl(MAVEN_SEARCH)
                    .queryParam("q", "a:\"" + artifactId + "\"")
                    .queryParam("rows", 10)
                    .queryParam("wt", "json")
                    .build()
                    .encode()
                    .toUri();
            String json = webClient.get()
                    .uri(searchUri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (json == null || json.isEmpty()) {
                return null;
            }
            JsonNode docs = mapper.readTree(json).path("response").path("docs");
            if (!docs.isArray() || docs.isEmpty()) {
                return null;
            }
            for (JsonNode doc : docs) {
                String candidateArtifact = doc.path("a").asText("");
                if (artifactId.equals(candidateArtifact)) {
                    return doc.path("g").asText("");
                }
            }
            return docs.path(0).path("g").asText("");
        } catch (Exception e) {
            log.debug("MavenSourceDiff: groupId lookup failed for {}: {}", artifactId, e.getMessage());
            return null;
        }
    }

    private String decrementVersion(String version) {
        try {
            String[] parts = version.split("\\.");
            int last = Integer.parseInt(parts[parts.length - 1]);
            if (last <= 0) {
                // 3.5.3.0 → drop last segment → 3.5.3; but need at least major.minor
                if (parts.length > 2) {
                    String[] shorter = new String[parts.length - 1];
                    System.arraycopy(parts, 0, shorter, 0, shorter.length);
                    return String.join(".", shorter);
                }
                return null;
            }
            if (last == 1 && parts.length > 3) {
                // 3.5.3.1 → 3.5.3 (drop the patch qualifier rather than producing 3.5.3.0)
                String[] shorter = new String[parts.length - 1];
                System.arraycopy(parts, 0, shorter, 0, shorter.length);
                return String.join(".", shorter);
            }
            parts[parts.length - 1] = String.valueOf(last - 1);
            return String.join(".", parts);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readAllBytes(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
    }
}

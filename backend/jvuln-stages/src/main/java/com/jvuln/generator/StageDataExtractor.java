package com.jvuln.generator;

import static com.jvuln.generator.ArtifactGenUtils.copyField;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.pipeline.model.PipelineContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 数据提取器
 *
 * 职责：从 Stage 1-3 输出中提取和转换 ArtifactGenStage 所需的数据片段
 */
@Component
class StageDataExtractor {

    private final ObjectMapper mapper;
    private final MavenSourcePackageScanner scanner;

    StageDataExtractor(ObjectMapper mapper, MavenSourcePackageScanner scanner) {
        this.mapper = mapper;
        this.scanner = scanner;
    }

    String trimIntelligence(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        ObjectNode out = mapper.createObjectNode();
        copyField(root, out, "cveId");
        copyField(root, out, "cweId");
        copyField(root, out, "description");
        copyField(root, out, "cvss");
        copyField(root, out, "fixedVersion");
        copyField(root, out, "artifact");
        copyField(root, out, "affectedVersions");
        return mapper.writeValueAsString(out);
    }

    String extractDiff(PipelineContext ctx, Object data, int cap) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode rawDiff = root.path("rawDiff");
        if (!rawDiff.isMissingNode() && rawDiff.isTextual()) {
            String d = rawDiff.asText();
            return d.length() > cap ? d.substring(0, cap) + "\n...[truncated]" : d;
        }
        Path diffFile = ctx.getWorkspacePath().resolve("patches/fix.diff");
        if (Files.exists(diffFile)) {
            String d = new String(Files.readAllBytes(diffFile), StandardCharsets.UTF_8);
            return d.length() > cap ? d.substring(0, cap) + "\n...[truncated]" : d;
        }
        String full = mapper.writeValueAsString(data);
        return full.length() > cap ? full.substring(0, cap) + "\n...[truncated]" : full;
    }

    String extractVulnerabilityFacts(Object data) throws Exception {
        if (data == null) return "{}";
        JsonNode root = mapper.valueToTree(data);
        JsonNode facts = root.path("vulnerabilityFacts");
        return facts.isMissingNode() ? "{}" : mapper.writeValueAsString(facts);
    }

    String extractTriggerChain(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode chain = root.path("trigger_chain");
        return !chain.isMissingNode() ? mapper.writeValueAsString(chain) : "{}";
    }

    String extractRootCause(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode analysis = root.path("code_analysis");
        if (!analysis.isMissingNode()) {
            ObjectNode out = mapper.createObjectNode();
            copyField(analysis, out, "vuln_root_cause");
            copyField(analysis, out, "fix_description");
            return mapper.writeValueAsString(out);
        }
        return "{}";
    }

    String extractArtifact(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode artifact = root.path("artifact");
        if (!artifact.isMissingNode()) {
            return artifact.isTextual() ? artifact.asText() : mapper.writeValueAsString(artifact);
        }
        return "";
    }

    TraceTarget extractTraceTarget(Object intelligence, Object analysis) {
        try {
            JsonNode intel = mapper.valueToTree(intelligence);
            JsonNode artifact = intel.path("artifact");

            String groupId = null;
            String artifactId = null;
            if (artifact.isObject()) {
                groupId = artifact.path("groupId").asText(null);
                artifactId = artifact.path("artifactId").asText(null);
            }

            if (groupId == null || artifactId == null) {
                return null;
            }

            String version = resolveVulnerableVersion(intel);
            if (version == null) {
                return null;
            }

            Set<String> packages = scanner.scanPackages(groupId, artifactId, version);
            if (packages.isEmpty()) {
                return null;
            }

            List<String> methodsOfInterest = extractMethodsOfInterest(analysis);

            return new TraceTarget(groupId, artifactId, version, packages, methodsOfInterest);
        } catch (Exception e) {
            return null;
        }
    }

    String resolveVulnerableVersion(JsonNode intel) {
        JsonNode affectedVersions = intel.path("affectedVersions");
        String to = affectedVersions.path("to").asText(null);
        String from = affectedVersions.path("from").asText(null);
        String fixedVersion = intel.path("fixedVersion").asText(null);

        if (fixedVersion != null && !fixedVersion.isEmpty()) {
            // fixedVersion is the Stage 4 anchor — use it to decide the major line.
            // 'to' is the last *affected* version, so it must never equal fixedVersion
            // (a patched version is not vulnerable); when it does, fall through to 'from'.
            String fixedMajor = extractMajor(fixedVersion);
            String toMajor = extractMajor(to);
            if (toMajor != null && toMajor.equals(fixedMajor) && !to.equals(fixedVersion)) {
                return to; // same major line → use 'to'
            }
            // Different major lines or to==fixedVersion; fall through to heuristic below
        }
        // Original heuristic: prefer 'to' if it differs from fixedVersion
        if (to != null && !to.equals(fixedVersion)) {
            return to;
        }
        return from;
    }

    /** Extract "X.Y" major.minor prefix for line-matching. */
    private String extractMajor(String version) {
        if (version == null) return null;
        Matcher m = Pattern.compile("(\\d+\\.\\d+)").matcher(version);
        return m.find() ? m.group(1) : null;
    }

    List<String> extractMethodsOfInterest(Object analysis) {
        List<String> methods = new ArrayList<>();
        if (analysis == null) return methods;

        try {
            JsonNode root = mapper.valueToTree(analysis);
            JsonNode files = root.path("analyzedFiles");
            if (!files.isArray()) return methods;

            for (JsonNode fileNode : files) {
                String fileName = fileNode.path("fileName").asText("");
                if (!fileName.endsWith(".java")) continue;

                String className = deriveClassName(fileName);
                JsonNode methodNodes = fileNode.path("methods");
                if (methodNodes.isArray()) {
                    for (JsonNode m : methodNodes) {
                        String methodName = m.path("methodName").asText("");
                        if (!methodName.isEmpty() && className != null) {
                            methods.add(className + "." + methodName);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return methods;
    }

    String deriveClassName(String fileName) {
        if (fileName == null) return null;
        String normalized = fileName.replace('\\', '/');
        if (!normalized.contains("/")) return null;
        int srcIdx = normalized.indexOf("src/main/java/");
        if (srcIdx >= 0) {
            String rel = normalized.substring(srcIdx + "src/main/java/".length());
            if (rel.endsWith(".java")) {
                rel = rel.substring(0, rel.length() - ".java".length());
            }
            return rel.replace('/', '.');
        }
        return null;
    }
}

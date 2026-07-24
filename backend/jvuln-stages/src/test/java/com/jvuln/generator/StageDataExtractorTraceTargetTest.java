package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageDataExtractorTraceTargetTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StageDataExtractor extractor =
            new StageDataExtractor(mapper, new MavenSourcePackageScanner());

    @Test
    void resolveVulnerableVersionPrefersToWhenDifferentFromFixed() {
        Map<String, Object> affected = new HashMap<>();
        affected.put("to", "1.4.199");
        affected.put("from", "1.0.0");
        Map<String, Object> intel = new HashMap<>();
        intel.put("affectedVersions", affected);
        intel.put("fixedVersion", "1.4.200");

        JsonNode node = mapper.valueToTree(intel);
        assertEquals("1.4.199", extractor.resolveVulnerableVersion(node));
    }

    @Test
    void resolveVulnerableVersionFallsBackToFromWhenToEqualsFixed() {
        Map<String, Object> affected = new HashMap<>();
        affected.put("to", "1.4.200");
        affected.put("from", "1.0.0");
        Map<String, Object> intel = new HashMap<>();
        intel.put("affectedVersions", affected);
        intel.put("fixedVersion", "1.4.200");

        JsonNode node = mapper.valueToTree(intel);
        assertEquals("1.0.0", extractor.resolveVulnerableVersion(node));
    }

    @Test
    void resolveVulnerableVersionReturnsNullWhenNothingPresent() {
        JsonNode node = mapper.valueToTree(new HashMap<String, Object>());
        assertNull(extractor.resolveVulnerableVersion(node));
    }

    @Test
    void deriveClassNameConvertsSrcMainJavaPathToFqn() {
        assertEquals("com.example.Foo",
                extractor.deriveClassName("module/src/main/java/com/example/Foo.java"));
    }

    @Test
    void deriveClassNameReturnsNullWhenNoPathSeparator() {
        assertNull(extractor.deriveClassName("Foo.java"));
    }

    @Test
    void deriveClassNameReturnsNullWhenNotUnderSrcMainJava() {
        assertNull(extractor.deriveClassName("other/root/com/example/Foo.java"));
    }

    @Test
    void extractMethodsOfInterestBuildsClassDotMethodEntries() {
        Map<String, Object> methodA = new HashMap<>();
        methodA.put("methodName", "query");
        Map<String, Object> methodB = new HashMap<>();
        methodB.put("methodName", "execute");

        List<Object> methods = new ArrayList<>();
        methods.add(methodA);
        methods.add(methodB);

        Map<String, Object> file = new HashMap<>();
        file.put("fileName", "h2/src/main/java/org/h2/command/Parser.java");
        file.put("methods", methods);

        List<Object> files = new ArrayList<>();
        files.add(file);

        Map<String, Object> analysis = new HashMap<>();
        analysis.put("analyzedFiles", files);

        List<String> result = extractor.extractMethodsOfInterest(analysis);
        assertEquals(2, result.size());
        assertTrue(result.contains("org.h2.command.Parser.query"));
        assertTrue(result.contains("org.h2.command.Parser.execute"));
    }

    @Test
    void extractMethodsOfInterestSkipsNonJavaFiles() {
        Map<String, Object> file = new HashMap<>();
        file.put("fileName", "README.md");

        List<Object> files = new ArrayList<>();
        files.add(file);

        Map<String, Object> analysis = new HashMap<>();
        analysis.put("analyzedFiles", files);

        assertTrue(extractor.extractMethodsOfInterest(analysis).isEmpty());
    }

    @Test
    void extractMethodsOfInterestReturnsEmptyForNullAnalysis() {
        assertTrue(extractor.extractMethodsOfInterest(null).isEmpty());
    }

    @Test
    void extractTraceTargetReturnsNullWhenCoordinatesMissing() {
        // No "artifact" object -> groupId/artifactId null -> null, no network access.
        Map<String, Object> intel = new HashMap<>();
        intel.put("cveId", "CVE-2021-9999");

        assertNull(extractor.extractTraceTarget(intel, null));
    }
}

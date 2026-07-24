package com.jvuln.collector.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.store.model.SourceData;
import com.jvuln.store.model.SourceResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelSourceParsingTest {

    @Test
    void nvdEmptyResultIsNotFoundAndSuccessPreservesVector() throws Exception {
        NvdSource source = new NvdSource("");

        IntelSource.IntelFragment absent = source.parsePayload(
                "{\"vulnerabilities\":[]}");
        IntelSource.IntelFragment present = source.parsePayload("{"
                + "\"vulnerabilities\":[{\"cve\":{"
                + "\"descriptions\":[{\"lang\":\"en\",\"value\":\"nvd description\"}],"
                + "\"metrics\":{\"cvssMetricV31\":[{\"cvssData\":{"
                + "\"baseScore\":9.8,\"baseSeverity\":\"CRITICAL\","
                + "\"vectorString\":\"CVSS:3.1/AV:N\"}}]},"
                + "\"references\":[]}}]}");

        assertEquals(SourceResult.Status.NOT_FOUND, absent.getStatus());
        assertEquals(SourceResult.Status.SUCCESS, present.getStatus());
        assertEquals("CVSS:3.1/AV:N", present.getCvssVector());
        assertEquals("nvd description", present.getDescription());
    }

    @Test
    void nvdReferenceSourceIsNotUsedAsDisplayTitle() throws Exception {
        NvdSource source = new NvdSource("");

        IntelSource.IntelFragment result = source.parsePayload("{"
                + "\"vulnerabilities\":[{\"cve\":{"
                + "\"descriptions\":[{\"lang\":\"en\",\"value\":\"description\"}],"
                + "\"references\":[{"
                + "\"url\":\"https://github.com/jmurty/java-xmlbuilder/issues/6\","
                + "\"source\":\"cna@vuldb.com\"}]}}]}");

        assertEquals(1, result.getArticles().size());
        assertEquals("", result.getArticles().get(0).getTitle());
        assertEquals("NVD", result.getArticles().get(0).getSource());
        assertEquals(Collections.singletonList("NVD"),
                result.getArticles().get(0).getDiscoveredFrom());
    }

    @Test
    void ghsaEmptyArrayIsNotFound() throws Exception {
        GhsaSource source = new GhsaSource("");

        IntelSource.IntelFragment result = source.parsePayload("[]");

        assertEquals(SourceResult.Status.NOT_FOUND, result.getStatus());
        assertEquals("[]", result.getRawPayload());
    }

    @Test
    void osvEmptyObjectIsNotFound() throws Exception {
        OsvSource source = new OsvSource();

        IntelSource.IntelFragment result = source.parsePayload("{}");

        assertEquals(SourceResult.Status.NOT_FOUND, result.getStatus());
        assertEquals("{}", result.getRawPayload());
    }

    @Test
    void deserializesOldJsonWithoutFixedVersions() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Old JSON (no fixedVersions field)
        String old = "{\"cweId\":\"\",\"cvssScore\":\"\",\"cvssVector\":\"\",\"cvssSeverity\":\"\","
                + "\"artifactGroupId\":\"\",\"artifactId\":\"\",\"affectedFrom\":\"\",\"affectedTo\":\"\","
                + "\"fixedVersion\":\"2.0.206\",\"sourceRepo\":\"\",\"fixCommits\":[],\"references\":[]}";
        SourceData data = mapper.readValue(old, SourceData.class);
        assertEquals("2.0.206", data.getFixedVersion());
        assertTrue(data.getFixedVersions().isEmpty(), "missing field defaults to empty list");
    }

    @Test
    void osvCollectsMultipleFixedVersions() throws Exception {
        OsvSource source = new OsvSource();
        String payload = "{\"id\":\"CVE-2021-42392\",\"summary\":\"test\","
                + "\"affected\":[{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"com.h2database:h2\"},"
                + "\"ranges\":[{\"type\":\"ECOSYSTEM\",\"events\":["
                + "{\"introduced\":\"0\"},{\"fixed\":\"1.4.200\"},{\"fixed\":\"2.0.206\"}]}]}],"
                + "\"references\":[]}";
        IntelSource.IntelFragment result = source.parsePayload(payload);
        List<String> fvs = result.getParsedData().getFixedVersions();
        assertEquals(2, fvs.size());
        assertTrue(fvs.contains("1.4.200"));
        assertTrue(fvs.contains("2.0.206"));
        // Singular fixedVersion = earliest (lowest major wins as per spec)
        assertEquals("1.4.200", result.getParsedData().getFixedVersion());
    }
}

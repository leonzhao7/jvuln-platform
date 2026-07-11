package com.jvuln.collector.source;

import com.jvuln.store.model.SourceResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

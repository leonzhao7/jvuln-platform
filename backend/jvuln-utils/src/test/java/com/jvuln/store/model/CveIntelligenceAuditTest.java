package com.jvuln.store.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CveIntelligenceAuditTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void legacyConstructorKeepsExistingContractAndCompatibleAuditDefaults() {
        CveIntelligence intelligence = legacyIntelligence("original description");

        assertEquals("original description", intelligence.getDescription());
        assertTrue(intelligence.getSourceResults().isEmpty());
        assertTrue(intelligence.getEvidenceResults().isEmpty());
        assertEquals(DescriptionAdjudication.Status.NOT_RUN,
                intelligence.getDescriptionAdjudication().getStatus());

        CveIntelligence.Article article = intelligence.getArticles().get(0);
        assertEquals(Collections.singletonList("NVD"), article.getDiscoveredFrom());
        assertEquals("analysis", article.getCategory());
        assertEquals("", article.getClassificationMethod());
        assertEquals(BigDecimal.ZERO, article.getClassificationConfidence());
    }

    @Test
    void fullAuditModelRoundTripsThroughJackson() throws Exception {
        SourceData data = new SourceData(
                "CWE-502", "9.8", "CVSS:3.1/AV:N", "CRITICAL",
                "org.example", "demo", "1.0.0", "< 2.0.0", "2.0.0",
                "https://github.com/example/demo",
                Collections.singletonList("https://github.com/example/demo/commit/abc"),
                Collections.singletonList(new CveIntelligence.Article(
                        "Vendor advisory", "https://example.org/advisory", "NVD", "")));
        SourceResult source = new SourceResult(
                SourceResult.Source.NVD, SourceResult.Status.SUCCESS, 12L, "", "",
                "provider description", data, "{\"source\":\"nvd\"}");
        CveIntelligence.Article classified = new CveIntelligence.Article(
                "Vendor advisory", "https://example.org/advisory", "NVD", "summary",
                "advisory", Arrays.asList("NVD", "OSV"), "RULE",
                "host.vendor-advisory", new BigDecimal("0.99"));
        EvidenceResult evidence = new EvidenceResult(
                "E-SRC-NVD-DESC", EvidenceResult.Kind.SOURCE_DESCRIPTION, "NVD",
                "NVD description", "", EvidenceResult.FetchStatus.INLINE,
                "provider description", "");
        DescriptionAdjudication adjudication = DescriptionAdjudication.resolved(
                true, "final description", "source conflict resolved",
                Collections.singletonList("NVD and OSV disagreed on the component"),
                Collections.singletonList("E-SRC-NVD-DESC"), new BigDecimal("0.93"));
        CveIntelligence original = new CveIntelligence(
                "CVE-2026-1000", "final description",
                new CveIntelligence.CvssScore(new BigDecimal("9.8"), "CVSS:3.1/AV:N", "CRITICAL"),
                "CWE-502", new CveIntelligence.MavenCoordinate("org.example", "demo"),
                new CveIntelligence.VersionRange("1.0.0", "< 2.0.0"), "2.0.0",
                "https://github.com/example/demo",
                Collections.singletonList("https://github.com/example/demo/commit/abc"),
                Collections.singletonList(classified), Collections.emptyList(), Instant.EPOCH,
                Collections.singletonList(source), Collections.singletonList(evidence), adjudication);

        String json = mapper.writeValueAsString(original);
        CveIntelligence restored = mapper.readValue(json, CveIntelligence.class);

        assertEquals("final description", restored.getDescription());
        assertEquals(SourceResult.Status.SUCCESS, restored.getSourceResults().get(0).getStatus());
        assertEquals("provider description",
                restored.getSourceResults().get(0).getOriginalDescription());
        assertEquals(Arrays.asList("NVD", "OSV"), restored.getArticles().get(0).getDiscoveredFrom());
        assertEquals(DescriptionAdjudication.Verdict.RESOLVED,
                restored.getDescriptionAdjudication().getVerdict());
        assertTrue(restored.getDescriptionAdjudication().isResolved());
        assertFalse(restored.getEvidenceResults().isEmpty());
    }

    @Test
    void failedAdjudicationHasNoVerdictOrFinalDescription() {
        DescriptionAdjudication failed = DescriptionAdjudication.failed("invalid response");

        assertEquals(DescriptionAdjudication.Status.FAILED, failed.getStatus());
        assertEquals(null, failed.getVerdict());
        assertEquals("", failed.getFinalDescription());
        assertEquals("invalid response", failed.getErrorMessage());
        assertFalse(failed.isResolved());
    }

    private CveIntelligence legacyIntelligence(String description) {
        return new CveIntelligence(
                "CVE-2026-1000", description,
                new CveIntelligence.CvssScore(BigDecimal.ZERO, "", ""), "",
                new CveIntelligence.MavenCoordinate("", ""),
                new CveIntelligence.VersionRange("", ""), "", "",
                Collections.<String>emptyList(),
                Collections.singletonList(new CveIntelligence.Article(
                        "title", "https://example.org/article", "NVD", "summary", "analysis")),
                Collections.<CveIntelligence.ReferenceFinding>emptyList(), Instant.EPOCH);
    }
}

package com.jvuln.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.store.model.EvidenceResult;
import com.jvuln.store.model.SourceData;
import com.jvuln.store.model.SourceResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceCollectorTest {

    @Test
    void selectsInlinePrimaryEvidenceBeforeLinksAndLimitsTrustedAnalyses() {
        FakeFetcher fetcher = new FakeFetcher();
        EvidenceCollector collector = new EvidenceCollector(fetcher, new ObjectMapper(), 10_000);

        List<EvidenceResult> evidence = collector.collect(
                Arrays.asList(success(SourceResult.Source.NVD), success(SourceResult.Source.GHSA)),
                references());

        assertEquals(EvidenceResult.Kind.SOURCE_DESCRIPTION, evidence.get(0).getKind());
        assertEquals(EvidenceResult.Kind.SOURCE_FACTS, evidence.get(1).getKind());
        assertEquals(2, count(evidence, EvidenceResult.Kind.SOURCE_DESCRIPTION));
        assertEquals(2, count(evidence, EvidenceResult.Kind.SOURCE_FACTS));
        assertEquals(1, count(evidence, EvidenceResult.Kind.ADVISORY));
        assertEquals(1, count(evidence, EvidenceResult.Kind.PATCH));
        assertEquals(3, count(evidence, EvidenceResult.Kind.ANALYSIS));
        assertFalse(evidence.stream().anyMatch(e -> e.getUrl().contains("untrusted.example")));
        int advisoryIndex = firstIndex(evidence, EvidenceResult.Kind.ADVISORY);
        int patchIndex = firstIndex(evidence, EvidenceResult.Kind.PATCH);
        int analysisIndex = firstIndex(evidence, EvidenceResult.Kind.ANALYSIS);
        assertTrue(advisoryIndex < patchIndex && patchIndex < analysisIndex);
    }

    @Test
    void preservesFetchFailureUsesStableIdsAndEnforcesAggregateBudget() {
        FakeFetcher fetcher = new FakeFetcher();
        EvidenceCollector collector = new EvidenceCollector(fetcher, new ObjectMapper(), 180);

        List<EvidenceResult> first = collector.collect(
                Collections.singletonList(success(SourceResult.Source.NVD)), references());
        List<EvidenceResult> second = collector.collect(
                Collections.singletonList(success(SourceResult.Source.NVD)), references());

        assertEquals(ids(first), ids(second));
        assertTrue(first.stream().anyMatch(e -> e.getFetchStatus() == EvidenceResult.FetchStatus.FAILED));
        int suppliedCharacters = first.stream().filter(EvidenceResult::isAvailable)
                .mapToInt(e -> e.getExcerpt().length()).sum();
        assertTrue(suppliedCharacters <= 180);
        assertTrue(first.stream().allMatch(e -> !e.getEvidenceId().isEmpty()));
    }

    @Test
    void reservesBoundedInlineEvidenceForEverySuccessfulProvider() {
        FakeFetcher fetcher = new FakeFetcher();
        EvidenceCollector collector = new EvidenceCollector(fetcher, new ObjectMapper(), 24_000);
        List<SourceResult> sources = Arrays.asList(
                successWithDescription(SourceResult.Source.NVD, repeat('n', 20_000)),
                successWithDescription(SourceResult.Source.GHSA, repeat('g', 20_000)),
                successWithDescription(SourceResult.Source.OSV, repeat('o', 20_000)));

        List<EvidenceResult> evidence = collector.collect(sources, Collections.emptyList());

        assertEquals(3, count(evidence, EvidenceResult.Kind.SOURCE_DESCRIPTION));
        assertEquals(3, count(evidence, EvidenceResult.Kind.SOURCE_FACTS));
        assertTrue(evidence.stream().allMatch(EvidenceResult::isAvailable));
        assertTrue(evidence.stream()
                .filter(e -> e.getKind() == EvidenceResult.Kind.SOURCE_DESCRIPTION)
                .allMatch(e -> e.getExcerpt().length() <= 3000));
        assertTrue(evidence.stream()
                .filter(e -> e.getKind() == EvidenceResult.Kind.SOURCE_FACTS)
                .allMatch(e -> e.getExcerpt().length() <= 4000));
    }

    private SourceResult success(SourceResult.Source source) {
        SourceData data = new SourceData("CWE-79", "8.8", "CVSS", "HIGH",
                "org.example", "demo", "1.0", "< 2.0", "2.0",
                "https://github.com/example/demo", Collections.singletonList("commit"),
                Collections.<CveIntelligence.Article>emptyList());
        return new SourceResult(source, SourceResult.Status.SUCCESS, 4, "", "",
                source.name() + " source description", data, "{}");
    }

    private List<CveIntelligence.Article> references() {
        List<CveIntelligence.Article> result = new ArrayList<>();
        result.add(classified("https://nvd.nist.gov/vuln/detail/CVE-2026-1000", "advisory"));
        result.add(classified("https://untrusted.example/advisory", "advisory"));
        result.add(classified("https://github.com/example/demo/commit/abc", "patch"));
        result.add(classified("https://unit42.paloaltonetworks.com/a", "analysis"));
        result.add(classified("https://jfrog.com/blog/b", "analysis"));
        result.add(classified("https://www.rapid7.com/blog/c", "analysis"));
        result.add(classified("https://securitylab.github.com/research/d", "analysis"));
        result.add(classified("https://untrusted.example/blog/e", "analysis"));
        return result;
    }

    private CveIntelligence.Article classified(String url, String category) {
        return new CveIntelligence.Article("title", url, "NVD", "", category,
                Collections.singletonList("NVD"), "RULE", "test", BigDecimal.ONE);
    }

    private SourceResult successWithDescription(SourceResult.Source source, String description) {
        SourceResult base = success(source);
        return new SourceResult(source, SourceResult.Status.SUCCESS, 4, "", "",
                description, base.getParsedData(), "{}");
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private long count(List<EvidenceResult> evidence, EvidenceResult.Kind kind) {
        return evidence.stream().filter(e -> e.getKind() == kind).count();
    }

    private int firstIndex(List<EvidenceResult> evidence, EvidenceResult.Kind kind) {
        for (int i = 0; i < evidence.size(); i++) {
            if (evidence.get(i).getKind() == kind) return i;
        }
        return -1;
    }

    private List<String> ids(List<EvidenceResult> evidence) {
        List<String> result = new ArrayList<>();
        for (EvidenceResult item : evidence) result.add(item.getEvidenceId());
        return result;
    }

    private static class FakeFetcher implements EvidencePageFetcher {
        @Override
        public FetchOutcome fetch(String url) {
            if (url.contains("/commit/")) {
                return FetchOutcome.failed("fixture failure");
            }
            return FetchOutcome.success("Fetched evidence from " + url);
        }
    }
}

package com.jvuln.collector;

import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.store.model.CveIntelligence;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleClassifierTest {

    @Test
    void canonicalizesDeduplicatesMergesProvenanceAndAppliesAllRuleCategories() {
        CapturingLlmClient llm = new CapturingLlmClient("[]");
        ArticleClassifier classifier = classifier(llm);
        List<CveIntelligence.Article> input = Arrays.asList(
                article("NVD", "https://NVD.NIST.GOV/vuln/detail/CVE-2026-1000#section", ""),
                article("NVD", "https://GitHub.com/acme/demo/commit/abc?utm_source=nvd#diff", ""),
                article("OSV", "https://github.com/acme/demo/commit/abc", "commit"),
                article("GHSA", "https://www.exploit-db.com/exploits/123", ""),
                article("OSV", "https://unit42.paloaltonetworks.com/cve-research-blog", "CVE analysis"),
                article("NVD", "https://docs.example.org/docs/installation", "documentation"));

        List<CveIntelligence.Article> result =
                classifier.classifyAndDeduplicate(input, "CVE-2026-1000");

        assertEquals(5, result.size());
        assertEquals(Arrays.asList("advisory", "patch", "poc", "analysis", "other"),
                categories(result));
        assertEquals("https://github.com/acme/demo/commit/abc", result.get(1).getUrl());
        assertEquals(Arrays.asList("NVD", "OSV"), result.get(1).getDiscoveredFrom());
        assertTrue(result.stream().allMatch(a -> "RULE".equals(a.getClassificationMethod())));
        assertTrue(result.stream().allMatch(a -> !a.getClassificationReason().isEmpty()));
        assertEquals(0, llm.calls);
    }

    @Test
    void sendsOnlyUnresolvedReferencesAndRequiresStableIdResponse() {
        CapturingLlmClient llm = new CapturingLlmClient("{\"classifications\":[{"
                + "\"referenceId\":\"REF-0001\",\"category\":\"analysis\","
                + "\"reason\":\"technical write-up\",\"confidence\":0.81}]}");
        ArticleClassifier classifier = classifier(llm);

        List<CveIntelligence.Article> result = classifier.classifyAndDeduplicate(Arrays.asList(
                article("NVD", "https://github.com/acme/demo/commit/abc", ""),
                article("OSV", "https://unknown.example/resource/42", "unknown")),
                "CVE-2026-1000");

        assertEquals(1, llm.calls);
        String prompt = llm.lastRequest.getMessages().get(0).getTextContent();
        assertTrue(prompt.contains("REF-0001"));
        assertTrue(prompt.contains("unknown.example"));
        assertFalse(prompt.contains("/commit/abc"));
        assertEquals("LLM", result.get(1).getClassificationMethod());
        assertEquals("technical write-up", result.get(1).getClassificationReason());
        assertEquals(new BigDecimal("0.81"), result.get(1).getClassificationConfidence());
    }

    @Test
    void acceptsLegacyTopLevelArrayResponse() {
        CapturingLlmClient llm = new CapturingLlmClient("[{"
                + "\"referenceId\":\"REF-0001\",\"category\":\"other\","
                + "\"reason\":\"legacy provider\",\"confidence\":0.6}]");

        List<CveIntelligence.Article> result = classifier(llm)
                .classifyAndDeduplicate(Collections.singletonList(
                        article("NVD", "https://unknown.example/item", "")),
                        "CVE-2026-1000");

        assertEquals("other", result.get(0).getCategory());
        assertEquals("LLM", result.get(0).getClassificationMethod());
    }

    @Test
    void rejectsIncompleteDuplicateInvalidAndMalformedLlmResponses() {
        List<String> invalidResponses = Arrays.asList(
                "[]",
                "[{\"referenceId\":\"REF-0001\",\"category\":\"other\","
                        + "\"reason\":\"x\",\"confidence\":0.5},{\"referenceId\":\"REF-0001\","
                        + "\"category\":\"other\",\"reason\":\"x\",\"confidence\":0.5}]",
                "[{\"referenceId\":\"REF-0001\",\"category\":\"invalid\","
                        + "\"reason\":\"x\",\"confidence\":0.5}]",
                "[{\"referenceId\":\"REF-0001\",\"category\":\"ANALYSIS\","
                        + "\"reason\":\"x\",\"confidence\":0.5}]",
                "[{\"referenceId\":\"REF-0001\",\"category\":\"other\","
                        + "\"reason\":\"\",\"confidence\":0.5}]",
                "[{\"referenceId\":\"REF-0001\",\"category\":\"other\","
                        + "\"reason\":\"x\",\"confidence\":1.5}]",
                "{}",
                "{\"classifications\":{}}",
                "{\"results\":[]}",
                "{\"classifications\":[],\"extra\":true}",
                "not-json");

        for (String response : invalidResponses) {
            ArticleClassifier classifier = classifier(new CapturingLlmClient(response));
            ArticleClassifier.ClassificationException error = assertThrows(
                    ArticleClassifier.ClassificationException.class,
                    () -> classifier.classifyAndDeduplicate(Collections.singletonList(
                            article("NVD", "https://unknown.example/item", "")),
                            "CVE-2026-1000"));
            assertEquals("INVALID_LLM_RESPONSE", error.getCode());
            assertFalse(error.getPartialArticles().isEmpty());
        }
    }

    @Test
    void neverTruncatesAndFailsExplicitlyWhenRequestCannotFitBudget() {
        List<CveIntelligence.Article> articles = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            articles.add(article("NVD", "https://unknown" + i + ".example/item", ""));
        }
        ArticleClassifier classifier = new ArticleClassifier(
                new CapturingLlmClient("[]"), new PromptRegistry(), 600);

        ArticleClassifier.ClassificationException error = assertThrows(
                ArticleClassifier.ClassificationException.class,
                () -> classifier.classifyAndDeduplicate(articles, "CVE-2026-1000"));

        assertEquals("INPUT_BUDGET_EXCEEDED", error.getCode());
        assertEquals(25, error.getPartialArticles().size());
    }

    private ArticleClassifier classifier(CapturingLlmClient llm) {
        return new ArticleClassifier(llm, new PromptRegistry());
    }

    private CveIntelligence.Article article(String source, String url, String title) {
        return new CveIntelligence.Article(title, url, source, "summary");
    }

    private List<String> categories(List<CveIntelligence.Article> articles) {
        List<String> result = new ArrayList<>();
        for (CveIntelligence.Article article : articles) {
            result.add(article.getCategory());
        }
        return result;
    }

    private static class CapturingLlmClient implements LlmClient {
        private final String response;
        private int calls;
        private LlmRequest lastRequest;

        private CapturingLlmClient(String response) {
            this.response = response;
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            calls++;
            lastRequest = request;
            return new LlmResponse(response, 0, 0, "test", "stop");
        }

        @Override
        public Flux<String> chatStream(LlmRequest request) {
            return Flux.error(new UnsupportedOperationException());
        }
    }
}

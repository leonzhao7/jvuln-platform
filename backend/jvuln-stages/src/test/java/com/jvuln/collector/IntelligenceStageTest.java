package com.jvuln.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jvuln.collector.source.IntelSource;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.store.WorkspaceManager;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.store.model.DescriptionAdjudication;
import com.jvuln.store.model.EvidenceResult;
import com.jvuln.store.model.SourceData;
import com.jvuln.store.model.SourceResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelligenceStageTest {

    private static final String CVE = "CVE-2026-1000";
    @TempDir
    Path tempDir;

    @Test
    void persistsPreferredSourceDescriptionAndReturnsSuccess() throws Exception {
        Fixture fixture = fixture(sourceResults());

        StageResult result = fixture.stage.execute(fixture.context);
        CveIntelligence intelligence = (CveIntelligence) result.getData();
        CveIntelligence persisted = fixture.workspace.readStageData(CVE, 1, CveIntelligence.class);

        assertTrue(result.isSuccess());
        assertEquals("NVD original description", intelligence.getDescription());
        assertEquals(DescriptionAdjudication.Status.NOT_RUN,
                intelligence.getDescriptionAdjudication().getStatus());
        assertEquals(3, persisted.getSourceResults().size());
        assertEquals("NVD original description",
                persisted.getSourceResults().get(0).getOriginalDescription());
        assertFalse(persisted.getSourceResults().get(0).getRawPayload().isEmpty());
        assertEquals("CWE-502", persisted.getCweId());
        assertEquals("org.example:demo", persisted.getArtifact().toGav());
        assertTrue(fixture.classifier.called);
        assertTrue(fixture.evidenceCollector.called);
    }

    @Test
    void allSourceFailuresPersistAuditWithEmptyDescriptionAndSkipRequiredLaterCalls() throws Exception {
        List<SourceResult> failures = Arrays.asList(
                failed(SourceResult.Source.NVD), notFound(SourceResult.Source.GHSA),
                failed(SourceResult.Source.OSV));
        Fixture fixture = fixture(failures);

        StageResult result = fixture.stage.execute(fixture.context);
        CveIntelligence persisted = fixture.workspace.readStageData(CVE, 1, CveIntelligence.class);

        assertFalse(result.isSuccess());
        assertEquals("", persisted.getDescription());
        assertEquals(3, persisted.getSourceResults().size());
        assertEquals(DescriptionAdjudication.Status.NOT_RUN,
                persisted.getDescriptionAdjudication().getStatus());
        assertFalse(fixture.classifier.called);
        assertFalse(fixture.evidenceCollector.called);
    }

    @Test
    void classificationFailurePersistsRuleClassifiedReferencesAndNotRunAdjudication() throws Exception {
        Fixture fixture = fixture(sourceResults());
        CveIntelligence.Article partial = classifiedArticle("patch");
        fixture.classifier.failure = new ArticleClassifier.ClassificationException(
                "INVALID_LLM_RESPONSE", "missing ID", Collections.singletonList(partial));

        StageResult result = fixture.stage.execute(fixture.context);
        CveIntelligence persisted = fixture.workspace.readStageData(CVE, 1, CveIntelligence.class);

        assertFalse(result.isSuccess());
        assertEquals("", persisted.getDescription());
        assertEquals("patch", persisted.getArticles().get(0).getCategory());
        assertEquals(DescriptionAdjudication.Status.NOT_RUN,
                persisted.getDescriptionAdjudication().getStatus());
        assertFalse(fixture.evidenceCollector.called);
    }

    private Fixture fixture(List<SourceResult> results) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        WorkspaceManager workspace = new WorkspaceManager(tempDir.toString(), mapper);
        workspace.initCveWorkspace(CVE);
        PipelineContext context = new PipelineContext(CVE, workspace.getCvePath(CVE),
                noOpLlm(), workspace);
        FakeSourceCollector sourceCollector = new FakeSourceCollector(results);
        FakeArticleClassifier classifier = new FakeArticleClassifier();
        FakeEvidenceCollector evidenceCollector = new FakeEvidenceCollector();
        IntelligenceStage stage = new IntelligenceStage(
                namedSources(), sourceCollector, classifier, evidenceCollector,
                new IntelligenceAssembler());
        return new Fixture(stage, context, workspace, classifier, evidenceCollector);
    }

    private List<SourceResult> sourceResults() {
        return Arrays.asList(success(SourceResult.Source.NVD),
                notFound(SourceResult.Source.GHSA), failed(SourceResult.Source.OSV));
    }

    private SourceResult success(SourceResult.Source source) {
        SourceData data = new SourceData("CWE-502", "9.8", "CVSS:3.1", "CRITICAL",
                "org.example", "demo", "1.0", "< 2.0", "2.0",
                "https://github.com/example/demo", Collections.singletonList("commit-abc"),
                Collections.singletonList(new CveIntelligence.Article("advisory",
                        "https://nvd.nist.gov/vuln/detail/" + CVE, source.name(), "")));
        return new SourceResult(source, SourceResult.Status.SUCCESS, 10, "", "",
                source.name() + " original description", data,
                "{\"source\":\"" + source.name() + "\"}");
    }

    private SourceResult failed(SourceResult.Source source) {
        return new SourceResult(source, SourceResult.Status.FAILED, 10,
                "IOException", "offline", "", SourceData.empty(), "");
    }

    private SourceResult notFound(SourceResult.Source source) {
        return new SourceResult(source, SourceResult.Status.NOT_FOUND, 5,
                "", "", "", SourceData.empty(), "[]");
    }

    private CveIntelligence.Article classifiedArticle(String category) {
        return new CveIntelligence.Article("title", "https://github.com/a/b/commit/c",
                "NVD", "", category, Collections.singletonList("NVD"),
                "RULE", "path.code-change", BigDecimal.ONE);
    }

    private List<IntelSource> namedSources() {
        return Arrays.asList(namedSource("NVD"), namedSource("GHSA"), namedSource("OSV"));
    }

    private IntelSource namedSource(String name) {
        return new IntelSource() {
            @Override
            public String name() { return name; }

            @Override
            public IntelFragment collect(String cveId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private LlmClient noOpLlm() {
        return new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<String> chatStream(LlmRequest request) {
                return Flux.error(new UnsupportedOperationException());
            }
        };
    }

    private static class Fixture {
        private final IntelligenceStage stage;
        private final PipelineContext context;
        private final WorkspaceManager workspace;
        private final FakeArticleClassifier classifier;
        private final FakeEvidenceCollector evidenceCollector;

        private Fixture(IntelligenceStage stage, PipelineContext context,
                        WorkspaceManager workspace, FakeArticleClassifier classifier,
                        FakeEvidenceCollector evidenceCollector) {
            this.stage = stage;
            this.context = context;
            this.workspace = workspace;
            this.classifier = classifier;
            this.evidenceCollector = evidenceCollector;
        }
    }

    private static class FakeSourceCollector extends SourceCollector {
        private final List<SourceResult> results;

        private FakeSourceCollector(List<SourceResult> results) {
            this.results = results;
        }

        @Override
        public List<SourceResult> collect(String cveId, List<IntelSource> sources) {
            return results;
        }
    }

    private class FakeArticleClassifier extends ArticleClassifier {
        private boolean called;
        private ClassificationException failure;

        private FakeArticleClassifier() {
            super(noOpLlm(), new PromptRegistry());
        }

        @Override
        public List<CveIntelligence.Article> classifyAndDeduplicate(
                List<CveIntelligence.Article> articles, String cveId) {
            called = true;
            if (failure != null) throw failure;
            List<CveIntelligence.Article> result = new ArrayList<>();
            for (CveIntelligence.Article article : articles) {
                result.add(new CveIntelligence.Article(article.getTitle(), article.getUrl(),
                        article.getSource(), article.getSummary(), "advisory",
                        article.getDiscoveredFrom(), "RULE", "test", BigDecimal.ONE));
            }
            return result;
        }
    }

    private static class FakeEvidenceCollector extends EvidenceCollector {
        private boolean called;

        private FakeEvidenceCollector() {
            super(url -> EvidencePageFetcher.FetchOutcome.failed("unused"),
                    new ObjectMapper(), 1000);
        }

        @Override
        public List<EvidenceResult> collect(List<SourceResult> sources,
                                            List<CveIntelligence.Article> articles) {
            called = true;
            return Collections.singletonList(new EvidenceResult(
                    "E-SRC-NVD-DESC", EvidenceResult.Kind.SOURCE_DESCRIPTION,
                    "NVD", "description", "", EvidenceResult.FetchStatus.INLINE,
                    "NVD original description", ""));
        }
    }

}

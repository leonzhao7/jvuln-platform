package com.jvuln.collector;

import com.jvuln.collector.source.IntelSource;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.store.model.DescriptionAdjudication;
import com.jvuln.store.model.EvidenceResult;
import com.jvuln.store.model.SourceResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.jvuln.util.ValueUtils.errorMessage;

@Component
public class IntelligenceStage implements Stage {

    private final List<IntelSource> sources;
    private final SourceCollector sourceCollector;
    private final ArticleClassifier articleClassifier;
    private final EvidenceCollector evidenceCollector;
    private final DescriptionAdjudicator descriptionAdjudicator;
    private final IntelligenceAssembler assembler;

    public IntelligenceStage(List<IntelSource> sources,
                             SourceCollector sourceCollector,
                             ArticleClassifier articleClassifier,
                             EvidenceCollector evidenceCollector,
                             DescriptionAdjudicator descriptionAdjudicator,
                             IntelligenceAssembler assembler) {
        this.sources = supportedSources(sources);
        this.sourceCollector = sourceCollector;
        this.articleClassifier = articleClassifier;
        this.evidenceCollector = evidenceCollector;
        this.descriptionAdjudicator = descriptionAdjudicator;
        this.assembler = assembler;
    }

    @Override
    public int number() { return 1; }

    @Override
    public String name() { return "Intelligence Collection"; }

    @Override
    public StageResult execute(PipelineContext context) throws Exception {
        String cveId = context.getCveId();
        context.reportProgress("Collecting NVD, GHSA, and OSV intelligence concurrently");
        List<SourceResult> sourceResults = sourceCollector.collect(cveId, sources);
        IntelligenceAssembler.Draft draft = assembler.merge(cveId, sourceResults);
        long successCount = sourceResults.stream().filter(SourceResult::isSuccess).count();
        context.reportProgress("Collected from " + successCount + "/" + sources.size()
                + " public intelligence sources");

        if (successCount == 0) {
            String message = "No public intelligence source succeeded";
            CveIntelligence partial = draft.toIntelligence("", Collections.emptyList(),
                    Collections.emptyList(), DescriptionAdjudication.notRun(message));
            return persistFailure(context, partial, message);
        }

        List<CveIntelligence.Article> classified;
        try {
            classified = articleClassifier.classifyAndDeduplicate(
                    draft.getArticles(), cveId);
        } catch (ArticleClassifier.ClassificationException e) {
            String message = e.getCode() + ": " + e.getMessage();
            CveIntelligence partial = draft.toIntelligence("", e.getPartialArticles(),
                    Collections.emptyList(), DescriptionAdjudication.notRun(message));
            return persistFailure(context, partial, message);
        } catch (Exception e) {
            String message = "Reference classification failed: " + errorMessage(e, 500);
            CveIntelligence partial = draft.toIntelligence("", Collections.emptyList(),
                    Collections.emptyList(), DescriptionAdjudication.notRun(message));
            return persistFailure(context, partial, message);
        }

        List<EvidenceResult> evidence;
        try {
            evidence = evidenceCollector.collect(sourceResults, classified);
        } catch (Exception e) {
            String message = "Evidence collection failed: " + errorMessage(e, 500);
            CveIntelligence partial = draft.toIntelligence("", classified,
                    Collections.emptyList(), DescriptionAdjudication.notRun(message));
            return persistFailure(context, partial, message);
        }

        DescriptionAdjudication adjudication;
        try {
            adjudication = descriptionAdjudicator.adjudicate(cveId, sourceResults, evidence);
        } catch (Exception e) {
            adjudication = DescriptionAdjudication.failed(errorMessage(e, 500));
        }
        if (adjudication == null) {
            adjudication = DescriptionAdjudication.failed("Adjudicator returned no result");
        }
        if (!adjudication.isResolved()) {
            String message = failureMessage(adjudication);
            CveIntelligence partial = draft.toIntelligence("", classified, evidence, adjudication);
            return persistFailure(context, partial, message);
        }

        CveIntelligence complete = draft.toIntelligence(
                adjudication.getFinalDescription(), classified, evidence, adjudication);
        context.getWorkspaceManager().writeStageData(cveId, number(), complete);
        return StageResult.success(number(), name(), complete);
    }

    private StageResult persistFailure(PipelineContext context,
                                       CveIntelligence partial, String message) throws Exception {
        context.getWorkspaceManager().writeStageData(
                context.getCveId(), number(), partial);
        return StageResult.failure(number(), name(), message);
    }

    private String failureMessage(DescriptionAdjudication adjudication) {
        if (!adjudication.getErrorMessage().trim().isEmpty()) {
            return adjudication.getErrorMessage();
        }
        if (!adjudication.getReason().trim().isEmpty()) {
            return adjudication.getReason();
        }
        return "Description adjudication did not resolve the CVE";
    }

    private List<IntelSource> supportedSources(List<IntelSource> configured) {
        List<IntelSource> result = new ArrayList<>();
        if (configured != null) {
            for (IntelSource source : configured) {
                if (source != null && sourceOrder(source.name()) < 3) {
                    result.add(source);
                }
            }
        }
        result.sort(Comparator.comparingInt(source -> sourceOrder(source.name())));
        return Collections.unmodifiableList(result);
    }

    private int sourceOrder(String name) {
        String normalized = name == null ? "" : name.toUpperCase(Locale.ROOT);
        if ("NVD".equals(normalized)) return 0;
        if ("GHSA".equals(normalized) || normalized.contains("GITHUB")) return 1;
        if ("OSV".equals(normalized)) return 2;
        return 3;
    }
}

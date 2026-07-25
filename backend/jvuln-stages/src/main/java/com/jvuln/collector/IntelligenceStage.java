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
    private final IntelligenceAssembler assembler;
    private final PatchCommitInferer patchCommitInferer;

    public IntelligenceStage(List<IntelSource> sources,
                             SourceCollector sourceCollector,
                             ArticleClassifier articleClassifier,
                             EvidenceCollector evidenceCollector,
                             IntelligenceAssembler assembler,
                             PatchCommitInferer patchCommitInferer) {
        this.sources = supportedSources(sources);
        this.sourceCollector = sourceCollector;
        this.articleClassifier = articleClassifier;
        this.evidenceCollector = evidenceCollector;
        this.assembler = assembler;
        this.patchCommitInferer = patchCommitInferer;
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

        try {
            if (draft.getFixCommits().isEmpty() && draft.hasSourceRepo()) {
                PatchCommitInferer.InferenceResult inference = patchCommitInferer.infer(
                        cveId, draft.getDescription(), draft.getSourceRepo(), draft.getFixedVersions());
                if (inference.hasResult()) {
                    for (String url : inference.getCommitUrls()) {
                        draft.addFixCommit(url);
                    }
                    if (inference.getChosenVersion() != null) {
                        draft.setFixedVersion(inference.getChosenVersion());
                    }
                    context.reportProgress("Inferred " + inference.getCommitUrls().size()
                            + " patch commit(s) via LLM analysis");
                } else {
                    context.reportProgress("Could not infer patch commits; will fall back to maven-source-diff");
                }
            }
        } catch (Exception e) {
            context.reportProgress("Patch commit inference failed: " + e.getMessage()
                    + "; will fall back to maven-source-diff");
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

        CveIntelligence complete = draft.toIntelligence(
                draft.getDescription(), classified, evidence,
                DescriptionAdjudication.notRun(""));
        context.getWorkspaceManager().writeStageData(cveId, number(), complete);
        return StageResult.success(number(), name(), complete);
    }

    private StageResult persistFailure(PipelineContext context,
                                       CveIntelligence partial, String message) throws Exception {
        context.getWorkspaceManager().writeStageData(
                context.getCveId(), number(), partial);
        return StageResult.failure(number(), name(), message);
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

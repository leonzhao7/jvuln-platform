package com.jvuln.collector;

import com.jvuln.store.model.CveIntelligence;
import com.jvuln.collector.source.IntelSource;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Component
public class IntelligenceStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceStage.class);
    private static final int SOURCE_STAGE_TIMEOUT_SECONDS = 45;
    private final List<IntelSource> sources;

    public IntelligenceStage(List<IntelSource> sources) {
        this.sources = sources;
    }

    @Override
    public int number() { return 1; }

    @Override
    public String name() { return "Intelligence Collection"; }

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        String cveId = ctx.getCveId();
        ctx.reportProgress("Starting intelligence collection from " + sources.size() + " sources");

        ExecutorService executor = Executors.newFixedThreadPool(sources.size());
        List<Future<IntelSource.IntelFragment>> futures = new ArrayList<>();
        List<IntelSource> scheduledSources = new ArrayList<>();

        for (final IntelSource source : sources) {
            scheduledSources.add(source);
            futures.add(executor.submit(new Callable<IntelSource.IntelFragment>() {
                @Override
                public IntelSource.IntelFragment call() {
                    try {
                        IntelSource.IntelFragment fragment = source.collect(cveId);
                        ctx.reportProgress("Source " + source.name() + " completed"
                                + (fragment.isSuccess() ? "" : " with no usable match"));
                        return fragment;
                    } catch (Exception e) {
                        log.warn("Source {} failed: {}", source.name(), e.getMessage());
                        ctx.reportProgress("Source " + source.name() + " failed: " + e.getMessage());
                        return failedFragment(source, e.getMessage());
                    }
                }
            }));
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(SOURCE_STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            log.warn("IntelligenceStage timed out after {}s for {}", SOURCE_STAGE_TIMEOUT_SECONDS, cveId);
            ctx.reportProgress("Intelligence collection timed out after " + SOURCE_STAGE_TIMEOUT_SECONDS
                    + "s; cancelling slow sources");
            executor.shutdownNow();
        }

        List<IntelSource.IntelFragment> fragments = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Future<IntelSource.IntelFragment> f = futures.get(i);
            IntelSource source = scheduledSources.get(i);
            try {
                if (!f.isDone()) {
                    f.cancel(true);
                    String message = "Timed out after " + SOURCE_STAGE_TIMEOUT_SECONDS + "s";
                    log.warn("Source {} did not finish for {}: {}", source.name(), cveId, message);
                    fragments.add(failedFragment(source, message));
                    continue;
                }
                fragments.add(f.get(1, TimeUnit.SECONDS));
            } catch (CancellationException e) {
                String message = "Cancelled after stage timeout";
                log.warn("Source {} cancelled for {}: {}", source.name(), cveId, message);
                fragments.add(failedFragment(source, message));
            } catch (TimeoutException e) {
                f.cancel(true);
                String message = "Timed out while collecting result";
                log.warn("Source {} timed out while retrieving result for {}", source.name(), cveId);
                fragments.add(failedFragment(source, message));
            } catch (Exception e) {
                log.warn("Fragment retrieval failed for {}: {}", source.name(), e.getMessage());
                fragments.add(failedFragment(source, e.getMessage()));
            }
        }

        CveIntelligence intel = merge(cveId, fragments);
        ctx.getWorkspaceManager().writeStageData(cveId, 1, intel);

        long successCount = 0;
        for (IntelSource.IntelFragment f : fragments) {
            if (f.isSuccess()) successCount++;
        }
        ctx.reportProgress("Collected from " + successCount + "/" + sources.size() + " sources");

        return StageResult.success(1, name(), intel);
    }

    private IntelSource.IntelFragment failedFragment(IntelSource source, String message) {
        return new IntelSource.IntelFragment(
                source.name(), false, "", "", "", "", "", "", "", "", "", "",
                Collections.<String>emptyList(),
                Collections.<CveIntelligence.Article>emptyList(),
                message == null ? "" : message);
    }

    private CveIntelligence merge(String cveId, List<IntelSource.IntelFragment> fragments) {
        String description = "";
        String cweId = "";
        BigDecimal cvssScore = BigDecimal.ZERO;
        String cvssSeverity = "";
        String groupId = "";
        String artifactId = "";
        String affectedFrom = "";
        String affectedTo = "";
        String fixedVersion = "";
        String sourceRepo = "";
        Set<String> fixCommits = new LinkedHashSet<>();
        List<CveIntelligence.Article> allArticles = new ArrayList<>();

        for (IntelSource.IntelFragment f : fragments) {
            if (!f.isSuccess()) continue;

            if (description.isEmpty() && !f.getDescription().isEmpty()) description = f.getDescription();
            if (cweId.isEmpty() && !f.getCweId().isEmpty()) cweId = f.getCweId();
            if (cvssScore.compareTo(BigDecimal.ZERO) == 0 && !f.getCvssScore().isEmpty()) {
                try { cvssScore = new BigDecimal(f.getCvssScore()); } catch (Exception ignored) {}
            }
            if (cvssSeverity.isEmpty() && !f.getCvssSeverity().isEmpty()) cvssSeverity = f.getCvssSeverity();
            if (groupId.isEmpty() && !f.getArtifactGroupId().isEmpty()) groupId = f.getArtifactGroupId();
            if (artifactId.isEmpty() && !f.getArtifactId().isEmpty()) artifactId = f.getArtifactId();
            if (affectedFrom.isEmpty() && !f.getAffectedFrom().isEmpty()) affectedFrom = f.getAffectedFrom();
            if (affectedTo.isEmpty() && !f.getAffectedTo().isEmpty()) affectedTo = f.getAffectedTo();
            if (fixedVersion.isEmpty() && !f.getFixedVersion().isEmpty()) fixedVersion = f.getFixedVersion();
            if (sourceRepo.isEmpty() && !f.getSourceRepo().isEmpty()) sourceRepo = f.getSourceRepo();
            fixCommits.addAll(f.getFixCommits());
            allArticles.addAll(f.getArticles());
        }

        return new CveIntelligence(
                cveId, description,
                new CveIntelligence.CvssScore(cvssScore, "", cvssSeverity),
                cweId,
                new CveIntelligence.MavenCoordinate(groupId, artifactId),
                new CveIntelligence.VersionRange(affectedFrom, affectedTo),
                fixedVersion, sourceRepo,
                new ArrayList<>(fixCommits),
                allArticles,
                Instant.now()
        );
    }
}

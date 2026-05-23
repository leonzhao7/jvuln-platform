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

        for (final IntelSource source : sources) {
            futures.add(executor.submit(new Callable<IntelSource.IntelFragment>() {
                @Override
                public IntelSource.IntelFragment call() {
                    try {
                        return source.collect(cveId);
                    } catch (Exception e) {
                        log.warn("Source {} failed: {}", source.name(), e.getMessage());
                        return new IntelSource.IntelFragment(
                                source.name(), false, "", "", "", "", "", "", "", "", "", "",
                                Collections.<String>emptyList(),
                                Collections.<CveIntelligence.Article>emptyList(),
                                e.getMessage());
                    }
                }
            }));
        }

        executor.shutdown();
        executor.awaitTermination(60, TimeUnit.SECONDS);

        List<IntelSource.IntelFragment> fragments = new ArrayList<>();
        for (Future<IntelSource.IntelFragment> f : futures) {
            try {
                fragments.add(f.get());
            } catch (Exception e) {
                log.warn("Fragment retrieval failed: {}", e.getMessage());
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

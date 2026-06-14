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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IntelligenceStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceStage.class);
    private static final int SOURCE_STAGE_TIMEOUT_SECONDS = 45;
    private static final Pattern DESCRIPTION_COMPONENT_PATTERN =
            Pattern.compile("^([a-zA-Z0-9_.-]+)\\s+v?\\d+(?:\\.\\d+)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESCRIPTION_AFFECTED_PATTERN =
            Pattern.compile("v?(\\d+(?:\\.\\d+)+)\\s+and below", Pattern.CASE_INSENSITIVE);
    private final List<IntelSource> sources;
    private final ReferenceEnricher referenceEnricher;
    private final ArticleClassifier articleClassifier;

    public IntelligenceStage(List<IntelSource> sources, ReferenceEnricher referenceEnricher,
                             ArticleClassifier articleClassifier) {
        this.sources = sources;
        this.referenceEnricher = referenceEnricher;
        this.articleClassifier = articleClassifier;
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

        sourceRepo = preferRepo(sourceRepo, allArticles);
        affectedTo = preferAffectedTo(affectedTo, description);
        artifactId = preferArtifactId(artifactId, sourceRepo);
        artifactId = preferDescriptionArtifact(artifactId, sourceRepo, description);
        ReferenceEnricher.EnrichmentResult enrichment = referenceEnricher.enrich(
                cveId, sourceRepo, fixedVersion, allArticles, fixCommits);

        if (sourceRepo.isEmpty() && enrichment.getSourceRepo() != null && !enrichment.getSourceRepo().isEmpty()) {
            sourceRepo = enrichment.getSourceRepo();
        }
        if ((fixedVersion == null || fixedVersion.isEmpty())
                && enrichment.getFixedVersion() != null && !enrichment.getFixedVersion().isEmpty()) {
            fixedVersion = enrichment.getFixedVersion();
        }
        if (enrichment.getFixCommits() != null && !enrichment.getFixCommits().isEmpty()) {
            fixCommits.addAll(enrichment.getFixCommits());
        }

        // 对文章进行去重和分类
        List<CveIntelligence.Article> classifiedArticles =
            articleClassifier.classifyAndDeduplicate(allArticles, cveId);

        return new CveIntelligence(
                cveId, description,
                new CveIntelligence.CvssScore(cvssScore, "", cvssSeverity),
                cweId,
                new CveIntelligence.MavenCoordinate(groupId, artifactId),
                new CveIntelligence.VersionRange(affectedFrom, affectedTo),
                fixedVersion, sourceRepo,
                new ArrayList<>(fixCommits),
                classifiedArticles,
                enrichment.getReferenceFindings(),
                Instant.now()
        );
    }

    private String preferRepo(String sourceRepo, List<CveIntelligence.Article> articles) {
        if (sourceRepo != null && !sourceRepo.isEmpty()) {
            return sourceRepo;
        }
        if (articles == null) {
            return "";
        }
        for (CveIntelligence.Article article : articles) {
            String normalized = normalizeRepoUrl(article.getUrl());
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    private String preferArtifactId(String artifactId, String sourceRepo) {
        if (artifactId != null && !artifactId.isEmpty()) {
            return artifactId;
        }
        String normalized = normalizeRepoUrl(sourceRepo);
        if (normalized.isEmpty()) {
            return "";
        }
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : "";
    }

    private String preferAffectedTo(String affectedTo, String description) {
        if (affectedTo != null && !affectedTo.isEmpty()) {
            return affectedTo;
        }
        if (description == null || description.isEmpty()) {
            return "";
        }
        Matcher matcher = DESCRIPTION_AFFECTED_PATTERN.matcher(description);
        return matcher.find() ? "<= " + matcher.group(1) : "";
    }

    private String preferDescriptionArtifact(String artifactId, String sourceRepo, String description) {
        if (description == null || description.isEmpty()) {
            return artifactId;
        }
        Matcher matcher = DESCRIPTION_COMPONENT_PATTERN.matcher(description);
        if (!matcher.find()) {
            return artifactId;
        }
        String described = matcher.group(1);
        if (artifactId == null || artifactId.isEmpty()) {
            return described;
        }
        String repoArtifact = preferArtifactId("", sourceRepo);
        if (artifactId.equals(repoArtifact)) {
            return described;
        }
        return artifactId;
    }

    private String normalizeRepoUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.contains("github.com/")) {
            int idx = trimmed.indexOf("github.com/");
            String tail = trimmed.substring(idx + "github.com/".length());
            String[] parts = tail.split("/");
            if (parts.length >= 2) {
                return "https://github.com/" + stripSuffix(parts[0]) + "/" + stripSuffix(parts[1]);
            }
        }
        if (trimmed.contains("gitee.com/")) {
            int idx = trimmed.indexOf("gitee.com/");
            String tail = trimmed.substring(idx + "gitee.com/".length());
            String[] parts = tail.split("/");
            if (parts.length >= 2) {
                return "https://gitee.com/" + stripSuffix(parts[0]) + "/" + stripSuffix(parts[1]);
            }
        }
        return "";
    }

    private String stripSuffix(String segment) {
        if (segment == null) {
            return "";
        }
        return segment.replaceAll("\\.git$", "").replaceAll("[?#].*$", "");
    }
}

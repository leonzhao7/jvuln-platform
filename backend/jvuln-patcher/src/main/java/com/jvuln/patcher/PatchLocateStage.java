package com.jvuln.patcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.patcher.strategy.AiPatchSearchStrategy;
import com.jvuln.patcher.strategy.AiPatchSearchStrategy.AiEnrichment;
import com.jvuln.patcher.strategy.AiPatchSearchStrategy.AiPatchOutcome;
import com.jvuln.patcher.strategy.LocateStrategy;
import com.jvuln.patcher.strategy.MavenSourceDiffStrategy;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class PatchLocateStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(PatchLocateStage.class);
    private final List<LocateStrategy> strategies;
    private final MavenSourceDiffStrategy mavenStrategy;
    private final AiPatchSearchStrategy aiStrategy;
    private final ObjectMapper mapper = new ObjectMapper();

    public PatchLocateStage(List<LocateStrategy> strategies, MavenSourceDiffStrategy mavenStrategy,
                             AiPatchSearchStrategy aiStrategy) {
        this.aiStrategy = aiStrategy;
        this.mavenStrategy = mavenStrategy;
        this.strategies = new ArrayList<>(strategies);
        this.strategies.sort(Comparator.comparingInt(LocateStrategy::priority));
    }

    @Override
    public int number() { return 2; }

    @Override
    public String name() { return "Patch Locating"; }

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        String cveId = ctx.getCveId();
        ctx.reportProgress("Locating fix commit for " + cveId);

        StageResult stage1 = ctx.getCompletedStages().get(1);
        if (stage1 == null || stage1.getData() == null) {
            return StageResult.failure(2, name(), "Stage 1 data not available");
        }

        // Stage 1 data may be a real CveIntelligence or a deserialized Map (when loaded from cache)
        Object stage1Data = stage1.getData();
        String sourceRepo    = extractString(stage1Data, "sourceRepo");
        String groupId       = extractNestedString(stage1Data, "artifact", "groupId");
        String artifactId    = extractNestedString(stage1Data, "artifact", "artifactId");
        String fixedVersion  = extractString(stage1Data, "fixedVersion");
        String affectedTo    = extractNestedString(stage1Data, "affectedVersions", "to");
        String description   = extractString(stage1Data, "description");
        List<String> knownCommits = extractCommits(stage1Data);
        List<String> articleUrls  = extractArticleUrls(stage1Data);
        String effectiveSourceRepo = sourceRepo;
        String effectiveGroupId = groupId;
        String effectiveArtifactId = artifactId;
        String effectiveAffectedTo = affectedTo;
        String effectiveFixed = (fixedVersion != null && !fixedVersion.isEmpty()) ? fixedVersion : null;

        log.info("Stage2: sourceRepo={} artifact={}:{} fixed={} commits={}",
                sourceRepo, groupId, artifactId, fixedVersion, knownCommits.size());
        // Phase 1: try commit-based strategies (reference-commit, ghsa-commit, cve-commit-search)
        for (LocateStrategy strategy : strategies) {
            if (strategy instanceof MavenSourceDiffStrategy) continue; // handled separately
            ctx.reportProgress("Trying strategy: " + strategy.name());
            try {
                Optional<LocateStrategy.PatchResult> result =
                        strategy.locate(cveId, sourceRepo, knownCommits);
                if (result.isPresent()) {
                    return buildSuccess(ctx, cveId, result.get(), strategy.name());
                }
            } catch (Exception e) {
                log.warn("Strategy {} failed: {}", strategy.name(), e.getMessage());
            }
        }

        // Phase 2: Maven source JAR diff — works without GitHub access.
        // If fixedVersion is unknown, infer it from the affected version range.
        if (effectiveFixed == null && artifactId != null && !artifactId.isEmpty()
                && affectedTo != null && !affectedTo.isEmpty()) {
            effectiveFixed = mavenStrategy.inferFixedVersion(groupId, artifactId, affectedTo);
            if (effectiveFixed != null) {
                log.info("Stage2: inferred fixedVersion={} from affectedTo='{}'", effectiveFixed, affectedTo);
            }
        }
        if (artifactId != null && !artifactId.isEmpty() && effectiveFixed != null) {
            ctx.reportProgress("Trying strategy: maven-source-diff");
            try {
                Optional<LocateStrategy.PatchResult> result =
                        mavenStrategy.locateByArtifact(cveId, groupId, artifactId, effectiveFixed);
                if (result.isPresent()) {
                    return buildSuccess(ctx, cveId, result.get(), "maven-source-diff");
                }
            } catch (Exception e) {
                log.warn("MavenSourceDiff failed: {}", e.getMessage());
            }
        }

        // Phase 3: AI-guided search — LLM reasons about the CVE and produces new leads
        // (commit keywords, version, release tag) which are fed back into existing strategies.
        ctx.reportProgress("Trying strategy: ai-patch-search");
        try {
            Optional<AiPatchOutcome> result = aiStrategy.locateWithAiHints(
                    cveId, sourceRepo, groupId, artifactId,
                    description, affectedTo, effectiveFixed, articleUrls);
            if (result.isPresent()) {
                AiPatchOutcome outcome = result.get();
                AiEnrichment enrichment = outcome.getEnrichment();
                if (enrichment != null) {
                    effectiveSourceRepo = firstNonBlank(enrichment.getSourceRepo(), effectiveSourceRepo);
                    effectiveGroupId = firstNonBlank(enrichment.getGroupId(), effectiveGroupId);
                    effectiveArtifactId = firstNonBlank(enrichment.getArtifactId(), effectiveArtifactId);
                    effectiveAffectedTo = firstNonBlank(enrichment.getAffectedTo(), effectiveAffectedTo);
                    effectiveFixed = firstNonBlank(enrichment.getFixedVersion(), effectiveFixed);
                    log.info("Stage2: AI enrichment applied sourceRepo={} artifact={}:{} affectedTo={} fixedVersion={}",
                            effectiveSourceRepo, effectiveGroupId, effectiveArtifactId, effectiveAffectedTo, effectiveFixed);
                }
                if (outcome.getPatchResult() != null) {
                    return buildSuccess(ctx, cveId, outcome.getPatchResult(), "ai-patch-search");
                }
                LocateStrategy.PatchResult retryPatch = retryWithEnrichment(
                        cveId, effectiveSourceRepo, effectiveGroupId, effectiveArtifactId,
                        effectiveAffectedTo, effectiveFixed, knownCommits, ctx);
                if (retryPatch != null) {
                    return buildSuccess(ctx, cveId, retryPatch, "ai-patch-search");
                }
            }
        } catch (Exception e) {
            log.warn("AiPatchSearch failed: {}", e.getMessage());
        }

        return StageResult.failure(2, name(),
                "No fix commit found with any strategy (tried: reference-commit, ghsa-commit, cve-commit-search, maven-source-diff, ai-patch-search)");
    }

    private LocateStrategy.PatchResult retryWithEnrichment(String cveId, String sourceRepo,
                                                           String groupId, String artifactId,
                                                           String affectedTo, String fixedVersion,
                                                           List<String> knownCommits,
                                                           PipelineContext ctx) throws Exception {
        if (sourceRepo != null && !sourceRepo.isEmpty()) {
            for (LocateStrategy strategy : strategies) {
                if (strategy instanceof MavenSourceDiffStrategy) continue;
                ctx.reportProgress("Retrying with AI enrichment: " + strategy.name());
                try {
                    Optional<LocateStrategy.PatchResult> result =
                            strategy.locate(cveId, sourceRepo, knownCommits);
                    if (result.isPresent()) {
                        return result.get();
                    }
                } catch (Exception e) {
                    log.warn("AI-enriched retry {} failed: {}", strategy.name(), e.getMessage());
                }
            }
        }

        String effectiveFixed = fixedVersion;
        if ((effectiveFixed == null || effectiveFixed.isEmpty())
                && artifactId != null && !artifactId.isEmpty()
                && affectedTo != null && !affectedTo.isEmpty()) {
            effectiveFixed = mavenStrategy.inferFixedVersion(groupId, artifactId, affectedTo);
            if (effectiveFixed != null) {
                log.info("Stage2: AI-enriched inferred fixedVersion={} from affectedTo='{}'",
                        effectiveFixed, affectedTo);
            }
        }

        if (artifactId != null && !artifactId.isEmpty()
                && effectiveFixed != null && !effectiveFixed.isEmpty()) {
            ctx.reportProgress("Retrying with AI enrichment: maven-source-diff");
            try {
                Optional<LocateStrategy.PatchResult> result =
                        mavenStrategy.locateByArtifact(cveId, groupId, artifactId, effectiveFixed);
                if (result.isPresent()) {
                    return result.get();
                }
            } catch (Exception e) {
                log.warn("AI-enriched retry maven-source-diff failed: {}", e.getMessage());
            }
        }

        return null;
    }

    private StageResult buildSuccess(PipelineContext ctx, String cveId,
                                      LocateStrategy.PatchResult pr, String strategyName) throws Exception {
        List<PatchInfo.FileDiff> diffs = DiffParser.parse(pr.getRawDiff());
        PatchInfo.PatchEvidence evidence = PatchEvidenceExtractor.extract(diffs, pr.getRawDiff());
        PatchInfo patchInfo = new PatchInfo(pr.getCommitHash(), pr.getCommitMessage(), "",
                diffs, pr.getRawDiff(), strategyName, evidence);
        ctx.getWorkspaceManager().writeStageData(cveId, 2, patchInfo);
        ctx.getWorkspaceManager().writeDiff(cveId, pr.getRawDiff());
        ctx.reportProgress("Found " + diffs.size() + " Java files changed via " + strategyName);
        log.info("Patch found via {}: {} ({} Java files, evidence={})",
                strategyName, pr.getCommitHash(), diffs.size(),
                evidence != null ? evidence.getPrimaryCategory() : "unknown");
        return StageResult.success(2, name(), patchInfo);
    }

    // ── Helpers for deserializing Stage 1 data whether it's CveIntelligence or a Map ──

    private String extractString(Object data, String field) {
        if (data instanceof CveIntelligence) {
            CveIntelligence intel = (CveIntelligence) data;
            if ("sourceRepo".equals(field))   return intel.getSourceRepo();
            if ("fixedVersion".equals(field)) return intel.getFixedVersion();
            if ("description".equals(field))  return intel.getDescription();
        }
        try {
            JsonNode node = mapper.valueToTree(data);
            return node.path(field).asText(null);
        } catch (Exception e) { return null; }
    }

    private String extractNestedString(Object data, String parent, String field) {
        if (data instanceof CveIntelligence) {
            CveIntelligence intel = (CveIntelligence) data;
            if ("artifact".equals(parent) && intel.getArtifact() != null) {
                if ("groupId".equals(field))    return intel.getArtifact().getGroupId();
                if ("artifactId".equals(field)) return intel.getArtifact().getArtifactId();
            }
        }
        try {
            JsonNode node = mapper.valueToTree(data);
            return node.path(parent).path(field).asText(null);
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractCommits(Object data) {
        if (data instanceof CveIntelligence) {
            List<String> c = ((CveIntelligence) data).getFixCommits();
            return c != null ? c : Collections.emptyList();
        }
        try {
            JsonNode node = mapper.valueToTree(data);
            JsonNode commits = node.path("fixCommits");
            List<String> result = new ArrayList<>();
            if (commits.isArray()) for (JsonNode c : commits) result.add(c.asText());
            return result;
        } catch (Exception e) { return Collections.emptyList(); }
    }

    private List<String> extractArticleUrls(Object data) {
        if (data instanceof CveIntelligence) {
            List<CveIntelligence.Article> articles = ((CveIntelligence) data).getArticles();
            if (articles == null) return Collections.emptyList();
            List<String> urls = new ArrayList<>();
            for (CveIntelligence.Article a : articles) {
                if (a.getUrl() != null && !a.getUrl().isEmpty()) urls.add(a.getUrl());
            }
            return urls;
        }
        try {
            JsonNode node = mapper.valueToTree(data);
            JsonNode articles = node.path("articles");
            List<String> result = new ArrayList<>();
            if (articles.isArray()) {
                for (JsonNode a : articles) {
                    String url = a.path("url").asText("");
                    if (!url.isEmpty()) result.add(url);
                }
            }
            return result;
        } catch (Exception e) { return Collections.emptyList(); }
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.trim().isEmpty() ? preferred.trim() : fallback;
    }
}

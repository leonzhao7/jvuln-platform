package com.jvuln.patcher;

import com.jvuln.store.model.CveIntelligence;
import com.jvuln.patcher.strategy.LocateStrategy;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class PatchLocateStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(PatchLocateStage.class);
    private final List<LocateStrategy> strategies;

    public PatchLocateStage(List<LocateStrategy> strategies) {
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

        CveIntelligence intel = (CveIntelligence) stage1.getData();
        String sourceRepo = intel.getSourceRepo();
        List<String> knownCommits = intel.getFixCommits();

        for (LocateStrategy strategy : strategies) {
            ctx.reportProgress("Trying strategy: " + strategy.name());
            try {
                Optional<LocateStrategy.PatchResult> result =
                        strategy.locate(cveId, sourceRepo, knownCommits);

                if (result.isPresent()) {
                    LocateStrategy.PatchResult pr = result.get();
                    log.info("Patch found via {}: {}", strategy.name(), pr.getCommitHash());

                    List<PatchInfo.FileDiff> diffs = DiffParser.parse(pr.getRawDiff());
                    PatchInfo patchInfo = new PatchInfo(
                            pr.getCommitHash(), pr.getCommitMessage(), "",
                            diffs, strategy.name());

                    ctx.getWorkspaceManager().writeStageData(cveId, 2, patchInfo);
                    ctx.getWorkspaceManager().writeDiff(cveId, pr.getRawDiff());

                    ctx.reportProgress("Found " + diffs.size() + " Java files changed via " + strategy.name());
                    return StageResult.success(2, name(), patchInfo);
                }
            } catch (Exception e) {
                log.warn("Strategy {} failed: {}", strategy.name(), e.getMessage());
            }
        }

        return StageResult.failure(2, name(), "No fix commit found with any strategy");
    }
}

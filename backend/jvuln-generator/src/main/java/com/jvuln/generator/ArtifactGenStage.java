package com.jvuln.generator;

import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ArtifactGenStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(ArtifactGenStage.class);

    @Override
    public int number() { return 5; }

    @Override
    public String name() { return "Artifact Generation"; }

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        ctx.reportProgress("Artifact generation - placeholder for demo project + PoC + report generation");
        // TODO: Phase 3 — Spring Boot demo project gen, PoC gen, compile verify, report gen
        Map<String, Object> placeholder = new HashMap<>();
        placeholder.put("status", "placeholder");
        placeholder.put("message", "Artifact generation will be implemented in Phase 3");
        ctx.getWorkspaceManager().writeStageData(ctx.getCveId(), 5, placeholder);
        return StageResult.success(5, name(), placeholder);
    }
}

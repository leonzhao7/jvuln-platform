package com.jvuln.pipeline.stage;

import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;

public interface Stage {
    int number();
    String name();
    StageResult execute(PipelineContext ctx) throws Exception;
}

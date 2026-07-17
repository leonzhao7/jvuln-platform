package com.jvuln.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConstantsTest {

    @Test
    void fiveStagesWithReportLast() {
        assertEquals(5, PipelineConstants.TOTAL_STAGES);
        assertEquals(5, PipelineConstants.STAGE_REPORT);
        assertTrue(PipelineConstants.isValidStage(5));
        assertEquals("Report Generation", PipelineConstants.getStageName(5));
    }
}

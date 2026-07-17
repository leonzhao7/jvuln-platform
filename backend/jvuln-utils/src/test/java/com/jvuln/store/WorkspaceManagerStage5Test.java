package com.jvuln.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManagerStage5Test {

    private static final String CVE = "CVE-2099-0001";

    @Test
    void writesAndReadsStage5Data(@TempDir Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WorkspaceManager workspace = new WorkspaceManager(tempDir.toString(), mapper);
        workspace.initCveWorkspace(CVE);

        assertFalse(workspace.isStageComplete(CVE, 5));

        Map<String, Object> summary = new HashMap<>();
        summary.put("reportPath", "report/report.md");
        summary.put("charCount", 123);
        workspace.writeStageData(CVE, 5, summary);

        assertTrue(workspace.isStageComplete(CVE, 5));

        @SuppressWarnings("unchecked")
        Map<String, Object> read = workspace.readStageData(CVE, 5, Map.class);
        assertEquals("report/report.md", read.get("reportPath"));
    }
}

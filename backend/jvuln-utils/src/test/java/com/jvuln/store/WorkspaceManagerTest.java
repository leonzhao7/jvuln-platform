package com.jvuln.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesTheCompleteCveWorkspace() throws Exception {
        WorkspaceManager manager = new WorkspaceManager(tempDir.toString(), new ObjectMapper());
        Path cvePath = manager.initCveWorkspace("CVE-2026-1234");
        Files.write(cvePath.resolve("report/report.md"), "report".getBytes("UTF-8"));

        manager.deleteCveWorkspace("CVE-2026-1234");

        assertFalse(Files.exists(cvePath));
    }
}

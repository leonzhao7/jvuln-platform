package com.jvuln.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.pipeline.PipelineEngine;
import com.jvuln.service.AnalysisQueryService;
import com.jvuln.service.AnalysisStatusSyncService;
import com.jvuln.store.CveTaskRepository;
import com.jvuln.store.StageRecordRepository;
import com.jvuln.store.WorkspaceManager;
import com.jvuln.store.entity.CveTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisControllerTest {

    private static final String CVE = "CVE-2026-1234";

    @TempDir
    Path tempDir;

    @Test
    void deleteAnalysisRemovesDatabaseTaskAndWorkspace() throws Exception {
        Fixture fixture = fixture(false);
        Path workspace = fixture.workspaceManager.initCveWorkspace(CVE);
        Files.write(workspace.resolve("report/report.md"), "report".getBytes("UTF-8"));

        ResponseEntity<?> response = fixture.controller.deleteAnalysis(CVE);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertFalse(Files.exists(workspace));
        verify(fixture.taskRepo).delete(fixture.task);
        verify(fixture.pipelineEngine).clearProgress(CVE);
    }

    @Test
    void deleteAnalysisRejectsRunningTaskAndKeepsWorkspace() throws Exception {
        Fixture fixture = fixture(true);
        Path workspace = fixture.workspaceManager.initCveWorkspace(CVE);

        ResponseEntity<?> response = fixture.controller.deleteAnalysis(CVE);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(Files.exists(workspace));
        verify(fixture.taskRepo, never()).delete(fixture.task);
    }

    private Fixture fixture(boolean running) {
        PipelineEngine pipelineEngine = mock(PipelineEngine.class);
        CveTaskRepository taskRepo = mock(CveTaskRepository.class);
        StageRecordRepository stageRepo = mock(StageRecordRepository.class);
        AnalysisQueryService queryService = mock(AnalysisQueryService.class);
        AnalysisStatusSyncService syncService = mock(AnalysisStatusSyncService.class);
        WorkspaceManager workspaceManager = new WorkspaceManager(
                tempDir.toString(), new ObjectMapper());
        CveTask task = new CveTask();
        task.setCveId(CVE);
        when(taskRepo.findByCveId(CVE)).thenReturn(Optional.of(task));
        when(stageRepo.findByCveIdOrderByStageNum(CVE)).thenReturn(Collections.emptyList());
        when(pipelineEngine.isRunning(CVE)).thenReturn(running);
        AnalysisController controller = new AnalysisController(
                pipelineEngine, taskRepo, stageRepo, queryService, syncService, workspaceManager);
        return new Fixture(controller, pipelineEngine, taskRepo, workspaceManager, task);
    }

    private static class Fixture {
        private final AnalysisController controller;
        private final PipelineEngine pipelineEngine;
        private final CveTaskRepository taskRepo;
        private final WorkspaceManager workspaceManager;
        private final CveTask task;

        private Fixture(AnalysisController controller, PipelineEngine pipelineEngine,
                        CveTaskRepository taskRepo, WorkspaceManager workspaceManager,
                        CveTask task) {
            this.controller = controller;
            this.pipelineEngine = pipelineEngine;
            this.taskRepo = taskRepo;
            this.workspaceManager = workspaceManager;
            this.task = task;
        }
    }
}

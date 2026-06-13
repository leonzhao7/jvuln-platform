package com.jvuln.pipeline.model;

import com.jvuln.llm.LlmClient;
import com.jvuln.store.WorkspaceManager;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class PipelineContext {

    private final String cveId;
    private final Path workspacePath;
    private final LlmClient llmClient;
    private final WorkspaceManager workspaceManager;
    private final Map<Integer, StageResult> completedStages = new ConcurrentHashMap<>();
    private Consumer<StageProgress> progressCallback;
    private int fromStage = 1;

    public PipelineContext(String cveId, Path workspacePath, LlmClient llmClient,
                           WorkspaceManager workspaceManager) {
        this.cveId = cveId;
        this.workspacePath = workspacePath;
        this.llmClient = llmClient;
        this.workspaceManager = workspaceManager;
    }

    public String getCveId() { return cveId; }
    public Path getWorkspacePath() { return workspacePath; }
    public LlmClient getLlmClient() { return llmClient; }
    public WorkspaceManager getWorkspaceManager() { return workspaceManager; }
    public Map<Integer, StageResult> getCompletedStages() { return completedStages; }

    public void setProgressCallback(Consumer<StageProgress> callback) {
        this.progressCallback = callback;
    }

    public void reportProgress(String message) {
        if (progressCallback != null) {
            progressCallback.accept(new StageProgress("progress", 0, message));
        }
    }

    public int getFromStage() { return fromStage; }
    public void setFromStage(int fromStage) { this.fromStage = fromStage; }
}

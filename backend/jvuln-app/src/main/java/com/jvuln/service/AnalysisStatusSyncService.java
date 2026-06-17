package com.jvuln.service;

import com.jvuln.pipeline.PipelineConstants;
import com.jvuln.store.StageRecordRepository;
import com.jvuln.store.WorkspaceManager;
import com.jvuln.store.entity.CveTask;
import com.jvuln.store.entity.StageRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AnalysisStatusSyncService {

    private final StageRecordRepository stageRecordRepository;
    private final WorkspaceManager workspaceManager;

    public AnalysisStatusSyncService(StageRecordRepository stageRecordRepository,
                                     WorkspaceManager workspaceManager) {
        this.stageRecordRepository = stageRecordRepository;
        this.workspaceManager = workspaceManager;
    }

    public Map<String, Object> sync(String cveId, CveTask task) {
        int maxCompleted = 0;
        int currentStage = 0;
        boolean failed = false;

        for (int stageNum = PipelineConstants.FIRST_STAGE;
             stageNum <= PipelineConstants.TOTAL_STAGES;
             stageNum++) {
            StageFileStatus diskStatus = inspectStageFileStatus(cveId, stageNum);
            if (!diskStatus.exists) {
                continue;
            }

            currentStage = stageNum;
            updateStageRecord(cveId, stageNum, diskStatus);
            if (diskStatus.status == StageRecord.StageStatus.COMPLETED) {
                maxCompleted = stageNum;
            } else if (diskStatus.status == StageRecord.StageStatus.FAILED) {
                failed = true;
            }
        }

        task.setCurrentStage(currentStage > 0 ? currentStage : maxCompleted);
        if (failed) {
            task.setStatus(CveTask.TaskStatus.FAILED);
        } else if (maxCompleted == PipelineConstants.TOTAL_STAGES) {
            task.setStatus(CveTask.TaskStatus.COMPLETED);
        } else {
            task.setStatus(CveTask.TaskStatus.PENDING);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cveId", cveId);
        result.put("syncedToStage", maxCompleted);
        result.put("currentStage", task.getCurrentStage());
        result.put("status", task.getStatus());
        return result;
    }

    private void updateStageRecord(String cveId, int stageNum, StageFileStatus diskStatus) {
        stageRecordRepository.findByCveIdAndStageNum(cveId, stageNum).ifPresent(record -> {
            record.setStatus(diskStatus.status);
            record.setErrorMsg(diskStatus.error);
            stageRecordRepository.save(record);
        });
    }

    @SuppressWarnings("unchecked")
    private StageFileStatus inspectStageFileStatus(String cveId, int stageNum) {
        if (!workspaceManager.isStageComplete(cveId, stageNum)) {
            return StageFileStatus.missing();
        }
        if (stageNum != PipelineConstants.STAGE_ARTIFACTS) {
            return StageFileStatus.completed();
        }
        try {
            Object data = workspaceManager.readStageData(cveId, stageNum, Object.class);
            if (!(data instanceof Map)) {
                return StageFileStatus.completed();
            }
            Map<String, Object> stage = (Map<String, Object>) data;
            String status = String.valueOf(stage.getOrDefault("status", ""));
            String failureReason = String.valueOf(stage.getOrDefault("failureReason", "")).trim();
            if ("paused".equals(status)) {
                String pauseReason = String.valueOf(stage.getOrDefault("pauseReason", "")).trim();
                return StageFileStatus.failed("Agent paused"
                        + (pauseReason.isEmpty() || "null".equals(pauseReason) ? "." : ": " + pauseReason));
            }
            if (!failureReason.isEmpty() && !"null".equals(failureReason)) {
                return StageFileStatus.failed(failureReason);
            }
            Object pocObject = stage.get("poc");
            if (pocObject instanceof Map) {
                Map<String, Object> poc = (Map<String, Object>) pocObject;
                String pocStatus = String.valueOf(poc.getOrDefault("status", ""));
                if ("unverified".equals(pocStatus)) {
                    String reason = String.valueOf(poc.getOrDefault("reason", "")).trim();
                    return StageFileStatus.failed("PoC verification failed"
                            + (reason.isEmpty() || "null".equals(reason) ? "." : ": " + reason));
                }
            }
            return StageFileStatus.completed();
        } catch (IOException e) {
            return StageFileStatus.failed("Could not read Stage 4 data: " + e.getMessage());
        }
    }

    private static final class StageFileStatus {
        private final boolean exists;
        private final StageRecord.StageStatus status;
        private final String error;

        private StageFileStatus(boolean exists, StageRecord.StageStatus status, String error) {
            this.exists = exists;
            this.status = status;
            this.error = error;
        }

        private static StageFileStatus missing() {
            return new StageFileStatus(false, StageRecord.StageStatus.PENDING, null);
        }

        private static StageFileStatus completed() {
            return new StageFileStatus(true, StageRecord.StageStatus.COMPLETED, null);
        }

        private static StageFileStatus failed(String error) {
            return new StageFileStatus(true, StageRecord.StageStatus.FAILED, error);
        }
    }
}

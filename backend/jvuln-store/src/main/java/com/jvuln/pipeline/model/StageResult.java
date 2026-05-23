package com.jvuln.pipeline.model;

public class StageResult {

    private final int stageNum;
    private final String stageName;
    private final boolean success;
    private final Object data;
    private final String errorMessage;

    private StageResult(int stageNum, String stageName, boolean success,
                        Object data, String errorMessage) {
        this.stageNum = stageNum;
        this.stageName = stageName;
        this.success = success;
        this.data = data;
        this.errorMessage = errorMessage;
    }

    public static StageResult success(int num, String name, Object data) {
        return new StageResult(num, name, true, data, null);
    }

    public static StageResult failure(int num, String name, String error) {
        return new StageResult(num, name, false, null, error);
    }

    public int getStageNum() { return stageNum; }
    public String getStageName() { return stageName; }
    public boolean isSuccess() { return success; }
    public Object getData() { return data; }
    public String getErrorMessage() { return errorMessage; }
}

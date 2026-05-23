package com.jvuln.pipeline.model;

public class StageProgress {
    private final String type;
    private final int stageNum;
    private final String message;

    public StageProgress(String type, int stageNum, String message) {
        this.type = type;
        this.stageNum = stageNum;
        this.message = message;
    }

    public String getType() { return type; }
    public int getStageNum() { return stageNum; }
    public String getMessage() { return message; }
}

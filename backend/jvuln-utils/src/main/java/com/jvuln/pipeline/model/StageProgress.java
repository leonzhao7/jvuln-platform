package com.jvuln.pipeline.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class StageProgress {
    private final String type;
    private final int stageNum;
    private final String message;
    private final long timestamp;

    public StageProgress(String type, int stageNum, String message) {
        this.type = type;
        this.stageNum = stageNum;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    @JsonCreator
    public StageProgress(@JsonProperty("type") String type,
                         @JsonProperty("stageNum") int stageNum,
                         @JsonProperty("message") String message,
                         @JsonProperty("timestamp") long timestamp) {
        this.type = type;
        this.stageNum = stageNum;
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getType() { return type; }
    public int getStageNum() { return stageNum; }
    public String getMessage() { return message; }
    public long getTimestamp() { return timestamp; }
}

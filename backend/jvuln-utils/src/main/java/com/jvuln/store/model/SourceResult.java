package com.jvuln.store.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import static com.jvuln.util.ValueUtils.text;

public class SourceResult {

    public enum Source { NVD, GHSA, OSV }
    public enum Status { SUCCESS, NOT_FOUND, FAILED, TIMED_OUT }

    private final Source source;
    private final Status status;
    private final long durationMs;
    private final String errorCode;
    private final String errorMessage;
    private final String originalDescription;
    private final SourceData parsedData;
    private final String rawPayload;

    @JsonCreator
    public SourceResult(
            @JsonProperty("source") Source source,
            @JsonProperty("status") Status status,
            @JsonProperty("durationMs") long durationMs,
            @JsonProperty("errorCode") String errorCode,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("originalDescription") String originalDescription,
            @JsonProperty("parsedData") SourceData parsedData,
            @JsonProperty("rawPayload") String rawPayload) {
        this.source = source;
        this.status = status;
        this.durationMs = Math.max(0L, durationMs);
        this.errorCode = text(errorCode);
        this.errorMessage = text(errorMessage);
        this.originalDescription = text(originalDescription);
        this.parsedData = parsedData == null ? SourceData.empty() : parsedData;
        this.rawPayload = text(rawPayload);
    }

    public Source getSource() { return source; }
    public Status getStatus() { return status; }
    public long getDurationMs() { return durationMs; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getOriginalDescription() { return originalDescription; }
    public SourceData getParsedData() { return parsedData; }
    public String getRawPayload() { return rawPayload; }
    @JsonIgnore
    public boolean isSuccess() { return status == Status.SUCCESS; }

}

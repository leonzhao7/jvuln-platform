package com.jvuln.store.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

import static com.jvuln.util.ValueUtils.immutableList;
import static com.jvuln.util.ValueUtils.text;

public class DescriptionAdjudication {

    public enum Status { NOT_RUN, SUCCESS, FAILED }
    public enum Verdict { RESOLVED, INSUFFICIENT_EVIDENCE }

    private final Status status;
    private final Verdict verdict;
    private final boolean corrected;
    private final String finalDescription;
    private final String reason;
    private final List<String> conflictingClaims;
    private final List<String> evidenceCitations;
    private final BigDecimal confidence;
    private final String errorMessage;

    @JsonCreator
    public DescriptionAdjudication(
            @JsonProperty("status") Status status,
            @JsonProperty("verdict") Verdict verdict,
            @JsonProperty("corrected") boolean corrected,
            @JsonProperty("finalDescription") String finalDescription,
            @JsonProperty("reason") String reason,
            @JsonProperty("conflictingClaims") List<String> conflictingClaims,
            @JsonProperty("evidenceCitations") List<String> evidenceCitations,
            @JsonProperty("confidence") BigDecimal confidence,
            @JsonProperty("errorMessage") String errorMessage) {
        this.status = status == null ? Status.NOT_RUN : status;
        this.verdict = verdict;
        this.corrected = corrected;
        this.finalDescription = text(finalDescription);
        this.reason = text(reason);
        this.conflictingClaims = immutableList(conflictingClaims);
        this.evidenceCitations = immutableList(evidenceCitations);
        this.confidence = confidence == null ? BigDecimal.ZERO : confidence;
        this.errorMessage = text(errorMessage);
    }

    public static DescriptionAdjudication notRun(String errorMessage) {
        return new DescriptionAdjudication(Status.NOT_RUN, null, false, "", "",
                null, null, BigDecimal.ZERO, errorMessage);
    }

    public static DescriptionAdjudication failed(String errorMessage) {
        return new DescriptionAdjudication(Status.FAILED, null, false, "", "",
                null, null, BigDecimal.ZERO, errorMessage);
    }

    public static DescriptionAdjudication resolved(
            boolean corrected, String finalDescription, String reason,
            List<String> conflictingClaims, List<String> evidenceCitations,
            BigDecimal confidence) {
        return new DescriptionAdjudication(Status.SUCCESS, Verdict.RESOLVED, corrected,
                finalDescription, reason, conflictingClaims, evidenceCitations, confidence, "");
    }

    public static DescriptionAdjudication insufficient(
            String reason, List<String> conflictingClaims,
            List<String> evidenceCitations, BigDecimal confidence) {
        return new DescriptionAdjudication(Status.SUCCESS, Verdict.INSUFFICIENT_EVIDENCE,
                false, "", reason, conflictingClaims, evidenceCitations, confidence, "");
    }

    public Status getStatus() { return status; }
    public Verdict getVerdict() { return verdict; }
    public boolean isCorrected() { return corrected; }
    public String getFinalDescription() { return finalDescription; }
    public String getReason() { return reason; }
    public List<String> getConflictingClaims() { return conflictingClaims; }
    public List<String> getEvidenceCitations() { return evidenceCitations; }
    public BigDecimal getConfidence() { return confidence; }
    public String getErrorMessage() { return errorMessage; }

    @JsonIgnore
    public boolean isResolved() {
        return status == Status.SUCCESS && verdict == Verdict.RESOLVED
                && !finalDescription.trim().isEmpty();
    }

}

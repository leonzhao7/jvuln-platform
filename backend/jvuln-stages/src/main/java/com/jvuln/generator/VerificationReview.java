package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.jvuln.generator.ArtifactGenUtils.*;

class VerificationReview {
    final String verdict;
    final String pocStatus;
    final String reason;
    final List<String> matchedSignals;
    final List<String> missingEvidence;
    final List<String> falsePositiveRisks;
    final List<String> nextActions;

    private VerificationReview(String verdict, String pocStatus, String reason,
                               List<String> matchedSignals, List<String> missingEvidence,
                               List<String> falsePositiveRisks, List<String> nextActions) {
        this.verdict = normalizeVerdict(verdict);
        this.pocStatus = normalizePocStatus(pocStatus, this.verdict);
        this.reason = reason == null ? "" : reason.trim();
        this.matchedSignals = normalizeList(matchedSignals);
        this.missingEvidence = normalizeList(missingEvidence);
        this.falsePositiveRisks = normalizeList(falsePositiveRisks);
        this.nextActions = normalizeList(nextActions);
    }

    static VerificationReview fromJson(JsonNode node) {
        return new VerificationReview(
                node.path("verdict").asText("inconclusive"),
                node.path("pocStatus").asText(""),
                node.path("reason").asText(""),
                readList(node.path("matchedSignals")),
                readList(node.path("missingEvidence")),
                readList(node.path("falsePositiveRisks")),
                readList(node.path("nextActions"))
        );
    }

    static VerificationReview fallback(JsonNode finishSummary, String compileStatus, String startupStatus,
                                       ValidationResult validation) {
        String summaryStatus = finishSummary == null ? "" : finishSummary.path("poc_status").asText("");
        String reason = "";
        if (finishSummary != null) {
            reason = finishSummary.path("remaining_gap").asText("").trim();
            if (reason.isEmpty()) {
                reason = finishSummary.path("verification_evidence").asText("").trim();
            }
            if (reason.isEmpty()) {
                reason = finishSummary.path("notes").asText("").trim();
            }
        }
        boolean backendVerified = validation != null && validation.compileOk
                && validation.startupOk && validation.pocVerified;
        if (validation != null && validation.pocMessage != null && !validation.pocMessage.trim().isEmpty()) {
            reason = validation.pocMessage.trim();
        } else if (backendVerified && reason.isEmpty()) {
            reason = validation.summary();
        }

        List<String> nextActions = new ArrayList<>();
        if (validation != null && !validation.compileOk) {
            nextActions.add("Fix the latest build failure before claiming verification.");
        } else if (validation != null && !validation.startupOk) {
            nextActions.add("Fix the latest startup failure and confirm the vulnerable endpoint is reachable.");
        } else if (validation != null && !validation.pocVerified) {
            nextActions.add("Adjust the demo or PoC to satisfy the backend validator's CVE-specific success criteria.");
        } else if ("compile_failed".equals(compileStatus)) {
            nextActions.add("Fix the latest build failure before claiming verification.");
        } else if ("startup_failed".equals(startupStatus)) {
            nextActions.add("Fix the latest startup failure and confirm the vulnerable endpoint is reachable.");
        } else if (!"verified".equals(summaryStatus) && !"skipped".equals(summaryStatus)) {
            nextActions.add("Inspect the latest PoC output and adjust the demo or PoC to reach the real vulnerable path.");
        }

        if (backendVerified || (validation == null && "verified".equals(summaryStatus))) {
            if (reason.isEmpty()) {
                reason = backendVerified
                        ? "Backend validation verified the CVE-specific PoC success criteria."
                        : "The agent reported verified with concrete execution evidence.";
            }
            return new VerificationReview("verified", "verified", reason,
                    Collections.singletonList(reason), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Collections.<String>emptyList());
        }
        if ("skipped".equals(summaryStatus)) {
            if (reason.isEmpty()) {
                reason = "The agent skipped PoC verification.";
            }
            return new VerificationReview("skipped", "skipped", reason,
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Collections.<String>emptyList());
        }

        if (reason.isEmpty()) {
            reason = "The reviewer fallback could not confirm that the observed behavior satisfies the CVE-specific success criteria.";
        }
        return new VerificationReview("unverified", "unverified", reason,
                Collections.<String>emptyList(), Collections.singletonList(reason),
                Collections.singletonList("Fallback review used because structured reviewer output was unavailable."),
                nextActions);
    }

    boolean requiresRevision() {
        return "unverified".equals(pocStatus) && !nextActions.isEmpty();
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", verdict);
        out.put("pocStatus", pocStatus);
        out.put("reason", reason);
        out.put("matchedSignals", matchedSignals);
        out.put("missingEvidence", missingEvidence);
        out.put("falsePositiveRisks", falsePositiveRisks);
        out.put("nextActions", nextActions);
        return out;
    }

    static String normalizeVerdict(String verdict) {
        String value = verdict == null ? "" : verdict.trim().toLowerCase(Locale.ROOT);
        if ("verified".equals(value) || "unverified".equals(value) || "skipped".equals(value)) {
            return value;
        }
        return "inconclusive";
    }

    static String normalizePocStatus(String pocStatus, String verdict) {
        String value = pocStatus == null ? "" : pocStatus.trim().toLowerCase(Locale.ROOT);
        if ("verified".equals(value) || "unverified".equals(value) || "skipped".equals(value)) {
            return value;
        }
        if ("verified".equals(verdict) || "skipped".equals(verdict)) {
            return verdict;
        }
        return "unverified";
    }
}

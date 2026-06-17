package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jvuln.generator.ArtifactGenUtils.*;

class VerificationPlan {
    final String objective;
    final List<String> successSignals;
    final List<String> requiredEvidence;
    final List<String> falsePositiveGuards;
    final List<String> suggestedChecks;

    private VerificationPlan(String objective, List<String> successSignals, List<String> requiredEvidence,
                         List<String> falsePositiveGuards, List<String> suggestedChecks) {
        this.objective = objective == null ? "" : objective.trim();
        this.successSignals = normalizeList(successSignals);
        this.requiredEvidence = normalizeList(requiredEvidence);
        this.falsePositiveGuards = normalizeList(falsePositiveGuards);
        this.suggestedChecks = normalizeList(suggestedChecks);
        }

    static VerificationPlan fromJson(JsonNode node) {
        return new VerificationPlan(
                node.path("objective").asText(""),
                readList(node.path("successSignals")),
                readList(node.path("requiredEvidence")),
                readList(node.path("falsePositiveGuards")),
                readList(node.path("suggestedChecks"))
        );
        }

    static VerificationPlan fallback(String artifact, String triggerChain, String rootCause) {
        List<String> signals = new ArrayList<>();
        signals.add("The vulnerable library or component path is reachable in the generated demo.");
        signals.add("Running the PoC produces an observable effect that matches the CVE trigger chain.");
        List<String> evidence = new ArrayList<>();
        evidence.add("Successful build output for vuln-demo.");
        evidence.add("Successful startup or clear evidence that the vulnerable endpoint is listening.");
        evidence.add("PoC output and/or application logs that show the real vulnerable path was exercised.");
        List<String> guards = new ArrayList<>();
        guards.add("HTTP 200 or 500 alone is not sufficient without CVE-specific side effects.");
        guards.add("Normal business endpoint responses are not proof unless tied to the vulnerable path.");
        List<String> checks = new ArrayList<>();
        checks.add("Compare PoC behavior against the patch diff and root cause before claiming success.");
        checks.add("Prefer side effects that align with the CVE trigger chain.");
        StringBuilder objective = new StringBuilder("Verify the generated lab for ");
        objective.append(artifact == null || artifact.trim().isEmpty() ? "the target artifact" : artifact.trim());
        if (triggerChain != null && !"{}".equals(triggerChain.trim())) {
            objective.append(" by reproducing the trigger chain.");
        } else if (rootCause != null && !"{}".equals(rootCause.trim())) {
            objective.append(" by demonstrating the vulnerable behavior described in the root cause.");
        } else {
            objective.append(" by demonstrating the real vulnerable path.");
        }
        return new VerificationPlan(objective.toString(), signals, evidence, guards, checks);
        }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("objective", objective);
        out.put("successSignals", successSignals);
        out.put("requiredEvidence", requiredEvidence);
        out.put("falsePositiveGuards", falsePositiveGuards);
        out.put("suggestedChecks", suggestedChecks);
        return out;
        }
    }

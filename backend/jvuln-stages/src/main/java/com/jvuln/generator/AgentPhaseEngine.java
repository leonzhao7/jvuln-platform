package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.pipeline.model.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.*;

import static com.jvuln.generator.ArtifactGenUtils.joinItems;
import static com.jvuln.generator.ArtifactGenUtils.singleLine;

@Component
class AgentPhaseEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentPhaseEngine.class);
    private static final int REPORT_FALLBACK_TURNS = 5;
    private static final int MAX_NO_PROGRESS_TURNS = 6;

    private final PromptRegistry promptRegistry;
    private final ObjectMapper mapper;
    private final LlmHelper llmHelper;

    AgentPhaseEngine(PromptRegistry promptRegistry, ObjectMapper mapper, LlmHelper llmHelper) {
        this.promptRegistry = promptRegistry;
        this.mapper = mapper;
        this.llmHelper = llmHelper;
    }

    AgentPhase inferPhase(AgentContext ctx) {
        if (ctx == null) {
            return AgentPhase.PLAN;
        }
        if (ctx.executionPlan == null) {
            return AgentPhase.PLAN;
        }
        if (ctx.summary != null) {
            return AgentPhase.FINISHED;
        }
        if (Files.exists(ctx.cvePath.resolve("report/report.md"))) {
            return AgentPhase.REPORT;
        }
        String compileStatus = ctx.deriveCompileStatus();
        if (!"compile_ok".equals(compileStatus)) {
            return ctx.writtenFiles.size() <= 3 ? AgentPhase.GENERATE_MINIMAL : AgentPhase.COMPILE_FIX;
        }
        String startupStatus = ctx.deriveStartupStatus();
        if (!"startup_ok".equals(startupStatus)) {
            return AgentPhase.STARTUP_FIX;
        }
        return AgentPhase.POC_FIX;
    }

    PhaseDirective currentDirective(AgentContext ctx) {
        if (ctx.lastDirective != null && ctx.lastDirective.phase == ctx.phase) {
            return ctx.lastDirective;
        }
        return buildPhaseDirective(ctx, ctx.lastValidation, false);
    }

    PhaseDirective buildPhaseDirective(AgentContext ctx, ValidationResult result, boolean forceReport) {
        AgentPhase phase = forceReport ? AgentPhase.REPORT : (ctx.phase == null ? inferPhase(ctx) : ctx.phase);
        List<String> allowed = allowedActions(phase);
        String gap;
        String expected;
        String actual;
        String fixHint;

        switch (phase) {
            case PLAN:
                gap = "execution_plan_missing";
                expected = "A concise execution plan with first batch files, validation sequence, and report strategy.";
                actual = "No accepted execution plan is stored for this run.";
                fixHint = "Call submit_plan, then immediately write the minimal runnable candidate.";
                break;
            case GENERATE_MINIMAL:
                gap = "minimal_candidate_missing";
                expected = "A minimal runnable candidate: core vuln-demo files plus poc/exploit.sh.";
                actual = "The workspace does not yet contain a compile-ready candidate.";
                fixHint = "Write pom.xml, key config/class files, and the first PoC batch in one write_files call.";
                break;
            case COMPILE_FIX:
                gap = "compile_failed";
                expected = "mvn package -DskipTests succeeds for vuln-demo.";
                actual = result == null ? "No compile result yet." : result.compileMessage;
                fixHint = deriveCompileFixHint(ctx, result);
                break;
            case STARTUP_FIX:
                gap = "startup_failed";
                expected = "The generated lab starts successfully on port 18080.";
                actual = result == null ? "No startup result yet." : result.startupMessage;
                fixHint = deriveStartupFixHint(ctx, result);
                break;
            case POC_FIX:
                gap = result == null ? "poc_unverified" : derivePocGap(result);
                expected = derivePocExpected(ctx);
                actual = result == null ? "No PoC validation evidence yet." : result.pocMessage;
                fixHint = derivePocFixHint(ctx, result);
                break;
            case REPORT:
                gap = result != null && result.compileOk && result.startupOk && result.pocVerified ? "ready_to_finish" : "report_and_finish";
                expected = result != null && result.compileOk && result.startupOk && result.pocVerified
                        ? "Summarize the verified evidence and call finish."
                        : "Write report/report.md describing the verified evidence or exact remaining gap, then call finish.";
                actual = result == null ? "Validation state unavailable." : result.summary();
                fixHint = "Do not continue debugging. Produce the report from current evidence and finish.";
                break;
            default:
                gap = "unknown";
                expected = "Finish the current stage safely.";
                actual = "";
                fixHint = "Use the smallest action consistent with the current validation state.";
                break;
        }

        PhaseDirective directive = new PhaseDirective(phase, gap, expected, actual, fixHint, allowed);
        ctx.lastDirective = directive;
        return directive;
    }

    String renderPhaseDirective(PhaseDirective directive) {
        StringBuilder sb = new StringBuilder();
        sb.append("BACKEND DIRECTIVE\n");
        sb.append(llmHelper.renderJson(directive.toMap())).append("\n");

        AgentPhase phase = directive.phase;
        if (phase == AgentPhase.COMPILE_FIX || phase == AgentPhase.STARTUP_FIX || phase == AgentPhase.POC_FIX) {
            sb.append("Diagnose the failure first: use read_file, read_log, inspect_runtime to understand the root cause. ");
            sb.append("Once you understand why it failed, make targeted changes to fix it. ");
            sb.append("Available actions: ").append(String.join(", ", directive.allowedNextActions)).append(".");
        } else {
            sb.append("Available actions: ").append(String.join(", ", directive.allowedNextActions)).append(".");
        }
        return sb.toString();
    }

    AgentPhase nextPhaseAfterValidation(AgentContext ctx, ValidationResult result, int remainingTurns) {
        if (remainingTurns <= REPORT_FALLBACK_TURNS) {
            return AgentPhase.REPORT;
        }
        if (result == null) {
            return inferPhase(ctx);
        }
        if (!result.compileOk) {
            return AgentPhase.COMPILE_FIX;
        }
        if (!result.startupOk) {
            return AgentPhase.STARTUP_FIX;
        }
        if (!result.pocVerified) {
            return AgentPhase.POC_FIX;
        }
        return AgentPhase.REPORT;
    }

    boolean isToolAllowed(AgentPhase phase, String toolName) {
        if (phase == null) {
            return "submit_plan".equals(toolName) || "read_file".equals(toolName) || "read_log".equals(toolName)
                    || "write_file".equals(toolName) || "write_files".equals(toolName);
        }
        switch (phase) {
            case PLAN:
                return "submit_plan".equals(toolName) || "read_file".equals(toolName) || "read_log".equals(toolName)
                        || "write_file".equals(toolName) || "write_files".equals(toolName);
            case GENERATE_MINIMAL:
                return "write_file".equals(toolName) || "write_files".equals(toolName)
                        || "read_file".equals(toolName) || "read_log".equals(toolName);
            case COMPILE_FIX:
            case STARTUP_FIX:
            case POC_FIX:
                return "write_file".equals(toolName) || "write_files".equals(toolName)
                        || "read_file".equals(toolName) || "read_log".equals(toolName) || "inspect_runtime".equals(toolName)
                        || "validate_artifacts".equals(toolName) || "finish".equals(toolName);
            case REPORT:
                return "write_file".equals(toolName) || "write_files".equals(toolName)
                        || "read_file".equals(toolName) || "read_log".equals(toolName) || "inspect_runtime".equals(toolName)
                        || "finish".equals(toolName);
            case FINISHED:
                return "finish".equals(toolName) || "read_file".equals(toolName) || "read_log".equals(toolName);
            default:
                return false;
        }
    }

    void updateProgressGuard(AgentContext ctx, boolean wroteFiles) {
        if (wroteFiles) {
            ctx.noProgressTurns = 0;
            ctx.lastProgressSignature = progressSignature(ctx);
            return;
        }

        String signature = progressSignature(ctx);
        if (signature.equals(ctx.lastProgressSignature)) {
            ctx.noProgressTurns++;
        } else {
            ctx.noProgressTurns = 1;
            ctx.lastProgressSignature = signature;
        }

        if (ctx.noProgressTurns >= MAX_NO_PROGRESS_TURNS) {
            String validation = ctx.lastValidation == null ? "none" : ctx.lastValidation.summary();
            String gap = ctx.lastDirective == null ? "" : ctx.lastDirective.gap;
            ctx.abortReason = "Stage 4 stopped after " + ctx.noProgressTurns
                    + " consecutive no-progress turns. Phase=" + ctx.phase
                    + ", validation=" + validation
                    + (gap == null || gap.isEmpty() ? "" : ", gap=" + gap)
                    + ". The agent inspected or revalidated without changing files.";
        }
    }

    String determineFailureReason(Map<String, Object> output) {
        Object vulnDemoObj = output.get("vulnDemo");
        if (vulnDemoObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> vulnDemo = (Map<String, Object>) vulnDemoObj;
            String compileStatus = String.valueOf(vulnDemo.getOrDefault("compileStatus", ""));
            if ("compile_failed".equals(compileStatus)) {
                String message = String.valueOf(vulnDemo.getOrDefault("compileMessage", ""));
                return "Vulnerability demo build failed"
                        + (message == null || message.trim().isEmpty() ? "." : ": " + message);
            }

            String startupStatus = String.valueOf(vulnDemo.getOrDefault("startupStatus", ""));
            if ("startup_failed".equals(startupStatus)) {
                String message = String.valueOf(vulnDemo.getOrDefault("startupMessage", ""));
                return "Vulnerability demo startup failed"
                        + (message == null || message.trim().isEmpty() ? "." : ": " + message);
            }
        }

        Object pocObj = output.get("poc");
        if (!(pocObj instanceof Map)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> poc = (Map<String, Object>) pocObj;
        String pocStatus = String.valueOf(poc.getOrDefault("status", ""));
        if (!"unverified".equals(pocStatus)) {
            return null;
        }

        String reason = String.valueOf(poc.getOrDefault("reason", "")).trim();
        if (!reason.isEmpty() && !"null".equals(reason)) {
            return "PoC verification failed: " + reason;
        }

        return "PoC verification failed: the reviewer could not confirm that the exploit reached the CVE-specific "
                + "success criteria with the observed evidence.";
    }

    boolean requiresExecutionPlan(String toolName) {
        return !("submit_plan".equals(toolName) || "read_file".equals(toolName) || "read_log".equals(toolName));
    }

    WriteScope inspectWriteScope(String toolName, JsonNode input) {
        WriteScope scope = new WriteScope();
        if ("write_file".equals(toolName)) {
            markWriteScope(scope, input.path("path").asText(""));
            return scope;
        }
        if ("write_files".equals(toolName)) {
            JsonNode files = input.path("files");
            if (files.isArray()) {
                for (JsonNode item : files) {
                    markWriteScope(scope, item.path("path").asText(""));
                }
            }
        }
        return scope;
    }

    String determineAutoValidationFocus(AgentPhase phaseBeforeTurn, AgentContext ctx, boolean wroteVulnDemo, boolean wrotePoc) {
        if (phaseBeforeTurn == AgentPhase.GENERATE_MINIMAL) {
            return Files.exists(ctx.cvePath.resolve("poc/exploit.sh")) ? "full" : "startup";
        }
        if (wrotePoc) {
            return "full";
        }
        if (wroteVulnDemo) {
            return Files.exists(ctx.cvePath.resolve("poc/exploit.sh")) ? "full" : "startup";
        }
        return "full";
    }

    String buildAutoValidationFeedback(AgentContext ctx, ValidationResult result, String focus, boolean wroteReport) {
        StringBuilder sb = new StringBuilder();
        sb.append("AUTO VALIDATION after your latest file batch (focus=").append(focus).append("):\n");
        sb.append(llmHelper.renderJson(result.toMap())).append("\n");

        if (result.compileOk && result.startupOk && result.pocVerified) {
            if (Files.exists(ctx.cvePath.resolve("report/report.md")) || wroteReport) {
                sb.append("Validation passed. Call finish() now with the concrete evidence above.");
            } else {
                sb.append("Validation passed. Write report/report.md now, then call finish().");
            }
            return sb.toString();
        }

        if ("startup".equals(focus) && result.compileOk && result.startupOk) {
            sb.append("Compile/startup passed. Next, write or refine poc/exploit.sh, then validate again.");
            return sb.toString();
        }
        if (!result.compileOk) {
            sb.append("Next move: fix the compile failure only, then let the backend validate again.");
            return sb.toString();
        }
        if (!result.startupOk) {
            sb.append("Next move: fix the startup failure only, then validate again.");
            return sb.toString();
        }
        sb.append("Next move: keep the demo running, patch the demo or PoC to satisfy the missing verification signal, then validate again.");
        return sb.toString();
    }

    private String progressSignature(AgentContext ctx) {
        String validation = ctx.lastValidation == null ? "none" : ctx.lastValidation.summary();
        String gap = ctx.lastDirective == null ? "" : ctx.lastDirective.gap;
        return String.valueOf(ctx.phase) + "|" + validation + "|" + gap + "|review=" + ctx.reviewRevisions;
    }

    private List<String> allowedActions(AgentPhase phase) {
        switch (phase) {
            case PLAN:
                return Arrays.asList("submit_plan", "write_files", "write_file", "read_file", "read_log");
            case GENERATE_MINIMAL:
                return Arrays.asList("write_files", "write_file", "read_file", "read_log");
            case COMPILE_FIX:
            case STARTUP_FIX:
            case POC_FIX:
                return Arrays.asList("write_files", "write_file", "read_file", "read_log", "inspect_runtime", "validate_artifacts", "finish");
            case REPORT:
                return Arrays.asList("write_files", "write_file", "read_file", "read_log", "inspect_runtime", "finish");
            default:
                return Collections.singletonList("finish");
        }
    }

    private String deriveCompileFixHint(AgentContext ctx, ValidationResult result) {
        if (result == null || result.compileMessage == null) {
            return "Analyze the compilation error and fix it.";
        }

        String diagnosis = diagnoseFailure(ctx, "COMPILE", result.compileMessage,
            ctx.buildHistory.isEmpty() ? null : ctx.buildHistory.get(ctx.buildHistory.size() - 1).output);

        return diagnosis.isEmpty()
            ? "Read the compilation error, identify the root cause, then fix it."
            : diagnosis;
    }

    private String deriveStartupFixHint(AgentContext ctx, ValidationResult result) {
        if (result == null || result.startupMessage == null) {
            return "Analyze why the application failed to start and fix it.";
        }

        String diagnosis = diagnoseFailure(ctx, "STARTUP", result.startupMessage,
            ctx.startupHistory.isEmpty() ? null : ctx.startupHistory.get(ctx.startupHistory.size() - 1).output);

        return diagnosis.isEmpty()
            ? "Read the startup logs, identify the root cause, then fix configuration or code issues."
            : diagnosis;
    }

    private String derivePocFixHint(AgentContext ctx, ValidationResult result) {
        if (ctx != null && ctx.verificationPlan != null) {
            StringBuilder sb = new StringBuilder();

            String diagnosis = diagnoseFailure(ctx, "POC",
                result == null ? "PoC not verified" : result.pocMessage,
                ctx.commandHistory.isEmpty() ? null : ctx.commandHistory.get(ctx.commandHistory.size() - 1).output);

            if (!diagnosis.isEmpty()) {
                sb.append(diagnosis).append("\n\n");
            }

            if (!ctx.verificationPlan.successSignals.isEmpty()) {
                sb.append("Success signals: ")
                        .append(joinItems(ctx.verificationPlan.successSignals, "; "))
                        .append(".");
            }
            if (!ctx.verificationPlan.requiredEvidence.isEmpty()) {
                sb.append(" Required evidence: ")
                        .append(joinItems(ctx.verificationPlan.requiredEvidence, "; "))
                        .append(".");
            }
            if (!ctx.verificationPlan.falsePositiveGuards.isEmpty()) {
                sb.append(" False-positive guards: ")
                        .append(joinItems(ctx.verificationPlan.falsePositiveGuards, "; "))
                        .append(".");
            }
            return sb.toString().trim().isEmpty()
                ? "Analyze why the PoC failed and fix the demo or PoC to satisfy the verification plan."
                : sb.toString();
        }
        return "Analyze why the PoC failed and fix it to satisfy the verification signals.";
    }

    private String diagnoseFailure(AgentContext ctx, String failureType, String errorMessage, String fullLog) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are analyzing a ").append(failureType).append(" failure in CVE reproduction lab generation.\n\n");
        prompt.append("ERROR MESSAGE:\n").append(errorMessage).append("\n\n");

        if (fullLog != null && !fullLog.trim().isEmpty() && fullLog.length() < 8000) {
            prompt.append("FULL LOG:\n").append(fullLog).append("\n\n");
        }

        prompt.append("RECENT BUILD HISTORY:\n").append(llmHelper.renderJson(llmHelper.recentHistory(ctx.buildHistory))).append("\n\n");
        prompt.append("RECENT STARTUP HISTORY:\n").append(llmHelper.renderJson(llmHelper.recentHistory(ctx.startupHistory))).append("\n\n");
        prompt.append("RECENT COMMAND HISTORY:\n").append(llmHelper.renderJson(llmHelper.recentHistory(ctx.commandHistory))).append("\n\n");

        prompt.append("Analyze the root cause in 2-3 sentences. What specifically failed and why? ");
        prompt.append("Suggest concrete next steps to fix it. Be specific about file names and line numbers if applicable.");

        try {
            LlmRequest req = LlmRequest.reasoning(
                "You are a Java/Spring Boot/CVE expert analyzing build/runtime failures.",
                prompt.toString()
            );
            LlmResponse response = llmHelper.chatWithRetry(ctx.pipeCtx, req, 1);
            return response == null ? "" : response.getContent().trim();
        } catch (Exception e) {
            log.warn("Failure diagnosis failed: {}", e.getMessage());
            return "";
        }
    }

    private String derivePocGap(ValidationResult result) {
        if (result == null || result.pocMessage == null || result.pocMessage.trim().isEmpty()) {
            return "poc_unverified";
        }
        String message = result.pocMessage.toLowerCase(Locale.ROOT);
        if (message.contains("not found")) {
            return "poc_artifact_missing";
        }
        if (message.contains("unavailable")) {
            return "poc_runtime_unavailable";
        }
        if (message.contains("could not be read")) {
            return "poc_artifact_unreadable";
        }
        if (message.contains("failed")) {
            return "poc_execution_failed";
        }
        if (message.contains("unexpected") || message.contains("mismatch")) {
            return "poc_output_mismatch";
        }
        return "poc_unverified";
    }

    private String derivePocExpected(AgentContext ctx) {
        if (ctx != null && ctx.verificationPlan != null) {
            if (!ctx.verificationPlan.successSignals.isEmpty()) {
                return "The PoC should satisfy these verification signals: "
                        + joinItems(ctx.verificationPlan.successSignals, "; ");
            }
            if (!ctx.verificationPlan.requiredEvidence.isEmpty()) {
                return "The PoC should produce this evidence: "
                        + joinItems(ctx.verificationPlan.requiredEvidence, "; ");
            }
        }
        return "The PoC must satisfy the verification plan's success signals.";
    }

    private void markWriteScope(WriteScope scope, String path) {
        if (path == null) {
            return;
        }
        if (path.startsWith("vuln-demo/")) {
            scope.vulnDemo = true;
        } else if (path.startsWith("poc/")) {
            scope.poc = true;
        } else if (path.startsWith("report/")) {
            scope.report = true;
        }
    }
}

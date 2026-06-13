package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
import com.jvuln.store.JavaProfileRepository;
import com.jvuln.store.entity.JavaProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class ArtifactGenStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(ArtifactGenStage.class);
    private static final int MAX_AGENT_TURNS = 80;
    private static final int MAX_REVIEW_REVISIONS = 4;
    private static final int MAX_EMPTY_AGENT_RESPONSES = 2;
    private static final int VULN_DEMO_PORT = 18080;
    private static final int COMPILE_TIMEOUT = 120;
    private static final int STARTUP_WAIT = 30;
    private static final int COMMAND_TIMEOUT = 60;
    private static final int OUTPUT_TRUNCATE = 4000;
    private static final int PROCESS_OUTPUT_BUFFER = 64 * 1024;
    private static final int REVIEW_FILE_SNIPPET = 2000;
    private static final int REVIEW_HISTORY_ITEMS = 6;
    private static final int REVIEW_CONTINUE_BUFFER = 4;
    private static final int MEMORY_RECORD_LIMIT = 8;
    private static final int MEMORY_OUTPUT_TRUNCATE = 1200;
    private static final int REPORT_FALLBACK_TURNS = 5;
    private static final int MAX_NO_PROGRESS_TURNS = 6;
    private static final int CONTEXT_COMPACT_CHAR_LIMIT = 90000;
    private static final int CONTEXT_FILE_TOTAL_LIMIT = 36000;
    private static final int CONTEXT_FILE_LIMIT = 12000;
    private static final int CONTEXT_DIFF_LIMIT = 16000;
    private static final int TRANSCRIPT_ENTRY_LIMIT = 60000;

    private static final Set<String> COMMAND_WHITELIST = new HashSet<>(Arrays.asList(
            "curl", "wget", "grep", "cat", "ls", "find", "head", "tail",
            "bash", "test", "echo", "java", "mvn", "wc", "sort", "uniq",
            "chmod", "mkdir", "pwd", "diff", "file", "xxd", "base64", "sha256sum", "md5sum"
    ));

    private final PromptRegistry promptRegistry;
    private final ObjectMapper mapper;
    private final JavaProfileRepository javaProfileRepo;

    public ArtifactGenStage(PromptRegistry promptRegistry, ObjectMapper mapper,
                            JavaProfileRepository javaProfileRepo) {
        this.promptRegistry = promptRegistry;
        this.mapper = mapper;
        this.javaProfileRepo = javaProfileRepo;
    }

    @Override
    public int number() { return 4; }

    @Override
    public String name() { return "Artifact Generation"; }

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        ctx.reportProgress("Starting agent-based artifact generation");

        Object rawIntelligence = ctx.getCompletedStages().get(1).getData();
        String intelligence = trimIntelligence(rawIntelligence);
        log.info("Stage 4 intelligence (first 500 chars): {}", intelligence.substring(0, Math.min(500, intelligence.length())));
        String patchDiff = extractDiff(ctx, ctx.getCompletedStages().get(2).getData(), 4000);
        StageResult stage2 = ctx.getCompletedStages().get(2);
        String vulnerabilityFacts = extractVulnerabilityFacts(stage2 != null ? stage2.getData() : null);
        String triggerChain = extractTriggerChain(ctx.getCompletedStages().get(3).getData());
        String rootCause = extractRootCause(ctx.getCompletedStages().get(3).getData());
        String artifact = extractArtifact(rawIntelligence);
        JavaProfile javaProfile = resolveJavaProfile(ctx, rawIntelligence);
        log.info("Selected Java profile: {} (Java {} / Spring Boot {})",
                javaProfile.getName(), javaProfile.getJavaVersion(), javaProfile.getSpringBootVersion());
        ctx.reportProgress("Using Java profile: " + javaProfile.getName());
        VerificationPlan verificationPlan = buildVerificationPlan(ctx, intelligence, vulnerabilityFacts,
                triggerChain, rootCause, patchDiff, artifact);

        Path cvePath = ctx.getWorkspaceManager().getCvePath(ctx.getCveId());
        AgentContext agentCtx = new AgentContext(cvePath, ctx);
        agentCtx.verificationPlan = verificationPlan;
        agentCtx.javaProfile = javaProfile;
        Path checkpointFile = cvePath.resolve("stages/4_checkpoint.json");
        Path memoryFile = cvePath.resolve("stages/4_memory.json");

        try {
            writeMinimalSkeleton(cvePath.resolve("vuln-demo"), javaProfile);
            agentCtx.discoverExistingFiles();

            String systemTemplate = promptRegistry.getSystemPrompt("gen_agent");
            String userTemplate = promptRegistry.getUserPrompt("gen_agent");
            Map<String, String> vars = new HashMap<>();
            vars.put("cve_id", ctx.getCveId());
            vars.put("intelligence", intelligence);
            vars.put("vulnerability_facts", vulnerabilityFacts);
            vars.put("trigger_chain", triggerChain);
            vars.put("root_cause", rootCause);
            vars.put("patch_diff", patchDiff);
            vars.put("artifact", artifact);
            vars.put("verification_plan", renderJson(verificationPlan.toMap()));
            vars.put("java_version", javaProfile.getJavaVersion());
            vars.put("spring_boot_version", javaProfile.getSpringBootVersion());
            vars.put("syntax_constraints", javaProfile.getSyntaxConstraints() != null
                    ? javaProfile.getSyntaxConstraints()
                    : "Java " + javaProfile.getJavaVersion() + " syntax");
            String systemPrompt = promptRegistry.render(systemTemplate, vars);
            String baseUserPrompt = promptRegistry.render(userTemplate, vars);

            AttemptMemory memory = loadAttemptMemory(memoryFile);
            agentCtx.attemptMemory = memory;
            restoreAgentContextFromMemory(agentCtx, memory);

            int resumedTurns = 0;
            if (Files.exists(checkpointFile)) {
                try {
                    JsonNode ckpt = mapper.readTree(checkpointFile.toFile());
                    resumedTurns = ckpt.path("completedTurns").asInt(0);
                    log.info("Found Stage 4 checkpoint from previous attempt at turn {}", resumedTurns);
                    ctx.reportProgress("Loaded previous Stage 4 checkpoint from turn " + resumedTurns);
                } catch (Exception e) {
                    log.warn("Could not read checkpoint, starting fresh: {}", e.getMessage());
                }
                Files.deleteIfExists(checkpointFile);
            }
            agentCtx.turns = 0;
            agentCtx.reviewRevisions = 0;
            agentCtx.phase = inferPhase(agentCtx);

            // Build user prompt — append prior-attempt memory, but start a fresh turn budget.
            String userPrompt;
            if ((resumedTurns > 0 || memory.hasRecords()) && !agentCtx.existingFiles.isEmpty()) {
                StringBuilder sb = new StringBuilder(baseUserPrompt);
                sb.append("\n\n## RESUME CONTEXT\n");
                sb.append("This is a fresh Stage 4 attempt.\n");
                if (resumedTurns > 0) {
                    sb.append("The previous attempt stopped after ").append(resumedTurns).append(" turns.\n");
                }
                sb.append("The following files already exist — inspect them with read_file before rewriting:\n");
                for (String f : agentCtx.existingFiles) sb.append("- ").append(f).append("\n");
                if (agentCtx.executionPlan != null) {
                    sb.append("\n## EXECUTION PLAN\n");
                    sb.append(renderJson(agentCtx.executionPlan.toMap())).append("\n");
                }
                if (memory.hasRecords()) {
                    sb.append("\n## ATTEMPT MEMORY\n");
                    sb.append(renderAttemptMemory(memory));
                }
                if (memory.sessionSummary != null && !memory.sessionSummary.trim().isEmpty()) {
                    sb.append("\n\n## COMPACTED SESSION SUMMARY\n");
                    sb.append(memory.sessionSummary.trim()).append("\n");
                }
                sb.append("\nDo not continue the old control flow blindly. Re-plan from the current files and latest validator gap.");
                userPrompt = sb.toString();
            } else {
                userPrompt = baseUserPrompt;
            }

            // Agent loop
            agentCtx.baseUserPrompt = userPrompt;
            agentCtx.transcriptFile = cvePath.resolve("stages/5_transcript.jsonl");
            agentCtx.contextSummaryFile = cvePath.resolve("stages/5_context_summary.md");
            agentCtx.baselineSnapshot = captureWorkspaceSnapshot(agentCtx);
            agentCtx.previousSnapshot = new LinkedHashMap<>(agentCtx.baselineSnapshot);
            appendTranscript(agentCtx, "session_start", 0, buildSessionStartEvent(agentCtx, resumedTurns));

            List<LlmRequest.Message> messages = new ArrayList<>();
            messages.add(LlmRequest.Message.user(userPrompt));
            int emptyResponses = 0;

            for (int turn = 0; turn < MAX_AGENT_TURNS; turn++) {
                ctx.reportProgress("Agent turn " + (turn + 1));
                log.info("Agent turn {}/{} phase={} plan={} validation={}",
                        turn + 1, MAX_AGENT_TURNS, agentCtx.phase,
                        agentCtx.executionPlan != null,
                        agentCtx.lastValidation == null ? "none" : agentCtx.lastValidation.summary());

                // Inject urgency reminder when nearing the turn limit
                int remaining = MAX_AGENT_TURNS - turn;
                if (remaining == 20) {
                    messages.add(LlmRequest.Message.user(
                            "NOTICE: 20 turns remain. Keep the turn count low anyway: prefer one broad file batch, "
                            + "backend validation, then the smallest repair needed to satisfy the verification plan."));
                    appendTranscript(agentCtx, "directive", turn + 1, "NOTICE: 20 turns remain.");
                } else if (remaining == REPORT_FALLBACK_TURNS) {
                    if (agentCtx.phase != AgentPhase.REPORT) {
                        if (agentCtx.lastValidation == null) {
                            agentCtx.lastValidation = validateArtifacts(agentCtx, "full");
                        }
                        agentCtx.phase = AgentPhase.REPORT;
                        agentCtx.lastDirective = buildPhaseDirective(agentCtx, agentCtx.lastValidation, true);
                        String directive = "BACKEND PHASE SWITCH: REPORT\n" + renderPhaseDirective(agentCtx.lastDirective);
                        messages.add(LlmRequest.Message.user(directive));
                        appendTranscript(agentCtx, "directive", turn + 1, directive);
                    }
                    String warning = "FINAL WARNING: 5 turns remain. Stop debugging. Write the report with the current evidence "
                            + "and remaining gap, then call finish().";
                    messages.add(LlmRequest.Message.user(warning));
                    appendTranscript(agentCtx, "directive", turn + 1, warning);
                }

                compactMessagesIfNeeded(messages, agentCtx, "before_turn_" + (turn + 1));
                String contextPacket = buildContextPacket(agentCtx, turn + 1);
                messages.add(LlmRequest.Message.user(contextPacket));
                appendTranscript(agentCtx, "context_packet", turn + 1, contextPacket);

                List<LlmRequest.ToolDef> tools = buildToolDefinitions(agentCtx);
                LlmResponse response;
                try {
                    long llmStart = System.currentTimeMillis();
                    log.info("Agent LLM request: turn={} phase={} tools={} messages={}",
                            turn + 1, agentCtx.phase, toolDefNames(tools), messages.size());
                    response = chatWithRetry(ctx,
                            LlmRequest.agent(systemPrompt, messages, tools), 3);
                    log.info("Agent LLM response: turn={} phase={} durationMs={} finishReason={} text='{}' toolUses={}",
                            turn + 1, agentCtx.phase, System.currentTimeMillis() - llmStart,
                            response.getFinishReason(), responsePreview(response), toolUseNames(response.getToolUses()));
                } catch (Exception e) {
                    // LLM call failed after all retries — save checkpoint and return a failed stage with persisted pause state
                    String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Agent paused at turn {} due to LLM error: {}", turn + 1, errMsg);
                    agentCtx.turns = turn + 1;
                    saveCheckpoint(cvePath, agentCtx, errMsg);
                    persistAttemptMemory(memoryFile, agentCtx, "paused", errMsg);
                    Map<String, Object> output = agentCtx.buildOutput();
                    output.put("status", "paused");
                    output.put("pauseReason", errMsg);
                    output.put("pausedAtTurn", turn + 1);
                    output.remove("reproductionSteps");
                    ctx.getWorkspaceManager().writeStageData(ctx.getCveId(), 5, output);
                    ctx.reportProgress("Agent paused: " + errMsg);
                    return StageResult.failure(5, name(), "Agent paused: " + errMsg);
                }

                messages.add(LlmRequest.Message.assistantWithBlocks(response.getContentBlocks()));
                appendTranscript(agentCtx, "assistant", turn + 1, buildAssistantEvent(response));

                if (!response.hasToolUse()) {
                    log.info("Agent returned no tool calls: text='{}'", responsePreview(response));
                    agentCtx.turns = turn + 1;
                    emptyResponses++;
                    if (emptyResponses >= MAX_EMPTY_AGENT_RESPONSES) {
                        ValidationResult forcedValidation = validateArtifacts(agentCtx, "full");
                        agentCtx.lastValidation = forcedValidation;
                        agentCtx.lastDirective = buildPhaseDirective(agentCtx, forcedValidation, false);
                        String directive = "The backend validator was triggered because you stopped using tools.\n"
                                + renderPhaseDirective(agentCtx.lastDirective);
                        messages.add(LlmRequest.Message.user(directive));
                        appendTranscript(agentCtx, "directive", turn + 1, directive);
                        continue;
                    }
                    if (turn + 1 >= MAX_AGENT_TURNS) {
                        break;
                    }
                    String directive = agentCtx.executionPlan == null
                            ? "Your last response did not invoke any tool. Submit an execution plan with submit_plan first."
                            : renderPhaseDirective(currentDirective(agentCtx));
                    messages.add(LlmRequest.Message.user(directive));
                    appendTranscript(agentCtx, "directive", turn + 1, directive);
                    continue;
                }
                emptyResponses = 0;

                List<LlmRequest.ContentBlock> toolResults = new ArrayList<>();
                boolean finished = false;
                boolean wroteVulnDemo = false;
                boolean wrotePoc = false;
                boolean wroteReport = false;
                boolean ranValidation = false;
                AgentPhase phaseBeforeTurn = agentCtx.phase;

                for (LlmRequest.ContentBlock block : response.getToolUses()) {
                    String toolName = block.getToolName();
                    log.info("Agent tool call: turn={} phase={} tool={} input={}",
                            turn + 1, agentCtx.phase, toolName, summarizeToolInput(toolName, block.getToolInput()));
                    if (!isToolAllowed(agentCtx.phase, toolName)) {
                        String error = "Tool '" + toolName + "' is not available in phase " + agentCtx.phase.name()
                                + ". " + renderPhaseDirective(currentDirective(agentCtx));
                        log.warn("Agent tool rejected: turn={} phase={} tool={} reason={}",
                                turn + 1, agentCtx.phase, toolName, singleLine(error, 300));
                        toolResults.add(LlmRequest.ContentBlock.toolResultError(block.getToolUseId(), error));
                        continue;
                    }
                    if (requiresExecutionPlan(toolName) && agentCtx.executionPlan == null) {
                        String error = "Execution plan required first. Call submit_plan before any file writes.";
                        log.warn("Agent tool rejected: turn={} phase={} tool={} reason={}",
                                turn + 1, agentCtx.phase, toolName, error);
                        toolResults.add(LlmRequest.ContentBlock.toolResultError(block.getToolUseId(), error));
                        continue;
                    }

                    if ("finish".equals(block.getToolName())) {
                        VerificationReview review = reviewGeneratedArtifacts(ctx, agentCtx, block.getToolInput(),
                                intelligence, vulnerabilityFacts, triggerChain, rootCause, patchDiff, artifact);
                        agentCtx.verificationReview = review;

                        boolean canRetry = review.requiresRevision()
                                && agentCtx.reviewRevisions < MAX_REVIEW_REVISIONS
                                && turn + REVIEW_CONTINUE_BUFFER < MAX_AGENT_TURNS;
                        if (canRetry) {
                            agentCtx.reviewRevisions++;
                            toolResults.add(LlmRequest.ContentBlock.toolResultError(block.getToolUseId(),
                                    buildReviewerFeedback(review, agentCtx.reviewRevisions)));
                            log.info("Agent finish rejected by reviewer: verdict={} revision={}/{}",
                                    review.verdict, agentCtx.reviewRevisions, MAX_REVIEW_REVISIONS);
                        } else {
                            finished = true;
                            agentCtx.summary = mergeSummaryWithReview(block.getToolInput(), review);
                            toolResults.add(LlmRequest.ContentBlock.toolResult(block.getToolUseId(),
                                    buildFinishAcceptedMessage(review)));
                            log.info("Agent finish accepted with reviewer verdict {}", review.verdict);
                        }
                        break;
                    }

                    String result = executeToolCall(agentCtx, block);
                    toolResults.add(LlmRequest.ContentBlock.toolResult(block.getToolUseId(), result));
                    log.info("Agent tool result: turn={} phase={} tool={} ok={} result='{}'",
                            turn + 1, agentCtx.phase, toolName, !result.startsWith("Error:"),
                            singleLine(result, 300));
                    if (!result.startsWith("Error:")) {
                        ranValidation = ranValidation || "validate_artifacts".equals(toolName);
                        WriteScope scope = inspectWriteScope(toolName, block.getToolInput());
                        wroteVulnDemo = wroteVulnDemo || scope.vulnDemo;
                        wrotePoc = wrotePoc || scope.poc;
                        wroteReport = wroteReport || scope.report;
                    }
                }

                messages.add(LlmRequest.Message.toolResults(toolResults));
                appendTranscript(agentCtx, "tool_results", turn + 1, buildToolResultEvent(toolResults));

                agentCtx.turns = turn + 1;

                if (!finished && (wroteVulnDemo || wrotePoc)) {
                    String focus = determineAutoValidationFocus(phaseBeforeTurn, agentCtx, wroteVulnDemo, wrotePoc);
                    log.info("Agent auto-validation start: turn={} previousPhase={} focus={} wroteVulnDemo={} wrotePoc={}",
                            turn + 1, phaseBeforeTurn, focus, wroteVulnDemo, wrotePoc);
                    ValidationResult autoValidation = validateArtifacts(agentCtx, focus);
                    agentCtx.lastValidation = autoValidation;
                    agentCtx.phase = nextPhaseAfterValidation(agentCtx, autoValidation, remaining - 1);
                    agentCtx.lastDirective = buildPhaseDirective(agentCtx, autoValidation, false);
                    log.info("Agent auto-validation done: turn={} nextPhase={} result={}",
                            turn + 1, agentCtx.phase, autoValidation.summary());
                    String directive = renderPhaseDirective(agentCtx.lastDirective);
                    messages.add(LlmRequest.Message.user(directive));
                    appendTranscript(agentCtx, "directive", turn + 1, directive);
                } else if (!finished && wroteReport && agentCtx.phase == AgentPhase.REPORT) {
                    agentCtx.lastDirective = buildPhaseDirective(agentCtx, agentCtx.lastValidation, false);
                    String directive = renderPhaseDirective(agentCtx.lastDirective);
                    messages.add(LlmRequest.Message.user(directive));
                    appendTranscript(agentCtx, "directive", turn + 1, directive);
                } else if (!finished && ranValidation) {
                    agentCtx.phase = nextPhaseAfterValidation(agentCtx, agentCtx.lastValidation, remaining - 1);
                    agentCtx.lastDirective = buildPhaseDirective(agentCtx, agentCtx.lastValidation, false);
                    log.info("Agent manual validation directive: turn={} nextPhase={} result={}",
                            turn + 1, agentCtx.phase,
                            agentCtx.lastValidation == null ? "none" : agentCtx.lastValidation.summary());
                    String directive = renderPhaseDirective(agentCtx.lastDirective);
                    messages.add(LlmRequest.Message.user(directive));
                    appendTranscript(agentCtx, "directive", turn + 1, directive);
                }

                if (!finished) {
                    updateProgressGuard(agentCtx, wroteVulnDemo || wrotePoc || wroteReport);
                    if (agentCtx.abortReason != null) {
                        log.warn("Agent stopped by progress guard at turn {}: {}", turn + 1, agentCtx.abortReason);
                        ctx.reportProgress(agentCtx.abortReason);
                        break;
                    }
                }

                if (finished) break;
                compactMessagesIfNeeded(messages, agentCtx, "after_turn_" + (turn + 1));
            }

            if (agentCtx.summary == null) {
                agentCtx.lastValidation = validateArtifacts(agentCtx, "full");
                agentCtx.verificationReview = reviewGeneratedArtifacts(ctx, agentCtx, null,
                        intelligence, vulnerabilityFacts, triggerChain, rootCause, patchDiff, artifact);
                ObjectNode forcedSummary = mapper.createObjectNode();
                forcedSummary.put("vuln_demo_status",
                        agentCtx.lastValidation != null && agentCtx.lastValidation.startupOk ? "startup_ok"
                                : (agentCtx.lastValidation != null && agentCtx.lastValidation.compileOk ? "compile_ok" : "compile_failed"));
                forcedSummary.put("poc_status", agentCtx.verificationReview == null ? "unverified" : agentCtx.verificationReview.pocStatus);
                forcedSummary.put("report_status", Files.exists(cvePath.resolve("report/report.md")) ? "generated" : "skipped");
                forcedSummary.put("verification_evidence",
                        agentCtx.lastValidation == null ? "" : agentCtx.lastValidation.summary());
                forcedSummary.put("remaining_gap",
                        agentCtx.verificationReview == null ? "Agent did not call finish()." : agentCtx.verificationReview.reason);
                forcedSummary.put("notes", agentCtx.abortReason == null
                        ? "Summary synthesized by backend validator because agent did not call finish()."
                        : agentCtx.abortReason);
                agentCtx.summary = mergeSummaryWithReview(forcedSummary, agentCtx.verificationReview);
            }

            // Build output
            Map<String, Object> output = agentCtx.buildOutput();
            String failureReason = agentCtx.abortReason != null ? agentCtx.abortReason : determineFailureReason(output);
            if (failureReason != null) {
                output.put("failureReason", failureReason);
            }
            persistAttemptMemory(memoryFile, agentCtx, failureReason == null ? "completed" : "failed",
                    failureReason == null ? "" : failureReason);
            ctx.getWorkspaceManager().writeStageData(ctx.getCveId(), 5, output);
            ctx.reportProgress("Agent completed in " + agentCtx.turns + " turns");
            if (failureReason != null) {
                return StageResult.failure(5, name(), failureReason);
            }
            return StageResult.success(5, name(), output);

        } finally {
            agentCtx.cleanup();
        }
    }

    // ==================== Checkpoint ====================

    private void saveCheckpoint(Path cvePath, AgentContext ctx, String error) {
        try {
            Map<String, Object> ckpt = new LinkedHashMap<>();
            ckpt.put("completedTurns", ctx.turns);
            ckpt.put("error", error);
            ckpt.put("writtenFiles", new ArrayList<>(ctx.writtenFiles));
            ckpt.put("timestamp", System.currentTimeMillis());
            Path file = cvePath.resolve("stages/4_checkpoint.json");
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), ckpt);
            log.info("Saved checkpoint at turn {}", ctx.turns);
        } catch (Exception e) {
            log.error("Failed to save checkpoint: {}", e.getMessage());
        }
    }

    // ==================== Context Management ====================

    private Map<String, Object> buildSessionStartEvent(AgentContext ctx, int resumedTurns) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("cveId", ctx.pipeCtx.getCveId());
        event.put("resumedTurns", resumedTurns);
        event.put("phase", ctx.phase == null ? "" : ctx.phase.name());
        event.put("existingFiles", new ArrayList<>(ctx.existingFiles));
        if (ctx.verificationPlan != null) {
            event.put("verificationPlan", ctx.verificationPlan.toMap());
        }
        if (ctx.sessionSummary != null && !ctx.sessionSummary.trim().isEmpty()) {
            event.put("restoredSessionSummary", ctx.sessionSummary);
        }
        return event;
    }

    private String buildContextPacket(AgentContext ctx, int turn) {
        Map<String, FileSnapshot> current = captureWorkspaceSnapshot(ctx);
        String diffFromPrevious = renderSnapshotDiff(ctx.previousSnapshot, current, CONTEXT_DIFF_LIMIT / 2);
        String diffFromBaseline = renderSnapshotDiff(ctx.baselineSnapshot, current, CONTEXT_DIFF_LIMIT / 2);
        ctx.previousSnapshot = new LinkedHashMap<>(current);

        StringBuilder sb = new StringBuilder();
        sb.append("BACKEND CONTEXT PACKET\n");
        sb.append("This packet is authoritative for the current workspace state. ");
        sb.append("Do not rely on memory if it conflicts with this packet.\n");
        sb.append("Turn: ").append(turn).append("/").append(MAX_AGENT_TURNS).append("\n");
        sb.append("Phase: ").append(ctx.phase == null ? "" : ctx.phase.name()).append("\n");
        if (ctx.lastDirective != null) {
            sb.append("\n## Current Directive\n");
            sb.append(renderJson(ctx.lastDirective.toMap())).append("\n");
        }
        if (ctx.executionPlan != null) {
            sb.append("\n## Accepted Execution Plan\n");
            sb.append(renderJson(ctx.executionPlan.toMap())).append("\n");
        }
        if (ctx.lastValidation != null) {
            sb.append("\n## Latest Backend Validation\n");
            sb.append(renderJson(ctx.lastValidation.toMap())).append("\n");
        }
        if (ctx.verificationReview != null) {
            sb.append("\n## Latest Reviewer Verdict\n");
            sb.append(renderJson(ctx.verificationReview.toMap())).append("\n");
        }
        if (ctx.sessionSummary != null && !ctx.sessionSummary.trim().isEmpty()) {
            sb.append("\n## Compacted Session Summary\n");
            sb.append(ctx.sessionSummary.trim()).append("\n");
        }
        if (ctx.attemptMemory != null && ctx.attemptMemory.hasRecords()) {
            sb.append("\n## Prior Attempt Memory\n");
            sb.append(renderAttemptMemory(ctx.attemptMemory)).append("\n");
        }

        sb.append("\n## Workspace Manifest\n");
        sb.append(renderFileManifest(current)).append("\n");

        sb.append("\n## Current File Contents\n");
        sb.append(renderCurrentFileContents(current)).append("\n");

        sb.append("\n## Diff: Previous Turn -> Current Workspace\n");
        sb.append(diffFromPrevious.isEmpty() ? "(no file changes since previous context packet)" : diffFromPrevious).append("\n");

        sb.append("\n## Diff: Initial Workspace -> Current Workspace\n");
        sb.append(diffFromBaseline.isEmpty() ? "(no file changes from initial workspace)" : diffFromBaseline).append("\n");

        sb.append("\nUse write_files for the next concrete patch. Use read_file only when a needed file is omitted or marked truncated.");
        return sb.toString();
    }

    private void compactMessagesIfNeeded(List<LlmRequest.Message> messages, AgentContext ctx, String reason) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int chars = estimateMessagesChars(messages);
        if (chars < CONTEXT_COMPACT_CHAR_LIMIT) {
            return;
        }

        String summary = buildCompactedSessionSummary(ctx, reason, chars);
        ctx.sessionSummary = summary;
        writeContextSummary(ctx, summary);

        messages.clear();
        messages.add(LlmRequest.Message.user(ctx.baseUserPrompt == null ? "" : ctx.baseUserPrompt));
        messages.add(LlmRequest.Message.user("COMPACTED SESSION SUMMARY\n" + summary
                + "\nThe next BACKEND CONTEXT PACKET is authoritative for current files and validation state."));
        ctx.compactionCount++;
        appendTranscript(ctx, "compact", ctx.turns, summary);
        log.info("Compacted Stage 4 agent context: reason={} previousChars={} compactions={}",
                reason, chars, ctx.compactionCount);
    }

    private String buildCompactedSessionSummary(AgentContext ctx, String reason, int previousChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("Reason: ").append(reason).append("\n");
        sb.append("Previous message chars: ").append(previousChars).append("\n");
        sb.append("CVE: ").append(ctx.pipeCtx.getCveId()).append("\n");
        sb.append("Phase: ").append(ctx.phase == null ? "" : ctx.phase.name()).append("\n");
        sb.append("Turns completed in this attempt: ").append(ctx.turns).append("\n");
        sb.append("Review revisions: ").append(ctx.reviewRevisions).append("\n");
        if (ctx.executionPlan != null) {
            sb.append("\nExecution plan:\n").append(renderJson(ctx.executionPlan.toMap())).append("\n");
        }
        if (ctx.lastDirective != null) {
            sb.append("\nCurrent directive:\n").append(renderJson(ctx.lastDirective.toMap())).append("\n");
        }
        if (ctx.lastValidation != null) {
            sb.append("\nLatest validation:\n").append(renderJson(ctx.lastValidation.toMap())).append("\n");
        }
        if (ctx.verificationReview != null) {
            sb.append("\nLatest review:\n").append(renderJson(ctx.verificationReview.toMap())).append("\n");
        }
        if (!ctx.buildHistory.isEmpty()) {
            sb.append("\nRecent build history:\n").append(renderJson(recentHistory(ctx.buildHistory))).append("\n");
        }
        if (!ctx.startupHistory.isEmpty()) {
            sb.append("\nRecent startup history:\n").append(renderJson(recentHistory(ctx.startupHistory))).append("\n");
        }
        if (!ctx.commandHistory.isEmpty()) {
            sb.append("\nRecent command history:\n").append(renderJson(recentHistory(ctx.commandHistory))).append("\n");
        }
        Map<String, FileSnapshot> current = captureWorkspaceSnapshot(ctx);
        sb.append("\nCurrent workspace manifest:\n").append(renderFileManifest(current)).append("\n");
        if (ctx.abortReason != null && !ctx.abortReason.trim().isEmpty()) {
            sb.append("\nAbort reason:\n").append(ctx.abortReason).append("\n");
        }
        return sb.toString().trim();
    }

    private int estimateMessagesChars(List<LlmRequest.Message> messages) {
        int total = 0;
        for (LlmRequest.Message message : messages) {
            total += message == null ? 0 : estimateContentChars(message.getContent());
        }
        return total;
    }

    private int estimateContentChars(Object content) {
        if (content == null) {
            return 0;
        }
        if (content instanceof String) {
            return ((String) content).length();
        }
        if (content instanceof List) {
            int total = 0;
            for (Object item : (List<?>) content) {
                if (item instanceof LlmRequest.ContentBlock) {
                    LlmRequest.ContentBlock block = (LlmRequest.ContentBlock) item;
                    total += safeLength(block.getText());
                    total += safeLength(block.getToolName());
                    total += block.getToolInput() == null ? 0 : block.getToolInput().toString().length();
                    total += safeLength(block.getToolResultContent());
                } else if (item != null) {
                    total += item.toString().length();
                }
            }
            return total;
        }
        return content.toString().length();
    }

    private int safeLength(String s) {
        return s == null ? 0 : s.length();
    }

    private Map<String, FileSnapshot> captureWorkspaceSnapshot(AgentContext ctx) {
        Map<String, FileSnapshot> files = new TreeMap<>();
        for (String prefix : Arrays.asList("vuln-demo", "poc", "report")) {
            Path dir = ctx.cvePath.resolve(prefix);
            if (!Files.exists(dir)) {
                continue;
            }
            try {
                Files.walk(dir)
                        .filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                        .forEach(p -> {
                            try {
                                String rel = ctx.cvePath.relativize(p).toString();
                                files.put(rel, FileSnapshot.fromPath(ctx.cvePath, p, rel));
                            } catch (Exception ignored) {
                                // Snapshot is best-effort; read_file remains available for exact recovery.
                            }
                        });
            } catch (Exception ignored) {
                // Continue with other workspace roots.
            }
        }
        return new LinkedHashMap<>(files);
    }

    private String renderFileManifest(Map<String, FileSnapshot> files) {
        if (files == null || files.isEmpty()) {
            return "(no files)";
        }
        StringBuilder sb = new StringBuilder();
        for (FileSnapshot file : files.values()) {
            sb.append("- ").append(file.path)
                    .append(" size=").append(file.size)
                    .append(" sha256=").append(file.sha256Short());
            if (!file.text) {
                sb.append(" binary_or_nontext");
            } else if (file.truncated) {
                sb.append(" content_truncated");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String renderCurrentFileContents(Map<String, FileSnapshot> files) {
        if (files == null || files.isEmpty()) {
            return "(no files)";
        }
        List<FileSnapshot> ordered = new ArrayList<>(files.values());
        ordered.sort((a, b) -> Integer.compare(fileContextPriority(a.path), fileContextPriority(b.path)));

        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (FileSnapshot file : ordered) {
            if (!file.text || file.content == null) {
                continue;
            }
            int next = file.content.length();
            if (used + next > CONTEXT_FILE_TOTAL_LIMIT && used > 0) {
                sb.append("\n[omitted remaining files due to context budget; call read_file for exact content]\n");
                break;
            }
            used += next;
            sb.append("\n### ").append(file.path);
            if (file.truncated) {
                sb.append(" (truncated; call read_file for full content)");
            }
            sb.append("\n```").append(fileFenceType(file.path)).append("\n");
            sb.append(file.content);
            if (!file.content.endsWith("\n")) {
                sb.append("\n");
            }
            sb.append("```\n");
        }
        return sb.length() == 0 ? "(no text files captured; use read_file)" : sb.toString().trim();
    }

    private int fileContextPriority(String path) {
        if (path == null) {
            return 100;
        }
        if ("poc/exploit.sh".equals(path)) return 0;
        if ("vuln-demo/pom.xml".equals(path)) return 1;
        if (path.endsWith("application.properties") || path.endsWith("application.yml")
                || path.endsWith("application.yaml")) return 2;
        if (path.endsWith(".java")) return 3;
        if (path.startsWith("vuln-demo/")) return 4;
        if (path.startsWith("poc/")) return 5;
        if (path.startsWith("report/")) return 6;
        return 20;
    }

    private String fileFenceType(String path) {
        if (path == null) return "";
        if (path.endsWith(".java")) return "java";
        if (path.endsWith(".xml")) return "xml";
        if (path.endsWith(".sh")) return "bash";
        if (path.endsWith(".md")) return "markdown";
        if (path.endsWith(".json")) return "json";
        if (path.endsWith(".yml") || path.endsWith(".yaml")) return "yaml";
        if (path.endsWith(".properties")) return "properties";
        return "";
    }

    private String renderSnapshotDiff(Map<String, FileSnapshot> before, Map<String, FileSnapshot> after, int limit) {
        Map<String, FileSnapshot> a = before == null ? Collections.emptyMap() : before;
        Map<String, FileSnapshot> b = after == null ? Collections.emptyMap() : after;
        TreeSet<String> paths = new TreeSet<>();
        paths.addAll(a.keySet());
        paths.addAll(b.keySet());

        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            FileSnapshot oldFile = a.get(path);
            FileSnapshot newFile = b.get(path);
            if (oldFile == null && newFile != null) {
                appendLimited(sb, "Added: " + path + " size=" + newFile.size
                        + " sha256=" + newFile.sha256Short() + "\n", limit);
                appendFileDiffContent(sb, null, newFile, limit);
            } else if (oldFile != null && newFile == null) {
                appendLimited(sb, "Deleted: " + path + " oldSize=" + oldFile.size
                        + " oldSha256=" + oldFile.sha256Short() + "\n", limit);
            } else if (oldFile != null && newFile != null && !oldFile.sha256.equals(newFile.sha256)) {
                appendLimited(sb, "Modified: " + path
                        + " oldSha256=" + oldFile.sha256Short()
                        + " newSha256=" + newFile.sha256Short() + "\n", limit);
                appendFileDiffContent(sb, oldFile, newFile, limit);
            }
            if (sb.length() >= limit) {
                appendLimited(sb, "\n...[diff truncated]\n", limit + 64);
                break;
            }
        }
        return sb.toString().trim();
    }

    private void appendFileDiffContent(StringBuilder sb, FileSnapshot oldFile, FileSnapshot newFile, int limit) {
        if (newFile == null || !newFile.text || newFile.content == null) {
            return;
        }
        String diff;
        if (oldFile == null || oldFile.content == null || !oldFile.text) {
            diff = renderAddedFile(newFile);
        } else {
            diff = renderLineWindowDiff(oldFile.path, oldFile.content, newFile.content);
        }
        appendLimited(sb, diff, limit);
    }

    private String renderAddedFile(FileSnapshot file) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- /dev/null\n");
        sb.append("+++ ").append(file.path).append("\n");
        String[] lines = file.content.split("\\R", -1);
        int maxLines = Math.min(lines.length, 80);
        for (int i = 0; i < maxLines; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) {
                continue;
            }
            sb.append("+").append(lines[i]).append("\n");
        }
        if (lines.length > maxLines) {
            sb.append("+...[added file truncated]\n");
        }
        return sb.toString();
    }

    private String renderLineWindowDiff(String path, String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\\R", -1);
        String[] newLines = newContent.split("\\R", -1);
        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length
                && Objects.equals(oldLines[prefix], newLines[prefix])) {
            prefix++;
        }

        int suffix = 0;
        while (suffix + prefix < oldLines.length && suffix + prefix < newLines.length
                && Objects.equals(oldLines[oldLines.length - 1 - suffix], newLines[newLines.length - 1 - suffix])) {
            suffix++;
        }

        int context = 3;
        int oldStart = Math.max(0, prefix - context);
        int newStart = Math.max(0, prefix - context);
        int oldEnd = Math.min(oldLines.length, oldLines.length - suffix + context);
        int newEnd = Math.min(newLines.length, newLines.length - suffix + context);

        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(path).append(" (previous)\n");
        sb.append("+++ ").append(path).append(" (current)\n");
        sb.append("@@ approx lines ").append(oldStart + 1).append(" -> ").append(newStart + 1).append(" @@\n");
        for (int i = oldStart; i < prefix && i < oldEnd; i++) {
            sb.append(" ").append(oldLines[i]).append("\n");
        }
        for (int i = prefix; i < oldEnd - context && i < oldLines.length; i++) {
            sb.append("-").append(oldLines[i]).append("\n");
        }
        for (int i = prefix; i < newEnd - context && i < newLines.length; i++) {
            sb.append("+").append(newLines[i]).append("\n");
        }
        int contextStart = Math.max(prefix, oldEnd - context);
        for (int i = contextStart; i < oldEnd && i < oldLines.length; i++) {
            sb.append(" ").append(oldLines[i]).append("\n");
        }
        return sb.toString();
    }

    private void appendLimited(StringBuilder sb, String value, int limit) {
        if (value == null || sb.length() >= limit) {
            return;
        }
        int remaining = limit - sb.length();
        if (value.length() <= remaining) {
            sb.append(value);
        } else {
            sb.append(value, 0, Math.max(0, remaining));
        }
    }

    private void appendTranscript(AgentContext ctx, String type, int turn, Object payload) {
        if (ctx == null || ctx.transcriptFile == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("ts", System.currentTimeMillis());
            event.put("turn", turn);
            event.put("type", type);
            event.put("phase", ctx.phase == null ? "" : ctx.phase.name());
            event.put("payload", trimTranscriptPayload(payload));
            Files.createDirectories(ctx.transcriptFile.getParent());
            String line = mapper.writeValueAsString(event) + "\n";
            Files.write(ctx.transcriptFile, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("Failed to append Stage 4 transcript: {}", e.getMessage());
        }
    }

    private Object trimTranscriptPayload(Object payload) {
        if (payload == null) {
            return "";
        }
        if (payload instanceof String) {
            return truncateHead((String) payload, TRANSCRIPT_ENTRY_LIMIT);
        }
        try {
            String json = mapper.writeValueAsString(payload);
            if (json.length() <= TRANSCRIPT_ENTRY_LIMIT) {
                return payload;
            }
            return truncateHead(json, TRANSCRIPT_ENTRY_LIMIT);
        } catch (Exception e) {
            return truncateHead(String.valueOf(payload), TRANSCRIPT_ENTRY_LIMIT);
        }
    }

    private void writeContextSummary(AgentContext ctx, String summary) {
        if (ctx == null || ctx.contextSummaryFile == null || summary == null) {
            return;
        }
        try {
            Files.createDirectories(ctx.contextSummaryFile.getParent());
            Files.write(ctx.contextSummaryFile, summary.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Failed to write Stage 4 context summary: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildAssistantEvent(LlmResponse response) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("finishReason", response == null ? "" : response.getFinishReason());
        event.put("text", response == null ? "" : responsePreview(response));
        event.put("toolUses", response == null ? Collections.emptyList() : toolUseSummaries(response.getToolUses()));
        return event;
    }

    private List<Map<String, Object>> toolUseSummaries(List<LlmRequest.ContentBlock> toolUses) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (toolUses == null) {
            return out;
        }
        for (LlmRequest.ContentBlock block : toolUses) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", block.getToolName());
            item.put("input", summarizeToolInput(block.getToolName(), block.getToolInput()));
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> buildToolResultEvent(List<LlmRequest.ContentBlock> toolResults) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (toolResults == null) {
            return out;
        }
        for (LlmRequest.ContentBlock block : toolResults) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("isError", block.isError());
            item.put("content", truncateHead(block.getToolResultContent(), MEMORY_OUTPUT_TRUNCATE));
            out.add(item);
        }
        return out;
    }

    private String truncateHead(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }

    private static class FileSnapshot {
        final String path;
        final long size;
        final String sha256;
        final boolean text;
        final boolean truncated;
        final String content;

        private FileSnapshot(String path, long size, String sha256, boolean text, boolean truncated, String content) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256 == null ? "" : sha256;
            this.text = text;
            this.truncated = truncated;
            this.content = content;
        }

        static FileSnapshot fromPath(Path root, Path file, String rel) throws Exception {
            byte[] bytes = Files.readAllBytes(file);
            boolean text = isProbablyText(rel, bytes);
            boolean truncated = text && bytes.length > CONTEXT_FILE_LIMIT;
            String content = null;
            if (text) {
                int len = Math.min(bytes.length, CONTEXT_FILE_LIMIT);
                content = new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            return new FileSnapshot(rel, bytes.length, sha256(bytes), text, truncated, content);
        }

        String sha256Short() {
            return sha256.length() <= 12 ? sha256 : sha256.substring(0, 12);
        }

        private static boolean isProbablyText(String path, byte[] bytes) {
            if (path != null) {
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".java") || lower.endsWith(".xml") || lower.endsWith(".properties")
                        || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".sh")
                        || lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".json")
                        || lower.endsWith(".html") || lower.endsWith(".js") || lower.endsWith(".css")) {
                    return true;
                }
            }
            int max = Math.min(bytes.length, 4096);
            for (int i = 0; i < max; i++) {
                if (bytes[i] == 0) {
                    return false;
                }
            }
            return true;
        }

        private static String sha256(byte[] bytes) throws Exception {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        }
    }

    // ==================== LLM Call with Retry ====================

    private LlmResponse chatWithRetry(PipelineContext ctx, LlmRequest request, int maxAttempts) throws Exception {
        Exception lastErr = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return ctx.getLlmClient().chat(request);
            } catch (Exception e) {
                lastErr = e;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                boolean retryable = msg.contains("500") || msg.contains("502") || msg.contains("503")
                        || msg.contains("429") || msg.contains("403") || msg.contains("overloaded")
                        || msg.contains("Internal Server Error") || msg.contains("returned no content")
                        || msg.contains("LLM API error 200");
                if (!retryable || attempt == maxAttempts) {
                    throw e;
                }
                long delay = attempt * 10_000L; // 10s, 20s
                log.warn("LLM call failed (attempt {}/{}), retrying in {}s: {}",
                        attempt, maxAttempts, delay / 1000, msg);
                Thread.sleep(delay);
            }
        }
        throw lastErr;
    }

    // ==================== Tool Definitions ====================

    private List<LlmRequest.ToolDef> buildToolDefinitions(AgentContext ctx) {
        List<LlmRequest.ToolDef> allTools = new ArrayList<>();

        Map<String, Object> planSchema = new LinkedHashMap<>();
        planSchema.put("type", "object");
        Map<String, Object> planProps = new LinkedHashMap<>();
        planProps.put("goal", prop("string", "Short goal for this Stage 4 attempt. MUST start with the exact CVE ID from the system prompt (e.g., 'Build CVE-YYYY-NNNNN reproduction environment')."));
        planProps.put("firstBatchFiles", stringArrayProp("Files to generate in the first broad batch"));
        planProps.put("minimalDeliverables", stringArrayProp("Smallest runnable candidate to prove before polishing"));
        planProps.put("validationSequence", stringArrayProp("Planned validation order, for example build -> startup -> poc"));
        planProps.put("deferredUntilVerified", stringArrayProp("Files or tasks intentionally deferred until validation is green"));
        planProps.put("risks", stringArrayProp("Main failure risks or likely repair targets"));
        planProps.put("reportStrategy", prop("string", "When you will write report/report.md"));
        planSchema.put("properties", planProps);
        planSchema.put("required", Arrays.asList("goal", "firstBatchFiles", "validationSequence", "reportStrategy"));
        allTools.add(new LlmRequest.ToolDef("submit_plan",
                "Submit the execution plan before build/start/write/validate actions. You may call submit_plan first and then continue with write_files in the same turn.",
                planSchema));

        Map<String, Object> writeSchema = new LinkedHashMap<>();
        writeSchema.put("type", "object");
        Map<String, Object> writeProps = new LinkedHashMap<>();
        writeProps.put("path", prop("string", "File path relative to workspace (e.g. vuln-demo/pom.xml, poc/exploit.sh, report/report.md)"));
        writeProps.put("content", prop("string", "Full file content"));
        writeSchema.put("properties", writeProps);
        writeSchema.put("required", Arrays.asList("path", "content"));
        allTools.add(new LlmRequest.ToolDef("write_file",
                "Write a file to the workspace. Path must start with vuln-demo/, poc/, or report/.",
                writeSchema));

        Map<String, Object> batchWriteSchema = new LinkedHashMap<>();
        batchWriteSchema.put("type", "object");
        Map<String, Object> batchWriteProps = new LinkedHashMap<>();
        Map<String, Object> filesSchema = new LinkedHashMap<>();
        filesSchema.put("type", "array");
        Map<String, Object> fileItemSchema = new LinkedHashMap<>();
        fileItemSchema.put("type", "object");
        Map<String, Object> fileItemProps = new LinkedHashMap<>();
        fileItemProps.put("path", prop("string", "File path relative to workspace"));
        fileItemProps.put("content", prop("string", "Full file content"));
        fileItemSchema.put("properties", fileItemProps);
        fileItemSchema.put("required", Arrays.asList("path", "content"));
        filesSchema.put("items", fileItemSchema);
        batchWriteProps.put("files", filesSchema);
        batchWriteSchema.put("properties", batchWriteProps);
        batchWriteSchema.put("required", Collections.singletonList("files"));
        allTools.add(new LlmRequest.ToolDef("write_files",
                "Write multiple files in one call. Every path must start with vuln-demo/, poc/, or report/.",
                batchWriteSchema));

        Map<String, Object> readSchema = new LinkedHashMap<>();
        readSchema.put("type", "object");
        Map<String, Object> readProps = new LinkedHashMap<>();
        readProps.put("path", prop("string", "File path relative to workspace"));
        readSchema.put("properties", readProps);
        readSchema.put("required", Collections.singletonList("path"));
        allTools.add(new LlmRequest.ToolDef("read_file",
                "Read a full source, config, or report file from the workspace.",
                readSchema));

        Map<String, Object> readLogSchema = new LinkedHashMap<>();
        readLogSchema.put("type", "object");
        Map<String, Object> readLogProps = new LinkedHashMap<>();
        readLogProps.put("path", prop("string", "File path relative to workspace"));
        readLogProps.put("tailBytes", prop("integer", "Optional number of bytes to return from the end of the file"));
        readLogSchema.put("properties", readLogProps);
        readLogSchema.put("required", Collections.singletonList("path"));
        allTools.add(new LlmRequest.ToolDef("read_log",
                "Read the tail of a log, build output, or runtime trace file from the workspace.",
                readLogSchema));

        Map<String, Object> validateSchema = new LinkedHashMap<>();
        validateSchema.put("type", "object");
        Map<String, Object> validateProps = new LinkedHashMap<>();
        validateProps.put("focus", prop("string", "Optional validation focus such as compile, startup, poc, or full"));
        validateSchema.put("properties", validateProps);
        allTools.add(new LlmRequest.ToolDef("validate_artifacts",
                "Run backend-controlled validation for build, startup, and PoC evidence. Use this instead of ad-hoc curl loops whenever possible.",
                validateSchema));

        Map<String, Object> inspectRuntimeSchema = new LinkedHashMap<>();
        inspectRuntimeSchema.put("type", "object");
        inspectRuntimeSchema.put("properties", Collections.emptyMap());
        allTools.add(new LlmRequest.ToolDef("inspect_runtime",
                "Return the current runtime metadata and validator-known evidence for the running lab.",
                inspectRuntimeSchema));

        Map<String, Object> finishSchema = new LinkedHashMap<>();
        finishSchema.put("type", "object");
        Map<String, Object> finishProps = new LinkedHashMap<>();
        finishProps.put("vuln_demo_status", prop("string", "Status: startup_ok, compile_ok, compile_failed"));
        finishProps.put("poc_status", prop("string", "Status: verified, unverified, skipped"));
        finishProps.put("report_status", prop("string", "Status: generated, skipped"));
        finishProps.put("verification_evidence", prop("string", "Concrete evidence showing why the PoC is verified or still missing a signal"));
        finishProps.put("remaining_gap", prop("string", "If unverified, the exact missing condition or blocker"));
        finishProps.put("notes", prop("string", "Any notes about the generation"));
        finishSchema.put("properties", finishProps);
        allTools.add(new LlmRequest.ToolDef("finish",
                "Call when all deliverables are ready. Provide a summary of what was generated.",
                finishSchema));

        if (ctx == null) {
            return allTools;
        }

        List<LlmRequest.ToolDef> tools = new ArrayList<>();
        for (LlmRequest.ToolDef tool : allTools) {
            if (isToolAllowed(ctx.phase, tool.getName())) {
                tools.add(tool);
            }
        }
        return tools;
    }

    private Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private Map<String, Object> stringArrayProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("description", description);
        p.put("items", Collections.singletonMap("type", "string"));
        return p;
    }

    // ==================== Tool Execution ====================

    private String executeToolCall(AgentContext ctx, LlmRequest.ContentBlock toolUse) {
        String toolName = toolUse.getToolName();
        JsonNode input = toolUse.getToolInput();
        log.info("Executing tool: {}", toolName);

        try {
            switch (toolName) {
                case "submit_plan": return doSubmitPlan(ctx, input);
                case "write_file":  return doWriteFile(ctx, input);
                case "write_files": return doWriteFiles(ctx, input);
                case "read_file":   return doReadFile(ctx, input);
                case "read_log":    return doReadLog(ctx, input);
                case "validate_artifacts": return doValidateArtifacts(ctx, input);
                case "inspect_runtime": return doInspectRuntime(ctx);
                default:            return "Unknown tool: " + toolName;
            }
        } catch (Exception e) {
            log.error("Tool {} failed: {}", toolName, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private String doSubmitPlan(AgentContext ctx, JsonNode input) {
        ExecutionPlan plan = ExecutionPlan.fromJson(input);
        if (!plan.isUsable()) {
            return "Error: submit_plan requires goal, firstBatchFiles, validationSequence, and reportStrategy";
        }
        ctx.executionPlan = plan;
        ctx.phase = AgentPhase.GENERATE_MINIMAL;
        ctx.lastDirective = buildPhaseDirective(ctx, ctx.lastValidation, false);
        return "Plan accepted. Phase switched to " + ctx.phase.name() + ".\n"
                + renderPhaseDirective(ctx.lastDirective);
    }

    private String doWriteFile(AgentContext ctx, JsonNode input) throws IOException {
        String path = input.path("path").asText("");
        String content = input.path("content").asText("");

        if (path.isEmpty() || content.isEmpty()) return "Error: path and content are required";

        writeWorkspaceFile(ctx, path, content);
        return "ok";
    }

    private String doWriteFiles(AgentContext ctx, JsonNode input) throws IOException {
        JsonNode files = input.path("files");
        if (!files.isArray() || files.size() == 0) {
            return "Error: files array is required";
        }

        int written = 0;
        for (JsonNode item : files) {
            String path = item.path("path").asText("");
            String content = item.path("content").asText("");
            if (path.isEmpty() || content.isEmpty()) {
                return "Error: each file entry requires path and content";
            }
            writeWorkspaceFile(ctx, path, content);
            written++;
        }
        return "ok: wrote " + written + " files";
    }

    private String doReadFile(AgentContext ctx, JsonNode input) throws IOException {
        String path = input.path("path").asText("");
        if (path.isEmpty()) return "Error: path is required";
        if (path.contains("..")) return "Error: path traversal not allowed";

        Path target = ctx.cvePath.resolve(path);
        if (!Files.exists(target)) return "Error: file not found: " + path;

        byte[] bytes = Files.readAllBytes(target);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String doReadLog(AgentContext ctx, JsonNode input) throws IOException {
        String path = input.path("path").asText("");
        if (path.isEmpty()) return "Error: path is required";
        if (path.contains("..")) return "Error: path traversal not allowed";

        Path target = ctx.cvePath.resolve(path);
        if (!Files.exists(target)) return "Error: file not found: " + path;
        if (Files.isDirectory(target)) return "Error: not a file: " + path;

        int requestedTailBytes = input.path("tailBytes").asInt(OUTPUT_TRUNCATE);
        int tailBytes = Math.max(1, Math.min(requestedTailBytes, PROCESS_OUTPUT_BUFFER));
        return readTailBytes(target, tailBytes);
    }

    private String readTailBytes(Path target, int tailBytes) throws IOException {
        long size = Files.size(target);
        int bytesToRead = (int) Math.min(size, (long) tailBytes);
        if (bytesToRead <= 0) {
            return "";
        }

        byte[] bytes = new byte[bytesToRead];
        try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "r")) {
            if (size > bytesToRead) {
                raf.seek(size - bytesToRead);
            }
            raf.readFully(bytes);
        }

        int offset = 0;
        if (size > bytesToRead) {
            while (offset < bytes.length && (bytes[offset] & 0xC0) == 0x80) {
                offset++;
            }
        }

        String content = new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
        if (size > bytesToRead) {
            return "...[truncated]\n" + content;
        }
        return content;
    }

    private String doValidateArtifacts(AgentContext ctx, JsonNode input) throws Exception {
        String focus = input.path("focus").asText("full").trim();
        ValidationResult result = validateArtifacts(ctx, focus.isEmpty() ? "full" : focus);
        ctx.lastValidation = result;
        return renderJson(result.toMap());
    }

    private String doInspectRuntime(AgentContext ctx) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("phase", ctx.phase.name());
        info.put("compileStatus", deriveCompileStatus(ctx));
        info.put("startupStatus", deriveStartupStatus(ctx));
        info.put("writtenFiles", new ArrayList<>(ctx.writtenFiles));
        if (ctx.lastValidation != null) {
            info.put("lastValidation", ctx.lastValidation.toMap());
        }
        if (ctx.lastDirective != null) {
            info.put("directive", ctx.lastDirective.toMap());
        }
        return renderJson(info);
    }

    private String doStartApp(AgentContext ctx) throws Exception {
        ctx.pipeCtx.reportProgress("Backend: starting vuln-demo");

        stopTrackedAppProcess(ctx);
        String cleanup = stopStaleWorkspaceProcesses(ctx);
        if (!cleanup.trim().isEmpty()) {
            log.info("Stale vuln-demo cleanup before startup: {}", singleLine(cleanup, 600));
        }

        if (isLocalPortOpen(VULN_DEMO_PORT)) {
            String diagnostics = describePortUsers(ctx);
            String message = "STARTUP BLOCKED — port " + VULN_DEMO_PORT + " is already in use before launch."
                    + (diagnostics.trim().isEmpty()
                    ? "\nPort accepts TCP connections, but the owning process could not be identified."
                    : "\n\n" + diagnostics);
            ctx.startupHistory.add(ToolRun.startup(false, 98, message));
            log.warn("{}", singleLine(message, 1000));
            return message;
        }

        Path vulnDemoPath = ctx.cvePath.resolve("vuln-demo");
        ProcessBuilder pb = new ProcessBuilder("bash", "run.sh");
        pb.directory(vulnDemoPath.toFile());
        pb.redirectErrorStream(true);
        ctx.appProcess = pb.start();
        ctx.appOutput = new ProcessOutputBuffer(ctx.appProcess.getInputStream(), PROCESS_OUTPUT_BUFFER);

        long deadline = System.currentTimeMillis() + STARTUP_WAIT * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!ctx.appProcess.isAlive()) {
                ctx.appOutput.await(1, TimeUnit.SECONDS);
                String output = ctx.appOutput.content();
                ctx.startupHistory.add(ToolRun.startup(false, -1, output));
                return "STARTUP FAILED — process exited\n\n" + truncate(output, OUTPUT_TRUNCATE);
            }
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(
                        "http://localhost:" + VULN_DEMO_PORT + "/").openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code > 0) {
                    log.info("  App started on port {}", VULN_DEMO_PORT);
                    ctx.startupHistory.add(ToolRun.startup(true, code,
                            "Application started on port " + VULN_DEMO_PORT + " (HTTP " + code + ")"));
                    return "Application started on port " + VULN_DEMO_PORT + " (HTTP " + code + ")";
                }
            } catch (Exception ignored) {}
            Thread.sleep(1000);
        }

        String output = ctx.appOutput != null ? ctx.appOutput.content() : "";
        if (ctx.appProcess.isAlive()) {
            ctx.startupHistory.add(ToolRun.startup(false, 124, output));
            return "STARTUP TIMEOUT after " + STARTUP_WAIT + "s — app process still running but port not responding\n\n"
                    + truncate(output, 2000);
        }
        ctx.startupHistory.add(ToolRun.startup(false, -1, output));
        return "STARTUP FAILED — process died during startup\n\n" + truncate(output, OUTPUT_TRUNCATE);
    }

    private void writeWorkspaceFile(AgentContext ctx, String path, String content) throws IOException {
        if (!path.startsWith("vuln-demo/") && !path.startsWith("poc/") && !path.startsWith("report/")) {
            throw new IOException("path must start with vuln-demo/, poc/, or report/");
        }
        if (path.contains("..")) {
            throw new IOException("path traversal not allowed");
        }
        if ("vuln-demo/build.sh".equals(path) || "vuln-demo/run.sh".equals(path)) {
            throw new IOException(path + " is managed by the backend (JAVA_HOME, Maven settings). Do not modify it. "
                    + "If you need a different Java version or build configuration, adjust pom.xml instead.");
        }

        Path target = ctx.cvePath.resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
        if (path.endsWith(".sh")) {
            target.toFile().setExecutable(true);
        }
        ctx.writtenFiles.add(path);
        log.info("  Wrote: {} ({} bytes)", path, content.length());
    }

    // ==================== Process Management ====================

    private void stopTrackedAppProcess(AgentContext ctx) {
        if (ctx.appProcess == null) {
            return;
        }
        try {
            if (ctx.appProcess.isAlive()) {
                ctx.appProcess.destroy();
                if (!ctx.appProcess.waitFor(3, TimeUnit.SECONDS)) {
                    ctx.appProcess.destroyForcibly();
                    ctx.appProcess.waitFor(5, TimeUnit.SECONDS);
                }
                log.info("Stopped tracked vuln-demo process");
            }
            if (ctx.appOutput != null) {
                ctx.appOutput.await(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ctx.appProcess = null;
            ctx.appOutput = null;
        }
    }

    private String stopStaleWorkspaceProcesses(AgentContext ctx) {
        String workspace = ctx.cvePath.resolve("vuln-demo").toAbsolutePath().normalize().toString();
        String script = ""
                + "workspace=" + shellQuote(workspace) + "\n"
                + "pids=''\n"
                + "for d in /proc/[0-9]*; do\n"
                + "  pid=${d#/proc/}\n"
                + "  cwd=$(readlink \"$d/cwd\" 2>/dev/null || true)\n"
                + "  [ \"$cwd\" = \"$workspace\" ] || continue\n"
                + "  cmd=$(tr '\\0' ' ' < \"$d/cmdline\" 2>/dev/null || true)\n"
                + "  case \"$cmd\" in\n"
                + "    *java*target/*|*java*--server.port=" + VULN_DEMO_PORT + "*) pids=\"$pids $pid\" ;;\n"
                + "  esac\n"
                + "done\n"
                + "if [ -z \"$pids\" ]; then exit 0; fi\n"
                + "echo \"stopping stale vuln-demo pids:$pids\"\n"
                + "kill $pids 2>/dev/null || true\n"
                + "sleep 2\n"
                + "for pid in $pids; do\n"
                + "  if kill -0 \"$pid\" 2>/dev/null; then\n"
                + "    kill -KILL \"$pid\" 2>/dev/null || true\n"
                + "    echo \"force killed $pid\"\n"
                + "  fi\n"
                + "done\n";
        ProcessResult pr = runProcess(ctx.cvePath, 8, "bash", "-c", script);
        if (pr.exitCode != 0) {
            return "stale process cleanup exited " + pr.exitCode + ": " + pr.output;
        }
        return pr.output;
    }

    private boolean isLocalPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String describePortUsers(AgentContext ctx) {
        String script = ""
                + "if command -v lsof >/dev/null 2>&1; then\n"
                + "  lsof -nP -iTCP:" + VULN_DEMO_PORT + " -sTCP:LISTEN 2>/dev/null || true\n"
                + "elif command -v ss >/dev/null 2>&1; then\n"
                + "  ss -ltnp 'sport = :" + VULN_DEMO_PORT + "' 2>/dev/null || true\n"
                + "elif command -v netstat >/dev/null 2>&1; then\n"
                + "  netstat -ltnp 2>/dev/null | grep ':" + VULN_DEMO_PORT + " ' || true\n"
                + "fi\n";
        ProcessResult pr = runProcess(ctx.cvePath, 5, "bash", "-c", script);
        return truncate(pr.output, 1200);
    }

    private ProcessResult runProcess(Path workDir, int timeoutSec, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            ProcessOutputBuffer output = new ProcessOutputBuffer(proc.getInputStream(), PROCESS_OUTPUT_BUFFER);
            boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                proc.waitFor(5, TimeUnit.SECONDS);
                output.await(1, TimeUnit.SECONDS);
                return new ProcessResult(124, "TIMEOUT after " + timeoutSec + "s\n" + output.content());
            }
            output.await(1, TimeUnit.SECONDS);
            return new ProcessResult(proc.exitValue(), output.content());
        } catch (Exception e) {
            return new ProcessResult(-1, "Process error: " + e.getMessage());
        }
    }

    // ==================== Skeleton ====================

    private void writeMinimalSkeleton(Path vulnDemoPath, JavaProfile profile) throws IOException {
        Files.createDirectories(vulnDemoPath.resolve("src/main/java/com/jvuln/demo"));
        Files.createDirectories(vulnDemoPath.resolve("src/main/resources"));
        Files.createDirectories(vulnDemoPath.getParent().resolve("poc"));
        Files.createDirectories(vulnDemoPath.getParent().resolve("report"));

        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <parent>\n"
                + "        <groupId>org.springframework.boot</groupId>\n"
                + "        <artifactId>spring-boot-starter-parent</artifactId>\n"
                + "        <version>" + profile.getSpringBootVersion() + "</version>\n"
                + "        <relativePath/>\n"
                + "    </parent>\n"
                + "    <groupId>com.jvuln</groupId>\n"
                + "    <artifactId>vuln-demo</artifactId>\n"
                + "    <version>0.0.1-SNAPSHOT</version>\n"
                + "    <packaging>jar</packaging>\n"
                + "    <properties>\n"
                + "        <java.version>" + profile.getMavenJavaVersion() + "</java.version>\n"
                + "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
                + "    </properties>\n"
                + "    <dependencies>\n"
                + "        <dependency>\n"
                + "            <groupId>org.springframework.boot</groupId>\n"
                + "            <artifactId>spring-boot-starter-web</artifactId>\n"
                + "        </dependency>\n"
                + "    </dependencies>\n"
                + "    <build>\n"
                + "        <plugins>\n"
                + "            <plugin>\n"
                + "                <groupId>org.springframework.boot</groupId>\n"
                + "                <artifactId>spring-boot-maven-plugin</artifactId>\n"
                + "            </plugin>\n"
                + "        </plugins>\n"
                + "    </build>\n"
                + "</project>\n";
        writeSkeletonFile(vulnDemoPath.resolve("pom.xml"), pom, false, false);

        String app = "// FOR AUTHORIZED SECURITY EDUCATION ONLY\n"
                + "package com.jvuln.demo;\n\n"
                + "import org.springframework.boot.SpringApplication;\n"
                + "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n"
                + "@SpringBootApplication\n"
                + "public class Application {\n"
                + "    public static void main(String[] args) {\n"
                + "        SpringApplication.run(Application.class, args);\n"
                + "    }\n"
                + "}\n";
        writeSkeletonFile(vulnDemoPath.resolve("src/main/java/com/jvuln/demo/Application.java"), app, false, false);

        String labInfo = "// FOR AUTHORIZED SECURITY EDUCATION ONLY\n"
                + "package com.jvuln.demo;\n\n"
                + "import java.io.File;\n"
                + "import java.util.LinkedHashMap;\n"
                + "import java.util.Map;\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.web.bind.annotation.RestController;\n\n"
                + "@RestController\n"
                + "public class LabInfoController {\n"
                + "    @GetMapping(\"/\")\n"
                + "    public Map<String, Object> index() {\n"
                + "        Map<String, Object> out = new LinkedHashMap<String, Object>();\n"
                + "        out.put(\"status\", \"ok\");\n"
                + "        out.put(\"service\", \"jvuln-demo\");\n"
                + "        return out;\n"
                + "    }\n\n"
                + "    @GetMapping(\"/api/lab/info\")\n"
                + "    public Map<String, Object> info() {\n"
                + "        Map<String, Object> out = new LinkedHashMap<String, Object>();\n"
                + "        out.put(\"status\", \"ok\");\n"
                + "        out.put(\"userDir\", new File(\".\").getAbsoluteFile().getParentFile().getAbsolutePath());\n"
                + "        out.put(\"javaVersion\", System.getProperty(\"java.version\", \"\"));\n"
                + "        out.put(\"tmpdir\", System.getProperty(\"java.io.tmpdir\", \"\"));\n"
                + "        out.put(\"port\", System.getProperty(\"server.port\", \"18080\"));\n"
                + "        return out;\n"
                + "    }\n"
                + "}\n";
        writeSkeletonFile(vulnDemoPath.resolve("src/main/java/com/jvuln/demo/LabInfoController.java"),
                labInfo, false, false);

        String properties = "server.port=" + VULN_DEMO_PORT + "\n"
                + "spring.main.banner-mode=off\n"
                + "logging.level.root=INFO\n";
        writeSkeletonFile(vulnDemoPath.resolve("src/main/resources/application.properties"),
                properties, false, false);

        String javaHome = profile.getJavaHome();
        String build = "#!/bin/bash\ncd \"$(dirname \"$0\")\"\n"
                + "export JAVA_HOME=\"" + javaHome + "\"\n"
                + "export PATH=\"$JAVA_HOME/bin:$PATH\"\n"
                + "mvn package -DskipTests -q\n";
        writeSkeletonFile(vulnDemoPath.resolve("build.sh"), build, true, false);

        String run = "#!/bin/bash\ncd \"$(dirname \"$0\")\"\n"
                + "export JAVA_HOME=\"" + javaHome + "\"\n"
                + "export PATH=\"$JAVA_HOME/bin:$PATH\"\n"
                + "exec java -jar target/*.jar --server.port=" + VULN_DEMO_PORT + "\n";
        writeSkeletonFile(vulnDemoPath.resolve("run.sh"), run, true, false);

        log.info("Ensured baseline skeleton: pom.xml, Application.java, LabInfoController.java, application.properties, build.sh, run.sh");
    }

    private void writeSkeletonFile(Path path, String content, boolean executable, boolean overwrite) throws IOException {
        if (Files.exists(path) && !overwrite) {
            if (executable) {
                path.toFile().setExecutable(true);
            }
            return;
        }
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        if (executable) {
            path.toFile().setExecutable(true);
        }
    }

    // ==================== Data Extraction ====================

    private String trimIntelligence(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        ObjectNode out = mapper.createObjectNode();
        copyField(root, out, "cveId");
        copyField(root, out, "cweId");
        copyField(root, out, "description");
        copyField(root, out, "cvss");
        copyField(root, out, "fixedVersion");
        copyField(root, out, "artifact");
        copyField(root, out, "affectedVersions");
        return mapper.writeValueAsString(out);
    }

    private String extractDiff(PipelineContext ctx, Object data, int cap) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode rawDiff = root.path("rawDiff");
        if (!rawDiff.isMissingNode() && rawDiff.isTextual()) {
            String d = rawDiff.asText();
            return d.length() > cap ? d.substring(0, cap) + "\n...[truncated]" : d;
        }
        Path diffFile = ctx.getWorkspacePath().resolve("patches/fix.diff");
        if (Files.exists(diffFile)) {
            String d = new String(Files.readAllBytes(diffFile), StandardCharsets.UTF_8);
            return d.length() > cap ? d.substring(0, cap) + "\n...[truncated]" : d;
        }
        String full = mapper.writeValueAsString(data);
        return full.length() > cap ? full.substring(0, cap) + "\n...[truncated]" : full;
    }

    private String extractVulnerabilityFacts(Object data) throws Exception {
        if (data == null) return "{}";
        JsonNode root = mapper.valueToTree(data);
        JsonNode facts = root.path("vulnerabilityFacts");
        return facts.isMissingNode() ? "{}" : mapper.writeValueAsString(facts);
    }

    private String extractTriggerChain(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode chain = root.path("trigger_chain");
        return !chain.isMissingNode() ? mapper.writeValueAsString(chain) : "{}";
    }

    private String extractRootCause(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode analysis = root.path("code_analysis");
        if (!analysis.isMissingNode()) {
            ObjectNode out = mapper.createObjectNode();
            copyField(analysis, out, "vuln_root_cause");
            copyField(analysis, out, "fix_description");
            return mapper.writeValueAsString(out);
        }
        return "{}";
    }

    private String extractArtifact(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode artifact = root.path("artifact");
        if (!artifact.isMissingNode()) {
            return artifact.isTextual() ? artifact.asText() : mapper.writeValueAsString(artifact);
        }
        return "";
    }

    // ==================== Java Profile Selection ====================

    private JavaProfile resolveJavaProfile(PipelineContext ctx, Object rawIntelligence) {
        List<JavaProfile> profiles = javaProfileRepo.findAll();
        if (profiles.isEmpty()) {
            return getHardcodedFallback();
        }

        try {
            JsonNode intel = mapper.valueToTree(rawIntelligence);
            String groupId = intel.at("/artifact/groupId").asText("");
            String artifactId = intel.at("/artifact/artifactId").asText("");
            String affectedTo = intel.at("/affectedVersions/to").asText("");
            String fixedVersion = intel.at("/fixedVersion").asText("");

            StringBuilder profileList = new StringBuilder();
            for (JavaProfile p : profiles) {
                profileList.append(String.format("- %s: Java %s, Spring Boot %s%s\n",
                    p.getName(), p.getJavaVersion(), p.getSpringBootVersion(),
                    Boolean.TRUE.equals(p.getIsDefault()) ? " (default)" : ""));
            }

            String sysPrompt = "You are a Java/Spring Boot/library compatibility expert. " +
                "Given a CVE's affected component and the available Java profiles, " +
                "select the best profile AND recommend a Spring Boot version that is compatible " +
                "with the vulnerable library version.\n\n" +
                "Return strict JSON: {\"profile\": \"profile-name\", \"springBootVersion\": \"x.y.z\"}\n" +
                "The springBootVersion must be compatible with the vulnerable library version. " +
                "For example, Tomcat 9.x needs Spring Boot 2.7.x, Tomcat 10.1.x needs Spring Boot 3.x, " +
                "Tomcat 11.x needs Spring Boot 3.4.x+. Return ONLY the JSON.";
            String userPrompt = String.format(
                "CVE artifact: %s:%s\n" +
                "Affected versions: %s\n" +
                "Fixed version: %s\n\n" +
                "Available profiles:\n%s",
                groupId, artifactId, affectedTo, fixedVersion, profileList.toString()
            );

            LlmRequest req = LlmRequest.reasoning(sysPrompt, userPrompt);
            LlmResponse resp = ctx.getLlmClient().chat(req);
            String raw = resp.getContent().trim();

            // Strip markdown code fences if present
            if (raw.startsWith("```")) {
                raw = raw.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("(?s)\\n?```$", "").trim();
            }

            JsonNode result = mapper.readTree(raw);
            String selectedName = result.path("profile").asText("").trim();
            String recommendedSBVersion = result.path("springBootVersion").asText("").trim();

            JavaProfile selected = profiles.stream()
                .filter(p -> p.getName().equalsIgnoreCase(selectedName))
                .findFirst()
                .orElse(null);

            if (selected != null) {
                if (!recommendedSBVersion.isEmpty() && !recommendedSBVersion.equals(selected.getSpringBootVersion())) {
                    log.info("LLM recommends Spring Boot {} (profile default: {})", recommendedSBVersion, selected.getSpringBootVersion());
                    // Clone profile with overridden Spring Boot version
                    JavaProfile overridden = new JavaProfile();
                    overridden.setName(selected.getName());
                    overridden.setJavaVersion(selected.getJavaVersion());
                    overridden.setJavaHome(selected.getJavaHome());
                    overridden.setSpringBootVersion(recommendedSBVersion);
                    overridden.setMavenJavaVersion(selected.getMavenJavaVersion());
                    overridden.setSyntaxConstraints(selected.getSyntaxConstraints());
                    return overridden;
                }
                log.info("LLM selected Java profile: {}", selectedName);
                return selected;
            } else {
                log.warn("LLM returned unknown profile '{}', using default", selectedName);
            }
        } catch (Exception e) {
            log.warn("Error during LLM-based Java profile resolution: {}", e.getMessage());
        }

        return profiles.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsDefault()))
                .findFirst()
                .orElse(profiles.get(0));
    }

    private JavaProfile getHardcodedFallback() {
        JavaProfile fallback = new JavaProfile();
        fallback.setName("Default (Java 8)");
        fallback.setJavaVersion("8");
        fallback.setJavaHome(System.getProperty("java.home", "/usr/lib/jvm/java-8-openjdk-amd64"));
        fallback.setSpringBootVersion("2.7.18");
        fallback.setMavenJavaVersion("1.8");
        fallback.setSyntaxConstraints("Java 8 syntax only (no var, no List.of, no records, no text blocks)");
        fallback.setIsDefault(Boolean.TRUE);
        return fallback;
    }

    // ==================== Utilities ====================

    private void copyField(JsonNode src, ObjectNode dst, String field) {
        JsonNode v = src.path(field);
        if (!v.isMissingNode()) dst.set(field, v);
    }

    private AttemptMemory loadAttemptMemory(Path file) {
        if (!Files.exists(file)) {
            return new AttemptMemory();
        }
        try {
            return AttemptMemory.fromJson(mapper.readTree(file.toFile()));
        } catch (Exception e) {
            log.warn("Could not load Stage 4 memory: {}", e.getMessage());
            return new AttemptMemory();
        }
    }

    private void persistAttemptMemory(Path file, AgentContext ctx, String event, String reason) {
        try {
            AttemptMemory memory = ctx.attemptMemory != null ? ctx.attemptMemory : new AttemptMemory();
            memory.turns = ctx.turns;
            memory.reviewRevisions = ctx.reviewRevisions;
            memory.verificationPlan = ctx.verificationPlan;
            memory.executionPlan = ctx.executionPlan;
            memory.lastValidation = ctx.lastValidation;
            memory.lastReview = ctx.verificationReview;
            memory.sessionSummary = ctx.sessionSummary;
            memory.buildHistory.clear();
            memory.buildHistory.addAll(ctx.buildHistory);
            memory.startupHistory.clear();
            memory.startupHistory.addAll(ctx.startupHistory);
            memory.commandHistory.clear();
            memory.commandHistory.addAll(ctx.commandHistory);
            memory.addRecord(AttemptRecord.fromContext(event, reason,
                    deriveCompileStatus(ctx), deriveStartupStatus(ctx),
                    ctx.verificationReview != null ? ctx.verificationReview.pocStatus
                            : (ctx.summary != null ? ctx.summary.path("poc_status").asText("") : ""),
                    ctx.turns, ctx.reviewRevisions, ctx.lastValidation));
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), memory.toMap());
            ctx.attemptMemory = memory;
        } catch (Exception e) {
            log.warn("Failed to persist Stage 4 memory: {}", e.getMessage());
        }
    }

    private void restoreAgentContextFromMemory(AgentContext ctx, AttemptMemory memory) {
        if (memory == null) {
            return;
        }
        // Do NOT restore executionPlan — Agent must always create a fresh plan
        // based on the current CVE context. Prior plans may be stale or wrong.
        ctx.lastValidation = memory.lastValidation;
        ctx.verificationReview = memory.lastReview;
        ctx.sessionSummary = memory.sessionSummary == null ? "" : memory.sessionSummary;
        restoreToolRuns(ctx.buildHistory, memory.buildHistory);
        restoreToolRuns(ctx.startupHistory, memory.startupHistory);
        restoreToolRuns(ctx.commandHistory, memory.commandHistory);
    }

    private void restoreToolRuns(List<ToolRun> target, List<ToolRun> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        target.clear();
        target.addAll(source);
    }

    private String renderAttemptMemory(AttemptMemory memory) {
        StringBuilder sb = new StringBuilder();
        if (memory.executionPlan != null) {
            sb.append("Execution plan:\n");
            sb.append(renderJson(memory.executionPlan.toMap())).append("\n");
        }
        if (memory.lastValidation != null) {
            sb.append("Latest validator result:\n");
            sb.append(renderJson(memory.lastValidation.toMap())).append("\n");
        }
        if (memory.lastReview != null) {
            sb.append("Latest review result:\n");
            sb.append(renderJson(memory.lastReview.toMap())).append("\n");
        }
        if (memory.sessionSummary != null && !memory.sessionSummary.trim().isEmpty()) {
            sb.append("Compacted session summary:\n");
            sb.append(memory.sessionSummary.trim()).append("\n");
        }
        if (!memory.records.isEmpty()) {
            sb.append("Recent failures and decisions:\n");
            for (AttemptRecord record : memory.records) {
                sb.append("- [").append(record.event).append("] ");
                if (!record.reason.isEmpty()) {
                    sb.append(record.reason).append(" | ");
                }
                sb.append("compile=").append(record.compileStatus)
                        .append(", startup=").append(record.startupStatus)
                        .append(", poc=").append(record.pocStatus)
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    private VerificationPlan buildVerificationPlan(PipelineContext ctx, String intelligence,
                                                   String vulnerabilityFacts, String triggerChain,
                                                   String rootCause, String patchDiff, String artifact) {
        try {
            String systemPrompt = promptRegistry.getSystemPrompt("gen_verification_plan");
            String userTemplate = promptRegistry.getUserPrompt("gen_verification_plan");
            Map<String, String> vars = new HashMap<>();
            vars.put("intelligence", intelligence);
            vars.put("vulnerability_facts", vulnerabilityFacts);
            vars.put("trigger_chain", triggerChain);
            vars.put("root_cause", rootCause);
            vars.put("patch_diff", patchDiff);
            vars.put("artifact", artifact);
            String userPrompt = promptRegistry.render(userTemplate, vars);
            LlmResponse response = chatWithRetry(ctx, LlmRequest.reasoning(systemPrompt, userPrompt), 2);
            return VerificationPlan.fromJson(parseJsonObject(response.getContent()));
        } catch (Exception e) {
            log.warn("Verification plan generation failed, using fallback: {}", e.getMessage());
            return VerificationPlan.fallback(artifact, triggerChain, rootCause);
        }
    }

    private VerificationReview reviewGeneratedArtifacts(PipelineContext ctx, AgentContext agentCtx, JsonNode finishSummary,
                                                        String intelligence, String vulnerabilityFacts,
                                                        String triggerChain, String rootCause, String patchDiff,
                                                        String artifact) {
        try {
            String systemPrompt = promptRegistry.getSystemPrompt("gen_verifier");
            String userTemplate = promptRegistry.getUserPrompt("gen_verifier");
            Map<String, String> vars = new HashMap<>();
            vars.put("intelligence", intelligence);
            vars.put("vulnerability_facts", vulnerabilityFacts);
            vars.put("trigger_chain", triggerChain);
            vars.put("root_cause", rootCause);
            vars.put("patch_diff", patchDiff);
            vars.put("artifact", artifact);
            vars.put("verification_plan", renderJson(agentCtx.verificationPlan.toMap()));
            vars.put("finish_summary", finishSummary == null ? "{}" : finishSummary.toPrettyString());
            vars.put("execution_evidence", renderJson(buildVerificationEvidence(agentCtx, finishSummary)));
            String userPrompt = promptRegistry.render(userTemplate, vars);
            LlmResponse response = chatWithRetry(ctx, LlmRequest.reasoning(systemPrompt, userPrompt), 2);
            return reconcileReviewWithBackend(
                    VerificationReview.fromJson(parseJsonObject(response.getContent())), agentCtx, finishSummary);
        } catch (Exception e) {
            log.warn("Verification review failed, using fallback: {}", e.getMessage());
            return VerificationReview.fallback(finishSummary, deriveCompileStatus(agentCtx),
                    deriveStartupStatus(agentCtx), agentCtx.lastValidation);
        }
    }

    private VerificationReview reconcileReviewWithBackend(VerificationReview review, AgentContext agentCtx,
                                                          JsonNode finishSummary) {
        ValidationResult validation = agentCtx.lastValidation;
        boolean backendVerified = validation != null
                && validation.compileOk && validation.startupOk && validation.pocVerified;
        if (!backendVerified || review == null || "verified".equals(review.pocStatus)) {
            return review;
        }

        log.warn("Reviewer returned {} despite backend validation success; using backend verified verdict. reason={}",
                review.pocStatus, singleLine(review.reason, 300));
        return VerificationReview.fallback(finishSummary, deriveCompileStatus(agentCtx),
                deriveStartupStatus(agentCtx), validation);
    }

    private Map<String, Object> buildVerificationEvidence(AgentContext ctx, JsonNode finishSummary) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("compileStatus", deriveCompileStatus(ctx));
        evidence.put("startupStatus", deriveStartupStatus(ctx));
        evidence.put("writtenFiles", new ArrayList<>(ctx.writtenFiles));
        evidence.put("reviewRevisions", ctx.reviewRevisions);
        evidence.put("finishSummary", finishSummary == null ? mapper.createObjectNode() : finishSummary);
        evidence.put("buildHistory", recentHistory(ctx.buildHistory));
        evidence.put("startupHistory", recentHistory(ctx.startupHistory));
        evidence.put("commandHistory", recentHistory(ctx.commandHistory));

        Map<String, String> snippets = collectKeyFileSnippets(ctx);
        if (!snippets.isEmpty()) {
            evidence.put("keyFileSnippets", snippets);
        }
        return evidence;
    }

    private List<Map<String, Object>> recentHistory(List<ToolRun> history) {
        if (history.isEmpty()) {
            return Collections.emptyList();
        }
        int from = Math.max(0, history.size() - REVIEW_HISTORY_ITEMS);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = from; i < history.size(); i++) {
            items.add(history.get(i).toMap());
        }
        return items;
    }

    private Map<String, String> collectKeyFileSnippets(AgentContext ctx) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add("vuln-demo/pom.xml");
        candidates.add("vuln-demo/src/main/resources/application.properties");
        candidates.add("poc/exploit.sh");
        candidates.add("report/report.md");
        for (String path : ctx.writtenFiles) {
            if (path.startsWith("vuln-demo/src/main/resources/")) {
                candidates.add(path);
            }
        }
        for (String path : ctx.writtenFiles) {
            if (path.startsWith("vuln-demo/src/main/java/")) {
                candidates.add(path);
            }
        }

        Map<String, String> snippets = new LinkedHashMap<>();
        for (String path : candidates) {
            if (snippets.size() >= 6) break;
            Path file = ctx.cvePath.resolve(path);
            if (!Files.exists(file) || Files.isDirectory(file)) {
                continue;
            }
            try {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                snippets.put(path, truncate(content, REVIEW_FILE_SNIPPET));
            } catch (IOException ignored) {
                // Ignore unreadable evidence files and continue with the rest.
            }
        }
        return snippets;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(s.length() - max) : s;
    }

    private String buildReviewerFeedback(VerificationReview review, int revision) {
        StringBuilder sb = new StringBuilder();
        sb.append("finish() rejected by reviewer.\n");
        sb.append("Verdict: ").append(review.verdict).append("\n");
        if (!review.reason.isEmpty()) {
            sb.append("Reason: ").append(review.reason).append("\n");
        }
        appendBulletSection(sb, "Matched evidence", review.matchedSignals);
        appendBulletSection(sb, "Missing evidence", review.missingEvidence);
        appendBulletSection(sb, "False-positive risks", review.falsePositiveRisks);
        appendBulletSection(sb, "Required next actions", review.nextActions);
        sb.append("Revision budget used: ").append(revision).append("/").append(MAX_REVIEW_REVISIONS).append(".\n");
        sb.append("Modify vuln-demo and/or poc files, rerun build/start/command, then call finish() again.");
        return sb.toString();
    }

    private String buildFinishAcceptedMessage(VerificationReview review) {
        if (review == null) {
            return "finish() accepted";
        }
        return "finish() accepted. Reviewer verdict: " + review.verdict
                + (review.reason.isEmpty() ? "" : ". " + review.reason);
    }

    private void appendBulletSection(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append(title).append(":\n");
        for (String item : items) {
            sb.append("- ").append(item).append("\n");
        }
    }

    private JsonNode mergeSummaryWithReview(JsonNode summary, VerificationReview review) {
        ObjectNode merged = summary != null && summary.isObject()
                ? ((ObjectNode) summary.deepCopy())
                : mapper.createObjectNode();
        if (review != null && !review.pocStatus.isEmpty()) {
            merged.put("poc_status", review.pocStatus);
        }

        String reviewerReason = review != null ? review.reason : "";
        String existingNotes = merged.path("notes").asText("").trim();
        if (!existingNotes.isEmpty() && !reviewerReason.isEmpty() && !existingNotes.contains(reviewerReason)) {
            merged.put("notes", existingNotes + " | Reviewer: " + reviewerReason);
        } else if (existingNotes.isEmpty() && !reviewerReason.isEmpty()) {
            merged.put("notes", reviewerReason);
        }

        if (review != null && !review.missingEvidence.isEmpty()) {
            merged.put("remaining_gap", joinItems(review.missingEvidence, "; "));
        }
        return merged;
    }

    private String renderJson(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String toolDefNames(List<LlmRequest.ToolDef> tools) {
        if (tools == null || tools.isEmpty()) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        for (LlmRequest.ToolDef tool : tools) {
            names.add(tool.getName());
        }
        return names.toString();
    }

    private String toolUseNames(List<LlmRequest.ContentBlock> toolUses) {
        if (toolUses == null || toolUses.isEmpty()) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        for (LlmRequest.ContentBlock block : toolUses) {
            names.add(block.getToolName());
        }
        return names.toString();
    }

    private String responsePreview(LlmResponse response) {
        if (response == null) {
            return "";
        }
        String content = response.getContent();
        if (content != null && !content.trim().isEmpty()) {
            return singleLine(content, 260);
        }
        StringBuilder sb = new StringBuilder();
        for (LlmRequest.ContentBlock block : response.getContentBlocks()) {
            if ("text".equals(block.getType()) && block.getText() != null && !block.getText().trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(block.getText().trim());
            }
        }
        return singleLine(sb.toString(), 260);
    }

    private String summarizeToolInput(String toolName, JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "{}";
        }
        if ("write_file".equals(toolName)) {
            return "{path=" + input.path("path").asText("") + ", contentLength="
                    + input.path("content").asText("").length() + "}";
        }
        if ("write_files".equals(toolName)) {
            List<String> paths = new ArrayList<>();
            JsonNode files = input.path("files");
            if (files.isArray()) {
                for (JsonNode file : files) {
                    paths.add(file.path("path").asText(""));
                }
            }
            return "{count=" + paths.size() + ", paths=" + paths + "}";
        }
        if ("read_file".equals(toolName)) {
            return "{path=" + input.path("path").asText("") + "}";
        }
        if ("read_log".equals(toolName)) {
            return "{path=" + input.path("path").asText("")
                    + ", tailBytes=" + input.path("tailBytes").asInt(OUTPUT_TRUNCATE) + "}";
        }
        if ("validate_artifacts".equals(toolName)) {
            return "{focus=" + input.path("focus").asText("full") + "}";
        }
        if ("submit_plan".equals(toolName)) {
            return "{goal=" + singleLine(input.path("goal").asText(""), 120)
                    + ", firstBatchFiles=" + input.path("firstBatchFiles").size()
                    + ", validationSequence=" + input.path("validationSequence").size() + "}";
        }
        return singleLine(input.toString(), 260);
    }

    private String singleLine(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 15)) + "...[truncated]";
    }

    private String extractJsonString(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(raw);
            return node.path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String shellEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "'\"'\"'");
    }

    private String shellQuote(String value) {
        return "'" + shellEscape(value) + "'";
    }

    private String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private String stripMarkdownFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline < 0) {
            return s;
        }
        s = s.substring(firstNewline + 1);
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    private JsonNode parseJsonObject(String raw) throws IOException {
        String s = stripMarkdownFence(raw);
        if (s.isEmpty()) {
            throw new IOException("Empty JSON response");
        }
        try {
            JsonNode node = mapper.readTree(s);
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (Exception ignored) {
            // Fall through and try to extract the first balanced JSON object.
        }

        int start = s.indexOf('{');
        if (start < 0) {
            throw new IOException("No JSON object found in response: " + singleLine(s, 200));
        }
        int end = findJsonObjectEnd(s, start);
        if (end < 0) {
            throw new IOException("Unbalanced JSON object in response: " + singleLine(s, 200));
        }
        return mapper.readTree(s.substring(start, end + 1));
    }

    private int findJsonObjectEnd(String s, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String joinItems(List<String> items, String delimiter) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (item == null || item.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(delimiter);
            }
            sb.append(item.trim());
        }
        return sb.toString();
    }

    private String deriveCompileStatus(AgentContext ctx) {
        if (!ctx.buildHistory.isEmpty()) {
            return ctx.buildHistory.get(ctx.buildHistory.size() - 1).success ? "compile_ok" : "compile_failed";
        }
        Path targetDir = ctx.cvePath.resolve("vuln-demo/target");
        if (Files.exists(targetDir)) {
            java.io.File[] jars = targetDir.toFile().listFiles(f -> f.getName().endsWith(".jar"));
            if (jars != null && jars.length > 0) {
                return "compile_ok";
            }
        }
        return "not_started";
    }

    private String deriveStartupStatus(AgentContext ctx) {
        if (!ctx.startupHistory.isEmpty()) {
            return ctx.startupHistory.get(ctx.startupHistory.size() - 1).success ? "startup_ok" : "startup_failed";
        }
        String compileStatus = deriveCompileStatus(ctx);
        if ("compile_ok".equals(compileStatus) || "compile_failed".equals(compileStatus)) {
            return "skipped";
        }
        return "not_started";
    }

    private void updateProgressGuard(AgentContext ctx, boolean wroteFiles) {
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

    private String progressSignature(AgentContext ctx) {
        String validation = ctx.lastValidation == null ? "none" : ctx.lastValidation.summary();
        String gap = ctx.lastDirective == null ? "" : ctx.lastDirective.gap;
        return String.valueOf(ctx.phase) + "|" + validation + "|" + gap + "|review=" + ctx.reviewRevisions;
    }

    @SuppressWarnings("unchecked")
    private String determineFailureReason(Map<String, Object> output) {
        Object vulnDemoObj = output.get("vulnDemo");
        if (vulnDemoObj instanceof Map) {
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

    private AgentPhase inferPhase(AgentContext ctx) {
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
        String compileStatus = deriveCompileStatus(ctx);
        if (!"compile_ok".equals(compileStatus)) {
            return ctx.writtenFiles.size() <= 3 ? AgentPhase.GENERATE_MINIMAL : AgentPhase.COMPILE_FIX;
        }
        String startupStatus = deriveStartupStatus(ctx);
        if (!"startup_ok".equals(startupStatus)) {
            return AgentPhase.STARTUP_FIX;
        }
        return AgentPhase.POC_FIX;
    }

    private PhaseDirective currentDirective(AgentContext ctx) {
        if (ctx.lastDirective != null && ctx.lastDirective.phase == ctx.phase) {
            return ctx.lastDirective;
        }
        return buildPhaseDirective(ctx, ctx.lastValidation, false);
    }

    private boolean isToolAllowed(AgentPhase phase, String toolName) {
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

    private AgentPhase nextPhaseAfterValidation(AgentContext ctx, ValidationResult result, int remainingTurns) {
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

    private PhaseDirective buildPhaseDirective(AgentContext ctx, ValidationResult result, boolean forceReport) {
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

    private String renderPhaseDirective(PhaseDirective directive) {
        StringBuilder sb = new StringBuilder();
        sb.append("BACKEND DIRECTIVE\n");
        sb.append(renderJson(directive.toMap())).append("\n");

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

        prompt.append("RECENT BUILD HISTORY:\n").append(renderJson(recentHistory(ctx.buildHistory))).append("\n\n");
        prompt.append("RECENT STARTUP HISTORY:\n").append(renderJson(recentHistory(ctx.startupHistory))).append("\n\n");
        prompt.append("RECENT COMMAND HISTORY:\n").append(renderJson(recentHistory(ctx.commandHistory))).append("\n\n");

        prompt.append("Analyze the root cause in 2-3 sentences. What specifically failed and why? ");
        prompt.append("Suggest concrete next steps to fix it. Be specific about file names and line numbers if applicable.");

        try {
            LlmRequest req = LlmRequest.reasoning(
                "You are a Java/Spring Boot/CVE expert analyzing build/runtime failures.",
                prompt.toString()
            );
            LlmResponse response = chatWithRetry(ctx.pipeCtx, req, 1);
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

    private boolean requiresExecutionPlan(String toolName) {
        return !("submit_plan".equals(toolName) || "read_file".equals(toolName) || "read_log".equals(toolName));
    }

    private WriteScope inspectWriteScope(String toolName, JsonNode input) {
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

    private String determineAutoValidationFocus(AgentPhase phaseBeforeTurn, AgentContext ctx, boolean wroteVulnDemo, boolean wrotePoc) {
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

    private String buildAutoValidationFeedback(AgentContext ctx, ValidationResult result, String focus, boolean wroteReport) {
        StringBuilder sb = new StringBuilder();
        sb.append("AUTO VALIDATION after your latest file batch (focus=").append(focus).append("):\n");
        sb.append(renderJson(result.toMap())).append("\n");

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

    private ValidationResult validateArtifacts(AgentContext ctx, String focus) throws Exception {
        String normalized = focus == null ? "full" : focus.trim().toLowerCase(Locale.ROOT);
        ValidationResult result = new ValidationResult(normalized);

        if (shouldValidateCompile(normalized)) {
            ProcessResult build = runProcess(ctx.cvePath.resolve("vuln-demo"), COMPILE_TIMEOUT, "bash", "build.sh");
            ToolRun buildRun = ToolRun.build(build.exitCode, build.output);
            ctx.buildHistory.add(buildRun);
            result.compileOk = build.exitCode == 0;
            result.compileMessage = truncate(build.output, OUTPUT_TRUNCATE);
            if (!result.compileOk || "compile".equals(normalized)) {
                return result;
            }
        } else {
            result.compileOk = "compile_ok".equals(deriveCompileStatus(ctx));
        }

        if (shouldValidateStartup(normalized)) {
            String startupMessage = doStartApp(ctx);
            ToolRun startupRun = ctx.startupHistory.isEmpty() ? null : ctx.startupHistory.get(ctx.startupHistory.size() - 1);
            result.startupOk = startupRun != null && startupRun.success;
            result.startupMessage = truncate(startupMessage, OUTPUT_TRUNCATE);
            if (!result.startupOk || "startup".equals(normalized)) {
                return result;
            }
        } else {
            result.startupOk = "startup_ok".equals(deriveStartupStatus(ctx));
        }

        if (shouldValidatePoc(normalized)) {
            ValidationResult pocResult = validatePoc(ctx);
            result.mergeFrom(pocResult);
        }
        return result;
    }

    private boolean shouldValidateCompile(String focus) {
        return "full".equals(focus) || "compile".equals(focus) || "startup".equals(focus) || "poc".equals(focus);
    }

    private boolean shouldValidateStartup(String focus) {
        return "full".equals(focus) || "startup".equals(focus) || "poc".equals(focus);
    }

    private boolean shouldValidatePoc(String focus) {
        return "full".equals(focus) || "poc".equals(focus);
    }

    private ValidationResult validatePoc(AgentContext ctx) {
        ValidationResult result = new ValidationResult("poc");

        Path pocScript = ctx.cvePath.resolve("poc/exploit.sh");
        if (!Files.exists(pocScript)) {
            result.pocVerified = false;
            result.pocMessage = "PoC script not found: poc/exploit.sh";
            return result;
        }

        ProcessResult pr = runProcess(ctx.cvePath, COMMAND_TIMEOUT, "bash", "poc/exploit.sh");
        ctx.commandHistory.add(ToolRun.command("bash poc/exploit.sh", pr.exitCode, pr.output));
        result.pocVerified = pr.exitCode == 0;
        result.pocMessage = truncate(pr.output, OUTPUT_TRUNCATE);
        return result;
    }

    // ==================== Inner Classes ====================

    private static class ToolRun {
        final String kind;
        final String command;
        final int exitCode;
        final boolean success;
        final String output;

        private ToolRun(String kind, String command, int exitCode, boolean success, String output) {
            this.kind = kind;
            this.command = command;
            this.exitCode = exitCode;
            this.success = success;
            this.output = output;
        }

        static ToolRun build(int exitCode, String output) {
            return new ToolRun("build", "bash build.sh", exitCode, exitCode == 0, output);
        }

        static ToolRun startup(boolean success, int exitCode, String output) {
            return new ToolRun("startup", "bash run.sh", exitCode, success, output);
        }

        static ToolRun command(String command, int exitCode, String output) {
            return new ToolRun("command", command, exitCode, exitCode == 0, output);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("command", command);
            out.put("exitCode", exitCode);
            out.put("success", success);
            out.put("output", output);
            return out;
        }
    }

    private static class WriteScope {
        boolean vulnDemo;
        boolean poc;
        boolean report;
    }

    private enum AgentPhase {
        PLAN,
        GENERATE_MINIMAL,
        COMPILE_FIX,
        STARTUP_FIX,
        POC_FIX,
        REPORT,
        FINISHED
    }

    private static class PhaseDirective {
        final AgentPhase phase;
        final String gap;
        final String expected;
        final String actual;
        final String fixHint;
        final List<String> allowedNextActions;

        PhaseDirective(AgentPhase phase, String gap, String expected, String actual,
                       String fixHint, List<String> allowedNextActions) {
            this.phase = phase;
            this.gap = gap == null ? "" : gap;
            this.expected = expected == null ? "" : expected;
            this.actual = actual == null ? "" : actual;
            this.fixHint = fixHint == null ? "" : fixHint;
            this.allowedNextActions = allowedNextActions == null
                    ? Collections.<String>emptyList()
                    : new ArrayList<>(allowedNextActions);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("phase", phase == null ? "" : phase.name());
            out.put("gap", gap);
            out.put("expected", expected);
            out.put("actual", actual);
            out.put("fixHint", fixHint);
            out.put("allowedNextActions", allowedNextActions);
            return out;
        }
    }

    private static class ValidationResult {
        final String focus;
        boolean compileOk;
        boolean startupOk;
        boolean pocVerified;
        String compileMessage = "";
        String startupMessage = "";
        String pocMessage = "";
        final Map<String, Object> artifacts = new LinkedHashMap<>();

        ValidationResult(String focus) {
            this.focus = focus == null ? "full" : focus;
        }

        void mergeFrom(ValidationResult other) {
            if (other == null) {
                return;
            }
            this.compileOk = this.compileOk || other.compileOk;
            this.startupOk = this.startupOk || other.startupOk;
            this.pocVerified = this.pocVerified || other.pocVerified;
            if (this.compileMessage.isEmpty()) this.compileMessage = other.compileMessage;
            if (this.startupMessage.isEmpty()) this.startupMessage = other.startupMessage;
            if (this.pocMessage.isEmpty()) this.pocMessage = other.pocMessage;
            this.artifacts.putAll(other.artifacts);
        }

        String summary() {
            return "compileOk=" + compileOk + ", startupOk=" + startupOk + ", pocVerified=" + pocVerified
                    + (pocMessage.isEmpty() ? "" : ", pocMessage=" + pocMessage);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("focus", focus);
            out.put("compileOk", compileOk);
            out.put("startupOk", startupOk);
            out.put("pocVerified", pocVerified);
            out.put("compileMessage", compileMessage);
            out.put("startupMessage", startupMessage);
            out.put("pocMessage", pocMessage);
            out.put("artifacts", artifacts);
            return out;
        }

        static ValidationResult fromJson(JsonNode node) {
            ValidationResult result = new ValidationResult(node.path("focus").asText("full"));
            result.compileOk = node.path("compileOk").asBoolean(false);
            result.startupOk = node.path("startupOk").asBoolean(false);
            result.pocVerified = node.path("pocVerified").asBoolean(false);
            result.compileMessage = node.path("compileMessage").asText("");
            result.startupMessage = node.path("startupMessage").asText("");
            result.pocMessage = node.path("pocMessage").asText("");
            JsonNode artifactsNode = node.path("artifacts");
            if (artifactsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = artifactsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    result.artifacts.put(field.getKey(), field.getValue());
                }
            }
            return result;
        }
    }

    private static class AttemptRecord {
        String event = "";
        String reason = "";
        int turns;
        int reviewRevisions;
        String compileStatus = "";
        String startupStatus = "";
        String pocStatus = "";
        String validatorSummary = "";

        static AttemptRecord fromContext(String event, String reason, String compileStatus,
                                         String startupStatus, String pocStatus, int turns,
                                         int reviewRevisions, ValidationResult validation) {
            AttemptRecord record = new AttemptRecord();
            record.event = event == null ? "" : event;
            record.reason = reason == null ? "" : reason;
            record.turns = turns;
            record.reviewRevisions = reviewRevisions;
            record.compileStatus = compileStatus == null ? "" : compileStatus;
            record.startupStatus = startupStatus == null ? "" : startupStatus;
            record.pocStatus = pocStatus == null ? "" : pocStatus;
            record.validatorSummary = validation == null ? "" : validation.summary();
            return record;
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("event", event);
            out.put("reason", reason);
            out.put("turns", turns);
            out.put("reviewRevisions", reviewRevisions);
            out.put("compileStatus", compileStatus);
            out.put("startupStatus", startupStatus);
            out.put("pocStatus", pocStatus);
            out.put("validatorSummary", validatorSummary);
            return out;
        }

        static AttemptRecord fromJson(JsonNode node) {
            AttemptRecord record = new AttemptRecord();
            record.event = node.path("event").asText("");
            record.reason = node.path("reason").asText("");
            record.turns = node.path("turns").asInt(0);
            record.reviewRevisions = node.path("reviewRevisions").asInt(0);
            record.compileStatus = node.path("compileStatus").asText("");
            record.startupStatus = node.path("startupStatus").asText("");
            record.pocStatus = node.path("pocStatus").asText("");
            record.validatorSummary = node.path("validatorSummary").asText("");
            return record;
        }
    }

    private static class AttemptMemory {
        int turns;
        int reviewRevisions;
        VerificationPlan verificationPlan;
        ExecutionPlan executionPlan;
        VerificationReview lastReview;
        ValidationResult lastValidation;
        String sessionSummary = "";
        final List<ToolRun> buildHistory = new ArrayList<>();
        final List<ToolRun> startupHistory = new ArrayList<>();
        final List<ToolRun> commandHistory = new ArrayList<>();
        final List<AttemptRecord> records = new ArrayList<>();

        boolean hasRecords() {
            return !records.isEmpty() || lastValidation != null || lastReview != null;
        }

        void addRecord(AttemptRecord record) {
            if (record == null) {
                return;
            }
            records.add(record);
            while (records.size() > MEMORY_RECORD_LIMIT) {
                records.remove(0);
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("turns", turns);
            out.put("reviewRevisions", reviewRevisions);
            if (verificationPlan != null) out.put("verificationPlan", verificationPlan.toMap());
            if (executionPlan != null) out.put("executionPlan", executionPlan.toMap());
            if (lastReview != null) out.put("lastReview", lastReview.toMap());
            if (lastValidation != null) out.put("lastValidation", lastValidation.toMap());
            if (sessionSummary != null && !sessionSummary.trim().isEmpty()) out.put("sessionSummary", sessionSummary);
            out.put("buildHistory", toolRunsToMaps(buildHistory));
            out.put("startupHistory", toolRunsToMaps(startupHistory));
            out.put("commandHistory", toolRunsToMaps(commandHistory));
            List<Map<String, Object>> recordMaps = new ArrayList<>();
            for (AttemptRecord record : records) {
                recordMaps.add(record.toMap());
            }
            out.put("records", recordMaps);
            return out;
        }

        static AttemptMemory fromJson(JsonNode node) {
            AttemptMemory memory = new AttemptMemory();
            memory.turns = node.path("turns").asInt(0);
            memory.reviewRevisions = node.path("reviewRevisions").asInt(0);
            if (node.has("verificationPlan")) {
                memory.verificationPlan = VerificationPlan.fromJson(node.path("verificationPlan"));
            }
            if (node.has("executionPlan")) {
                memory.executionPlan = ExecutionPlan.fromJson(node.path("executionPlan"));
            }
            if (node.has("lastReview")) {
                memory.lastReview = VerificationReview.fromJson(node.path("lastReview"));
            }
            if (node.has("lastValidation")) {
                memory.lastValidation = ValidationResult.fromJson(node.path("lastValidation"));
            }
            memory.sessionSummary = node.path("sessionSummary").asText("");
            readToolRuns(node.path("buildHistory"), memory.buildHistory);
            readToolRuns(node.path("startupHistory"), memory.startupHistory);
            readToolRuns(node.path("commandHistory"), memory.commandHistory);
            JsonNode recordsNode = node.path("records");
            if (recordsNode.isArray()) {
                for (JsonNode recordNode : recordsNode) {
                    memory.records.add(AttemptRecord.fromJson(recordNode));
                }
            }
            return memory;
        }

        private static List<Map<String, Object>> toolRunsToMaps(List<ToolRun> runs) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (ToolRun run : runs) {
                out.add(run.toMap());
            }
            return out;
        }

        private static void readToolRuns(JsonNode node, List<ToolRun> target) {
            if (!node.isArray()) {
                return;
            }
            for (JsonNode item : node) {
                target.add(new ToolRun(
                        item.path("kind").asText(""),
                        item.path("command").asText(""),
                        item.path("exitCode").asInt(-1),
                        item.path("success").asBoolean(false),
                        item.path("output").asText("")));
            }
        }
    }

    private static class VerificationPlan {
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

    private static class ExecutionPlan {
        final String goal;
        final List<String> firstBatchFiles;
        final List<String> minimalDeliverables;
        final List<String> validationSequence;
        final List<String> deferredUntilVerified;
        final List<String> risks;
        final String reportStrategy;

        private ExecutionPlan(String goal, List<String> firstBatchFiles, List<String> minimalDeliverables,
                              List<String> validationSequence, List<String> deferredUntilVerified,
                              List<String> risks, String reportStrategy) {
            this.goal = goal == null ? "" : goal.trim();
            this.firstBatchFiles = normalizeList(firstBatchFiles);
            this.minimalDeliverables = normalizeList(minimalDeliverables);
            this.validationSequence = normalizeList(validationSequence);
            this.deferredUntilVerified = normalizeList(deferredUntilVerified);
            this.risks = normalizeList(risks);
            this.reportStrategy = reportStrategy == null ? "" : reportStrategy.trim();
        }

        static ExecutionPlan fromJson(JsonNode node) {
            return new ExecutionPlan(
                    node.path("goal").asText(""),
                    readList(node.path("firstBatchFiles")),
                    readList(node.path("minimalDeliverables")),
                    readList(node.path("validationSequence")),
                    readList(node.path("deferredUntilVerified")),
                    readList(node.path("risks")),
                    node.path("reportStrategy").asText("")
            );
        }

        boolean isUsable() {
            return !goal.isEmpty() && !firstBatchFiles.isEmpty() && !validationSequence.isEmpty() && !reportStrategy.isEmpty();
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("goal", goal);
            out.put("firstBatchFiles", firstBatchFiles);
            out.put("minimalDeliverables", minimalDeliverables);
            out.put("validationSequence", validationSequence);
            out.put("deferredUntilVerified", deferredUntilVerified);
            out.put("risks", risks);
            out.put("reportStrategy", reportStrategy);
            return out;
        }
    }

    private static class VerificationReview {
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
    }

    private static List<String> readList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
            return out;
        }
        String text = node.asText("").trim();
        if (!text.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    private static List<String> normalizeList(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (!out.contains(value.trim())) {
                out.add(value.trim());
            }
        }
        return out;
    }

    private static String normalizeVerdict(String verdict) {
        String value = verdict == null ? "" : verdict.trim().toLowerCase(Locale.ROOT);
        if ("verified".equals(value) || "unverified".equals(value) || "skipped".equals(value)) {
            return value;
        }
        return "inconclusive";
    }

    private static String normalizePocStatus(String pocStatus, String verdict) {
        String value = pocStatus == null ? "" : pocStatus.trim().toLowerCase(Locale.ROOT);
        if ("verified".equals(value) || "unverified".equals(value) || "skipped".equals(value)) {
            return value;
        }
        if ("verified".equals(verdict) || "skipped".equals(verdict)) {
            return verdict;
        }
        return "unverified";
    }

    private static class ProcessResult {
        final int exitCode;
        final String output;
        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static class ProcessOutputBuffer {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int maxBytes;
        private final Thread reader;

        ProcessOutputBuffer(final InputStream input, int maxBytes) {
            this.maxBytes = maxBytes;
            this.reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop(input);
                }
            }, "jvuln-process-output");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        private void readLoop(InputStream input) {
            byte[] tmp = new byte[4096];
            int n;
            try {
                while ((n = input.read(tmp)) != -1) {
                    append(tmp, n);
                }
            } catch (IOException ignored) {
                // Process termination commonly closes the stream while the reader is active.
            }
        }

        private synchronized void append(byte[] bytes, int len) throws IOException {
            if (len <= 0) return;
            byte[] current = buffer.toByteArray();
            int keepCurrent = Math.max(0, maxBytes - len);
            if (current.length > keepCurrent) {
                buffer.reset();
                if (keepCurrent > 0) {
                    buffer.write(current, current.length - keepCurrent, keepCurrent);
                }
            }
            int offset = Math.max(0, len - maxBytes);
            buffer.write(bytes, offset, len - offset);
        }

        synchronized String content() {
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        void await(long timeout, TimeUnit unit) {
            try {
                reader.join(unit.toMillis(timeout));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private class AgentContext {
        final Path cvePath;
        final PipelineContext pipeCtx;
        final Set<String> writtenFiles = new LinkedHashSet<>();
        final List<String> existingFiles = new ArrayList<>();
        final List<ToolRun> buildHistory = new ArrayList<>();
        final List<ToolRun> startupHistory = new ArrayList<>();
        final List<ToolRun> commandHistory = new ArrayList<>();
        Path transcriptFile;
        Path contextSummaryFile;
        Process appProcess;
        ProcessOutputBuffer appOutput;
        JsonNode summary;
        AttemptMemory attemptMemory;
        VerificationPlan verificationPlan;
        ExecutionPlan executionPlan;
        VerificationReview verificationReview;
        ValidationResult lastValidation;
        PhaseDirective lastDirective;
        AgentPhase phase = AgentPhase.PLAN;
        JavaProfile javaProfile;
        Map<String, FileSnapshot> baselineSnapshot = new LinkedHashMap<>();
        Map<String, FileSnapshot> previousSnapshot = new LinkedHashMap<>();
        String baseUserPrompt = "";
        String sessionSummary = "";
        int turns;
        int reviewRevisions;
        int noProgressTurns;
        int compactionCount;
        String lastProgressSignature = "";
        String abortReason;

        AgentContext(Path cvePath, PipelineContext pipeCtx) {
            this.cvePath = cvePath;
            this.pipeCtx = pipeCtx;
        }

        void discoverExistingFiles() {
            for (String prefix : Arrays.asList("vuln-demo", "poc", "report")) {
                Path dir = cvePath.resolve(prefix);
                if (!Files.exists(dir)) continue;
                try {
                    Files.walk(dir)
                            .filter(p -> Files.isRegularFile(p) && !p.toString().contains("/target/"))
                            .forEach(p -> {
                                String rel = cvePath.relativize(p).toString();
                                existingFiles.add(rel);
                                writtenFiles.add(rel); // count as "written" so buildOutput includes them
                            });
                } catch (Exception ignored) {}
            }
        }

        void cleanup() {
            stopTrackedAppProcess(this);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> buildOutput() {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", "generated");
            output.put("agentTurns", turns);
            output.put("reviewRevisions", reviewRevisions);
            if (abortReason != null && !abortReason.trim().isEmpty()) {
                output.put("abortReason", abortReason);
            }

            // Categorize files
            List<String> vulnDemoFiles = new ArrayList<>();
            List<String> pocFiles = new ArrayList<>();
            String reportFile = null;
            for (String f : writtenFiles) {
                if (f.startsWith("vuln-demo/")) vulnDemoFiles.add(f.substring("vuln-demo/".length()));
                else if (f.startsWith("poc/")) pocFiles.add(f.substring("poc/".length()));
                else if (f.startsWith("report/")) reportFile = f;
            }
            // Always include skeleton files
            for (String sk : Arrays.asList(
                    "pom.xml",
                    "src/main/java/com/jvuln/demo/Application.java",
                    "src/main/java/com/jvuln/demo/LabInfoController.java",
                    "src/main/resources/application.properties",
                    "build.sh",
                    "run.sh")) {
                if (!vulnDemoFiles.contains(sk)) vulnDemoFiles.add(0, sk);
            }

            String compileStatus = deriveCompileStatus(this);
            String startupStatus = deriveStartupStatus(this);
            String vulnDemoStatus = "startup_ok".equals(startupStatus) ? "startup_ok"
                    : ("startup_failed".equals(startupStatus) ? "startup_failed" : compileStatus);
            String verificationStatus = verificationReview != null ? verificationReview.pocStatus
                    : (summary != null ? summary.path("poc_status").asText("")
                    : (lastValidation != null && lastValidation.pocVerified ? "verified" : ""));

            ToolRun lastBuild = buildHistory.isEmpty() ? null : buildHistory.get(buildHistory.size() - 1);
            ToolRun lastStartup = startupHistory.isEmpty() ? null : startupHistory.get(startupHistory.size() - 1);

            output.put("compileStatus", compileStatus);
            output.put("startupStatus", startupStatus);
            output.put("pocStatus", verificationStatus.isEmpty() ? "unknown" : verificationStatus);
            output.put("verificationStatus", verificationStatus.isEmpty() ? "unknown" : verificationStatus);
            output.put("attemptsUsed", turns);

            Map<String, Object> vulnDemo = new LinkedHashMap<>();
            vulnDemo.put("status", vulnDemoStatus);
            vulnDemo.put("compileStatus", compileStatus);
            vulnDemo.put("startupStatus", startupStatus);
            if (lastBuild != null) {
                vulnDemo.put("compileMessage", truncate(lastBuild.output, 600));
            }
            if (lastStartup != null) {
                vulnDemo.put("startupMessage", truncate(lastStartup.output, 600));
            }
            vulnDemo.put("files", vulnDemoFiles);
            output.put("vulnDemo", vulnDemo);

            Map<String, Object> poc = new LinkedHashMap<>();
            String pocStatus = verificationStatus.isEmpty()
                    ? (!pocFiles.isEmpty() ? "unverified" : "skipped")
                    : verificationStatus;
            poc.put("status", pocStatus);
            poc.put("files", pocFiles);
            if (verificationReview != null) {
                poc.put("reason", verificationReview.reason);
                poc.put("nextActions", verificationReview.nextActions);
            } else if (summary != null) {
                String reason = summary.path("remaining_gap").asText("").trim();
                if (reason.isEmpty()) {
                    reason = summary.path("notes").asText("").trim();
                }
                if (!reason.isEmpty()) {
                    poc.put("reason", reason);
                }
            }
            output.put("poc", poc);

            String verificationSummary = "";
            if (verificationReview != null && verificationReview.reason != null) {
                verificationSummary = verificationReview.reason.trim();
            }
            if (verificationSummary.isEmpty() && summary != null) {
                verificationSummary = summary.path("remaining_gap").asText("").trim();
                if (verificationSummary.isEmpty()) {
                    verificationSummary = summary.path("notes").asText("").trim();
                }
            }
            if (verificationSummary.isEmpty() && lastValidation != null) {
                verificationSummary = lastValidation.summary();
            }
            if (!verificationSummary.isEmpty()) {
                output.put("verificationSummary", verificationSummary);
            }

            Map<String, Object> report = new LinkedHashMap<>();
            String rptStatus;
            if (summary != null) {
                rptStatus = summary.path("report_status").asText("unknown");
            } else {
                rptStatus = reportFile != null ? "generated" : "skipped";
            }
            report.put("status", rptStatus);
            if (reportFile != null) report.put("file", reportFile);
            output.put("report", report);

            // All files list (for frontend compatibility) — include skeleton files
            Set<String> allPaths = new LinkedHashSet<>();
            for (String sk : Arrays.asList(
                    "vuln-demo/pom.xml",
                    "vuln-demo/src/main/java/com/jvuln/demo/Application.java",
                    "vuln-demo/src/main/java/com/jvuln/demo/LabInfoController.java",
                    "vuln-demo/src/main/resources/application.properties",
                    "vuln-demo/build.sh",
                    "vuln-demo/run.sh")) {
                allPaths.add(sk);
            }
            allPaths.addAll(writtenFiles);
            List<Map<String, String>> allFiles = new ArrayList<>();
            for (String f : allPaths) {
                Map<String, String> entry = new HashMap<>();
                entry.put("path", f);
                if (f.startsWith("vuln-demo/")) entry.put("type", "vuln-demo");
                else if (f.startsWith("poc/")) entry.put("type", "poc");
                else if (f.startsWith("report/")) entry.put("type", "report");
                allFiles.add(entry);
            }
            output.put("fileCount", allFiles.size());
            output.put("files", allFiles);
            if (verificationPlan != null) {
                output.put("verificationPlan", verificationPlan.toMap());
            }
            if (executionPlan != null) {
                output.put("executionPlan", executionPlan.toMap());
            }
            if (verificationReview != null) {
                output.put("verification", verificationReview.toMap());
            }
            if (lastValidation != null) {
                output.put("validation", lastValidation.toMap());
            }

            // Java Profile info
            if (javaProfile != null) {
                Map<String, Object> profileInfo = new LinkedHashMap<>();
                profileInfo.put("name", javaProfile.getName());
                profileInfo.put("javaVersion", javaProfile.getJavaVersion());
                profileInfo.put("springBootVersion", javaProfile.getSpringBootVersion());
                output.put("javaProfile", profileInfo);
            }

            // Reproduction steps — only when vuln-demo compiled successfully
            boolean compiled = "startup_ok".equals(vulnDemoStatus) || "compile_ok".equals(vulnDemoStatus);
            if (compiled) {
                List<Map<String, String>> steps = new ArrayList<>();
                int stepNum = 1;

                // Step 1: Build
                Map<String, String> buildStep = new LinkedHashMap<>();
                buildStep.put("step", String.valueOf(stepNum++));
                buildStep.put("title", "Build the vulnerable demo project");
                buildStep.put("command", "cd vuln-demo && mvn package -DskipTests -q");
                buildStep.put("description", "Compile the Spring Boot application with the vulnerable component");
                steps.add(buildStep);

                // Step 2: Start
                Map<String, String> startStep = new LinkedHashMap<>();
                startStep.put("step", String.valueOf(stepNum++));
                startStep.put("title", "Start the application");
                startStep.put("command", "cd vuln-demo && java -jar target/*.jar --server.port=18080");
                startStep.put("description", "Launch the application on port 18080. Wait for 'Started Application' in the console output");
                steps.add(startStep);

                // Step 3: PoC (if exists)
                if (!pocFiles.isEmpty()) {
                    Map<String, String> pocStep = new LinkedHashMap<>();
                    pocStep.put("step", String.valueOf(stepNum++));
                    pocStep.put("title", "Execute the PoC exploit");
                    pocStep.put("command", "bash poc/" + pocFiles.get(0));
                    pocStep.put("description", "Run the proof-of-concept script against the running application"
                            + ("unverified".equals(pocStatus) ? " (auto-generated, may require manual adjustment)" : ""));
                    steps.add(pocStep);
                }

                // Step 4: Report (if exists)
                if (reportFile != null) {
                    Map<String, String> reportStep = new LinkedHashMap<>();
                    reportStep.put("step", String.valueOf(stepNum++));
                    reportStep.put("title", "Read the vulnerability report");
                    reportStep.put("command", "cat " + reportFile);
                    reportStep.put("description", "Review the educational report explaining the vulnerability, root cause, and remediation");
                    steps.add(reportStep);
                }

                output.put("reproductionSteps", steps);
            }

            return output;
        }
    }
}

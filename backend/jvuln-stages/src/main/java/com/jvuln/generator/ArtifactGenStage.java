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
import com.jvuln.generator.workspace.WorkspaceFileRenderer;
import com.jvuln.store.JavaProfileRepository;
import com.jvuln.store.entity.JavaProfile;

import static com.jvuln.generator.ArtifactGenUtils.normalizeList;
import static com.jvuln.generator.ArtifactGenUtils.readList;
import static com.jvuln.generator.ArtifactGenUtils.truncate;
import static com.jvuln.generator.ArtifactGenUtils.truncateHead;
import static com.jvuln.generator.ArtifactGenUtils.singleLine;
import static com.jvuln.generator.ArtifactGenUtils.shellQuote;
import static com.jvuln.generator.ArtifactGenUtils.copyField;
import static com.jvuln.generator.ArtifactGenUtils.toolDefNames;
import static com.jvuln.generator.ArtifactGenUtils.toolUseNames;
import static com.jvuln.generator.ArtifactGenUtils.extractJsonString;
import static com.jvuln.generator.GeneratorConstants.*;
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
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class ArtifactGenStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(ArtifactGenStage.class);

    private static final Set<String> COMMAND_WHITELIST = new HashSet<>(Arrays.asList(
            "curl", "wget", "grep", "cat", "ls", "find", "head", "tail",
            "bash", "test", "echo", "java", "mvn", "wc", "sort", "uniq",
            "chmod", "mkdir", "pwd", "diff", "file", "xxd", "base64", "sha256sum", "md5sum"
    ));

    private final PromptRegistry promptRegistry;
    private final ObjectMapper mapper;
    private final JavaProfileRepository javaProfileRepo;
    private final WorkspaceFileRenderer fileRenderer;
    private final AgentToolExecutor toolExecutor;
    private final AgentPhaseEngine phaseEngine;
    private final LlmHelper llmHelper;
    private final ValidationEngine validationEngine;
    private final ReviewEngine reviewEngine;
    private final AttemptMemoryManager memoryManager;
    private final SkeletonWriter skeletonWriter;
    private final ContextBuilder contextBuilder;
    private final ToolDefinitionBuilder toolDefinitionBuilder;
    private final StageDataExtractor dataExtractor;
    private final JavaProfileResolver javaProfileResolver;

    public ArtifactGenStage(PromptRegistry promptRegistry, ObjectMapper mapper,
                            JavaProfileRepository javaProfileRepo,
                            WorkspaceFileRenderer fileRenderer,
                            AgentToolExecutor toolExecutor,
                            AgentPhaseEngine phaseEngine,
                            LlmHelper llmHelper,
                            ValidationEngine validationEngine,
                            ReviewEngine reviewEngine,
                            AttemptMemoryManager memoryManager,
                            SkeletonWriter skeletonWriter,
                            ContextBuilder contextBuilder,
                            ToolDefinitionBuilder toolDefinitionBuilder,
                            StageDataExtractor dataExtractor,
                            JavaProfileResolver javaProfileResolver) {
        this.promptRegistry = promptRegistry;
        this.mapper = mapper;
        this.javaProfileRepo = javaProfileRepo;
        this.fileRenderer = fileRenderer;
        this.toolExecutor = toolExecutor;
        this.phaseEngine = phaseEngine;
        this.llmHelper = llmHelper;
        this.validationEngine = validationEngine;
        this.reviewEngine = reviewEngine;
        this.memoryManager = memoryManager;
        this.skeletonWriter = skeletonWriter;
        this.contextBuilder = contextBuilder;
        this.toolDefinitionBuilder = toolDefinitionBuilder;
        this.dataExtractor = dataExtractor;
        this.javaProfileResolver = javaProfileResolver;
    }

    @Override
    public int number() { return 4; }

    @Override
    public String name() { return "Artifact Generation"; }

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        ctx.reportProgress("Starting agent-based artifact generation");

        Object rawIntelligence = ctx.getCompletedStages().get(1).getData();
        String intelligence = dataExtractor.trimIntelligence(rawIntelligence);
        log.info("Stage 4 intelligence (first 500 chars): {}", intelligence.substring(0, Math.min(500, intelligence.length())));
        String patchDiff = dataExtractor.extractDiff(ctx, ctx.getCompletedStages().get(2).getData(), 4000);
        StageResult stage2 = ctx.getCompletedStages().get(2);
        String vulnerabilityFacts = dataExtractor.extractVulnerabilityFacts(stage2 != null ? stage2.getData() : null);
        String triggerChain = dataExtractor.extractTriggerChain(ctx.getCompletedStages().get(3).getData());
        String rootCause = dataExtractor.extractRootCause(ctx.getCompletedStages().get(3).getData());
        String artifact = dataExtractor.extractArtifact(rawIntelligence);
        JavaProfile javaProfile = javaProfileResolver.resolveJavaProfile(ctx, rawIntelligence);
        log.info("Selected Java profile: {} (Java {} / Spring Boot {})",
                javaProfile.getName(), javaProfile.getJavaVersion(), javaProfile.getSpringBootVersion());
        ctx.reportProgress("Using Java profile: " + javaProfile.getName());
        VerificationPlan verificationPlan = buildVerificationPlan(ctx, intelligence, vulnerabilityFacts,
                triggerChain, rootCause, patchDiff, artifact);

        Path cvePath = ctx.getWorkspaceManager().getCvePath(ctx.getCveId());
        AgentContext agentCtx = new AgentContext(cvePath, ctx);
        agentCtx.setToolExecutor(toolExecutor);
        agentCtx.verificationPlan = verificationPlan;
        agentCtx.javaProfile = javaProfile;
        Path checkpointFile = cvePath.resolve("stages/4_checkpoint.json");
        Path memoryFile = cvePath.resolve("stages/4_memory.json");

        try {
            skeletonWriter.writeMinimalSkeleton(cvePath.resolve("vuln-demo"), javaProfile);
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
            vars.put("verification_plan", llmHelper.renderJson(verificationPlan.toMap()));
            vars.put("java_version", javaProfile.getJavaVersion());
            vars.put("spring_boot_version", javaProfile.getSpringBootVersion());
            vars.put("syntax_constraints", javaProfile.getSyntaxConstraints() != null
                    ? javaProfile.getSyntaxConstraints()
                    : "Java " + javaProfile.getJavaVersion() + " syntax");
            String systemPrompt = promptRegistry.render(systemTemplate, vars);
            String baseUserPrompt = promptRegistry.render(userTemplate, vars);

            AttemptMemory memory = memoryManager.loadAttemptMemory(memoryFile);
            agentCtx.attemptMemory = memory;
            memoryManager.restoreAgentContextFromMemory(agentCtx, memory);

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
            agentCtx.phase = phaseEngine.inferPhase(agentCtx);

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
                    sb.append(llmHelper.renderJson(agentCtx.executionPlan.toMap())).append("\n");
                }
                if (memory.hasRecords()) {
                    sb.append("\n## ATTEMPT MEMORY\n");
                    sb.append(memoryManager.renderAttemptMemory(memory));
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
            agentCtx.transcriptFile = cvePath.resolve("stages/4_transcript.jsonl");
            agentCtx.contextSummaryFile = cvePath.resolve("stages/4_context_summary.md");
            agentCtx.baselineSnapshot = contextBuilder.captureWorkspaceSnapshot(agentCtx);
            agentCtx.previousSnapshot = new LinkedHashMap<>(agentCtx.baselineSnapshot);
            contextBuilder.appendTranscript(agentCtx, "session_start", 0, contextBuilder.buildSessionStartEvent(agentCtx, resumedTurns));

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
                    contextBuilder.appendTranscript(agentCtx, "directive", turn + 1, "NOTICE: 20 turns remain.");
                } else if (remaining == REPORT_FALLBACK_TURNS) {
                    if (agentCtx.phase != AgentPhase.REPORT) {
                        if (agentCtx.lastValidation == null) {
                            agentCtx.lastValidation = validationEngine.validateArtifacts(agentCtx, "full");
                        }
                        agentCtx.phase = AgentPhase.REPORT;
                        agentCtx.lastDirective = phaseEngine.buildPhaseDirective(agentCtx, agentCtx.lastValidation, true);
                        String directive = "BACKEND PHASE SWITCH: REPORT\n" + phaseEngine.renderPhaseDirective(agentCtx.lastDirective);
                        messages.add(LlmRequest.Message.user(directive));
                        contextBuilder.appendTranscript(agentCtx, "directive", turn + 1, directive);
                    }
                    String warning = "FINAL WARNING: 5 turns remain. Stop debugging. Write the report with the current evidence "
                            + "and remaining gap, then call finish().";
                    messages.add(LlmRequest.Message.user(warning));
                    contextBuilder.appendTranscript(agentCtx, "directive", turn + 1, warning);
                }

                contextBuilder.compactMessagesIfNeeded(messages, agentCtx, "before_turn_" + (turn + 1));
                String contextPacket = contextBuilder.buildContextPacket(agentCtx, turn + 1, MAX_AGENT_TURNS);
                messages.add(LlmRequest.Message.user(contextPacket));
                contextBuilder.appendTranscript(agentCtx, "context_packet", turn + 1, contextPacket);

                List<LlmRequest.ToolDef> tools = toolDefinitionBuilder.buildToolDefinitions(agentCtx);
                LlmResponse response;
                try {
                    long llmStart = System.currentTimeMillis();
                    log.info("Agent LLM request: turn={} phase={} tools={} messages={}",
                            turn + 1, agentCtx.phase, toolDefNames(tools), messages.size());
                    response = llmHelper.chatWithRetry(ctx,
                            LlmRequest.agent(systemPrompt, messages, tools), 3);
                    log.info("Agent LLM response: turn={} phase={} durationMs={} finishReason={} text='{}' toolUses={}",
                            turn + 1, agentCtx.phase, System.currentTimeMillis() - llmStart,
                            response.getFinishReason(), contextBuilder.responsePreview(response), toolUseNames(response.getToolUses()));
                } catch (Exception e) {
                    // LLM call failed after all retries — save checkpoint and return a failed stage with persisted pause state
                    String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Agent paused at turn {} due to LLM error: {}", turn + 1, errMsg);
                    agentCtx.turns = turn + 1;
                    saveCheckpoint(cvePath, agentCtx, errMsg);
                    memoryManager.persistAttemptMemory(memoryFile, agentCtx, "paused", errMsg);
                    Map<String, Object> output = agentCtx.buildOutput();
                    output.put("status", "paused");
                    output.put("pauseReason", errMsg);
                    output.put("pausedAtTurn", turn + 1);
                    output.remove("reproductionSteps");
                    ctx.getWorkspaceManager().writeStageData(ctx.getCveId(), 4, output);
                    ctx.reportProgress("Agent paused: " + errMsg);
                    return StageResult.failure(4, name(), "Agent paused: " + errMsg);
                }

                messages.add(LlmRequest.Message.assistantWithBlocks(response.getContentBlocks()));
                contextBuilder.appendTranscript(agentCtx, "assistant", turn + 1, contextBuilder.buildAssistantEvent(response));

                if (!response.hasToolUse()) {
                    log.info("Agent returned no tool calls: text='{}'", contextBuilder.responsePreview(response));
                    agentCtx.turns = turn + 1;
                    emptyResponses++;
                    if (emptyResponses >= MAX_EMPTY_AGENT_RESPONSES) {
                        ValidationResult forcedValidation = validationEngine.validateArtifacts(agentCtx, "full");
                        agentCtx.lastValidation = forcedValidation;
                        agentCtx.lastDirective = phaseEngine.buildPhaseDirective(agentCtx, forcedValidation, false);
                        String directive = "The backend validator was triggered because you stopped using tools.\n"
                                + phaseEngine.renderPhaseDirective(agentCtx.lastDirective);
                        messages.add(LlmRequest.Message.user(directive));
                        contextBuilder.appendTranscript(agentCtx, "directive", turn + 1, directive);
                        continue;
                    }
                    if (turn + 1 >= MAX_AGENT_TURNS) {
                        break;
                    }
                    String directive = agentCtx.executionPlan == null
                            ? "Your last response did not invoke any tool. Submit an execution plan with submit_plan first."
                            : phaseEngine.renderPhaseDirective(phaseEngine.currentDirective(agentCtx));
                    messages.add(LlmRequest.Message.user(directive));
                    contextBuilder.appendTranscript(agentCtx, "directive", turn + 1, directive);
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
                            turn + 1, agentCtx.phase, toolName, contextBuilder.summarizeToolInput(toolName, block.getToolInput()));
                    if (!phaseEngine.isToolAllowed(agentCtx.phase, toolName)) {
                        String error = "Tool '" + toolName + "' is not available in phase " + agentCtx.phase.name()
                                + ". " + phaseEngine.renderPhaseDirective(phaseEngine.currentDirective(agentCtx));
                        log.warn("Agent tool rejected: turn={} phase={} tool={} reason={}",
                                turn + 1, agentCtx.phase, toolName, singleLine(error, 300));
                        toolResults.add(LlmRequest.ContentBlock.toolResultError(block.getToolUseId(), error));
                        continue;
                    }
                    if (phaseEngine.requiresExecutionPlan(toolName) && agentCtx.executionPlan == null) {
                        String error = "Execution plan required first. Call submit_plan before any file writes.";
                        log.warn("Agent tool rejected: turn={} phase={} tool={} reason={}",
                                turn + 1, agentCtx.phase, toolName, error);
                        toolResults.add(LlmRequest.ContentBlock.toolResultError(block.getToolUseId(), error));
                        continue;
                    }

                    if ("finish".equals(block.getToolName())) {
                        VerificationReview review = reviewEngine.reviewGeneratedArtifacts(ctx, agentCtx, block.getToolInput(),
                                intelligence, vulnerabilityFacts, triggerChain, rootCause, patchDiff, artifact);
                        agentCtx.verificationReview = review;

                        boolean canRetry = review.requiresRevision()
                                && agentCtx.reviewRevisions < MAX_REVIEW_REVISIONS
                                && turn + REVIEW_CONTINUE_BUFFER < MAX_AGENT_TURNS;
                        if (canRetry) {
                            agentCtx.reviewRevisions++;
                            toolResults.add(LlmRequest.ContentBlock.toolResultError(block.getToolUseId(),
                                    reviewEngine.buildReviewerFeedback(review, agentCtx.reviewRevisions)));
                            log.info("Agent finish rejected by reviewer: verdict={} revision={}/{}",
                                    review.verdict, agentCtx.reviewRevisions, MAX_REVIEW_REVISIONS);
                        } else {
                            finished = true;
                            agentCtx.summary = reviewEngine.mergeSummaryWithReview(block.getToolInput(), review);
                            toolResults.add(LlmRequest.ContentBlock.toolResult(block.getToolUseId(),
                                    reviewEngine.buildFinishAcceptedMessage(review)));
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
                        WriteScope scope = phaseEngine.inspectWriteScope(toolName, block.getToolInput());
                        wroteVulnDemo = wroteVulnDemo || scope.vulnDemo;
                        wrotePoc = wrotePoc || scope.poc;
                        wroteReport = wroteReport || scope.report;
                    }
                }

                messages.add(LlmRequest.Message.toolResults(toolResults));
                contextBuilder.appendTranscript(agentCtx, "tool_results", turn + 1, contextBuilder.buildToolResultEvent(toolResults));

                agentCtx.turns = turn + 1;

                if (!finished && (wroteVulnDemo || wrotePoc)) {
                    String focus = phaseEngine.determineAutoValidationFocus(phaseBeforeTurn, agentCtx, wroteVulnDemo, wrotePoc);
                    log.info("Agent auto-validation start: turn={} previousPhase={} focus={} wroteVulnDemo={} wrotePoc={}",
                            turn + 1, phaseBeforeTurn, focus, wroteVulnDemo, wrotePoc);
                    ValidationResult autoValidation = validationEngine.validateArtifacts(agentCtx, focus);
                    agentCtx.lastValidation = autoValidation;
                    agentCtx.phase = phaseEngine.nextPhaseAfterValidation(agentCtx, autoValidation, remaining - 1);
                    agentCtx.lastDirective = phaseEngine.buildPhaseDirective(agentCtx, autoValidation, false);
                    log.info("Agent auto-validation done: turn={} nextPhase={} result={}",
                            turn + 1, agentCtx.phase, autoValidation.summary());
                    String directive = phaseEngine.renderPhaseDirective(agentCtx.lastDirective);
                    messages.add(LlmRequest.Message.user(directive));
                    contextBuilder.appendTranscript(agentCtx, "directive", turn + 1, directive);
                } else if (!finished && wroteReport && agentCtx.phase == AgentPhase.REPORT) {
                    agentCtx.lastDirective = phaseEngine.buildPhaseDirective(agentCtx, agentCtx.lastValidation, false);
                    String directive = phaseEngine.renderPhaseDirective(agentCtx.lastDirective);
                    messages.add(LlmRequest.Message.user(directive));
                    contextBuilder.appendTranscript(agentCtx, "directive", turn + 1, directive);
                } else if (!finished && ranValidation) {
                    agentCtx.phase = phaseEngine.nextPhaseAfterValidation(agentCtx, agentCtx.lastValidation, remaining - 1);
                    agentCtx.lastDirective = phaseEngine.buildPhaseDirective(agentCtx, agentCtx.lastValidation, false);
                    log.info("Agent manual validation directive: turn={} nextPhase={} result={}",
                            turn + 1, agentCtx.phase,
                            agentCtx.lastValidation == null ? "none" : agentCtx.lastValidation.summary());
                    String directive = phaseEngine.renderPhaseDirective(agentCtx.lastDirective);
                    messages.add(LlmRequest.Message.user(directive));
                    contextBuilder.appendTranscript(agentCtx, "directive", turn + 1, directive);
                }

                if (!finished) {
                    phaseEngine.updateProgressGuard(agentCtx, wroteVulnDemo || wrotePoc || wroteReport);
                    if (agentCtx.abortReason != null) {
                        log.warn("Agent stopped by progress guard at turn {}: {}", turn + 1, agentCtx.abortReason);
                        ctx.reportProgress(agentCtx.abortReason);
                        break;
                    }
                }

                if (finished) break;
                contextBuilder.compactMessagesIfNeeded(messages, agentCtx, "after_turn_" + (turn + 1));
            }

            if (agentCtx.summary == null) {
                agentCtx.lastValidation = validationEngine.validateArtifacts(agentCtx, "full");
                agentCtx.verificationReview = reviewEngine.reviewGeneratedArtifacts(ctx, agentCtx, null,
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
                agentCtx.summary = reviewEngine.mergeSummaryWithReview(forcedSummary, agentCtx.verificationReview);
            }

            // Build output
            Map<String, Object> output = agentCtx.buildOutput();
            String failureReason = agentCtx.abortReason != null ? agentCtx.abortReason : phaseEngine.determineFailureReason(output);
            if (failureReason != null) {
                output.put("failureReason", failureReason);
            }
            memoryManager.persistAttemptMemory(memoryFile, agentCtx, failureReason == null ? "completed" : "failed",
                    failureReason == null ? "" : failureReason);
            ctx.getWorkspaceManager().writeStageData(ctx.getCveId(), 4, output);
            ctx.reportProgress("Agent completed in " + agentCtx.turns + " turns");
            if (failureReason != null) {
                return StageResult.failure(4, name(), failureReason);
            }
            return StageResult.success(4, name(), output);

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

    // ==================== LLM Call with Retry ====================


    // ==================== Tool Execution ====================

    private String executeToolCall(AgentContext ctx, LlmRequest.ContentBlock toolUse) {
        String toolName = toolUse.getToolName();
        JsonNode input = toolUse.getToolInput();
        log.info("Executing tool: {}", toolName);

        try {
            switch (toolName) {
                case "submit_plan": return doSubmitPlan(ctx, input);
                case "write_file":  return toolExecutor.doWriteFile(ctx, input);
                case "write_files": return toolExecutor.doWriteFiles(ctx, input);
                case "read_file":   return toolExecutor.doReadFile(ctx, input);
                case "read_log":    return toolExecutor.doReadLog(ctx, input);
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
        ctx.lastDirective = phaseEngine.buildPhaseDirective(ctx, ctx.lastValidation, false);
        return "Plan accepted. Phase switched to " + ctx.phase.name() + ".\n"
                + phaseEngine.renderPhaseDirective(ctx.lastDirective);
    }






    private String doValidateArtifacts(AgentContext ctx, JsonNode input) throws Exception {
        String focus = input.path("focus").asText("full").trim();
        ValidationResult result = validationEngine.validateArtifacts(ctx, focus.isEmpty() ? "full" : focus);
        ctx.lastValidation = result;
        return llmHelper.renderJson(result.toMap());
    }

    private String doInspectRuntime(AgentContext ctx) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("phase", ctx.phase.name());
        info.put("compileStatus", ctx.deriveCompileStatus());
        info.put("startupStatus", ctx.deriveStartupStatus());
        info.put("writtenFiles", new ArrayList<>(ctx.writtenFiles));
        if (ctx.lastValidation != null) {
            info.put("lastValidation", ctx.lastValidation.toMap());
        }
        if (ctx.lastDirective != null) {
            info.put("directive", ctx.lastDirective.toMap());
        }
        return llmHelper.renderJson(info);
    }

    // ==================== Utilities ====================

    private void copyField(JsonNode src, ObjectNode dst, String field) {
        JsonNode v = src.path(field);
        if (!v.isMissingNode()) dst.set(field, v);
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
            LlmResponse response = llmHelper.chatWithRetry(ctx, LlmRequest.reasoning(systemPrompt, userPrompt), 2);
            return VerificationPlan.fromJson(llmHelper.parseJsonObject(response.getContent()));
        } catch (Exception e) {
            log.warn("Verification plan generation failed, using fallback: {}", e.getMessage());
            return VerificationPlan.fallback(artifact, triggerChain, rootCause);
        }
    }

}

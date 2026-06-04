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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class ArtifactGenStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(ArtifactGenStage.class);
    private static final int MAX_AGENT_TURNS = 25;
    private static final int VULN_DEMO_PORT = 18080;
    private static final int COMPILE_TIMEOUT = 120;
    private static final int STARTUP_WAIT = 30;
    private static final int COMMAND_TIMEOUT = 60;
    private static final int OUTPUT_TRUNCATE = 4000;

    private static final Set<String> COMMAND_WHITELIST = new HashSet<>(Arrays.asList(
            "curl", "wget", "grep", "cat", "ls", "find", "head", "tail",
            "bash", "test", "echo", "java", "mvn", "wc", "sort", "uniq",
            "chmod", "mkdir", "pwd", "diff", "file", "xxd", "base64", "sha256sum", "md5sum"
    ));

    private final PromptRegistry promptRegistry;
    private final ObjectMapper mapper;

    public ArtifactGenStage(PromptRegistry promptRegistry, ObjectMapper mapper) {
        this.promptRegistry = promptRegistry;
        this.mapper = mapper;
    }

    @Override
    public int number() { return 5; }

    @Override
    public String name() { return "Artifact Generation"; }

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        ctx.reportProgress("Starting agent-based artifact generation");

        Object rawIntelligence = ctx.getCompletedStages().get(1).getData();
        String intelligence = trimIntelligence(rawIntelligence);
        String patchDiff = extractDiff(ctx.getCompletedStages().get(2).getData(), 4000);
        String triggerChain = extractTriggerChain(ctx.getCompletedStages().get(4).getData());
        String rootCause = extractRootCause(ctx.getCompletedStages().get(4).getData());
        String artifact = extractArtifact(rawIntelligence);

        Path cvePath = ctx.getWorkspaceManager().getCvePath(ctx.getCveId());
        AgentContext agentCtx = new AgentContext(cvePath, ctx);

        try {
            writeMinimalSkeleton(cvePath.resolve("vuln-demo"));
            agentCtx.discoverExistingFiles();

            String systemPrompt = promptRegistry.getSystemPrompt("gen_agent");
            String userTemplate = promptRegistry.getUserPrompt("gen_agent");
            Map<String, String> vars = new HashMap<>();
            vars.put("intelligence", intelligence);
            vars.put("trigger_chain", triggerChain);
            vars.put("root_cause", rootCause);
            vars.put("patch_diff", patchDiff);
            vars.put("artifact", artifact);
            String baseUserPrompt = promptRegistry.render(userTemplate, vars);

            // Check if resuming from a paused state
            Path checkpointFile = cvePath.resolve("stages/5_checkpoint.json");
            int startTurn = 0;
            if (Files.exists(checkpointFile)) {
                try {
                    JsonNode ckpt = mapper.readTree(checkpointFile.toFile());
                    startTurn = ckpt.path("completedTurns").asInt(0);
                    log.info("Resuming agent from turn {} (checkpoint found)", startTurn);
                    ctx.reportProgress("Resuming from turn " + startTurn);
                } catch (Exception e) {
                    log.warn("Could not read checkpoint, starting fresh: {}", e.getMessage());
                }
                Files.deleteIfExists(checkpointFile);
            }

            // Build user prompt — append resume context if continuing after a pause
            String userPrompt;
            if (startTurn > 0 && !agentCtx.existingFiles.isEmpty()) {
                StringBuilder sb = new StringBuilder(baseUserPrompt);
                sb.append("\n\n## RESUME CONTEXT\n");
                sb.append("A previous run completed ").append(startTurn).append(" turns before an API error interrupted it.\n");
                sb.append("The following files already exist — inspect them with read_file before rewriting:\n");
                for (String f : agentCtx.existingFiles) sb.append("- ").append(f).append("\n");
                sb.append("\nContinue from where it left off. Check compilation status and proceed.");
                userPrompt = sb.toString();
            } else {
                userPrompt = baseUserPrompt;
            }

            List<LlmRequest.ToolDef> tools = buildToolDefinitions();

            // Agent loop
            List<LlmRequest.Message> messages = new ArrayList<>();
            messages.add(LlmRequest.Message.user(userPrompt));

            for (int turn = 0; turn < MAX_AGENT_TURNS; turn++) {
                ctx.reportProgress("Agent turn " + (turn + 1));
                log.info("Agent turn {}/{}", turn + 1, MAX_AGENT_TURNS);

                // Inject urgency reminder when nearing the turn limit
                int remaining = MAX_AGENT_TURNS - turn;
                if (remaining == 5) {
                    messages.add(LlmRequest.Message.user(
                            "NOTICE: Only 5 turns remaining. Wrap up now: if the PoC is not yet verified, "
                            + "set poc_status to 'unverified'. Write the report and call finish()."));
                } else if (remaining == 2) {
                    messages.add(LlmRequest.Message.user(
                            "FINAL WARNING: 2 turns left. You MUST call finish() on your next response. "
                            + "Skip any remaining debugging."));
                }

                LlmResponse response;
                try {
                    response = chatWithRetry(ctx,
                            LlmRequest.agent(systemPrompt, messages, tools), 3);
                } catch (Exception e) {
                    // LLM call failed after all retries — save checkpoint and return paused
                    String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Agent paused at turn {} due to LLM error: {}", turn + 1, errMsg);
                    agentCtx.turns = turn;
                    saveCheckpoint(cvePath, agentCtx, errMsg);
                    Map<String, Object> output = agentCtx.buildOutput();
                    output.put("status", "paused");
                    output.put("pauseReason", errMsg);
                    output.put("pausedAtTurn", turn);
                    ctx.getWorkspaceManager().writeStageData(ctx.getCveId(), 5, output);
                    ctx.reportProgress("Agent paused: " + errMsg);
                    return StageResult.success(5, name(), output);
                }

                messages.add(LlmRequest.Message.assistantWithBlocks(response.getContentBlocks()));

                if (!response.hasToolUse()) {
                    log.info("Agent returned no tool calls — ending loop");
                    break;
                }

                List<LlmRequest.ContentBlock> toolResults = new ArrayList<>();
                boolean finished = false;

                for (LlmRequest.ContentBlock block : response.getToolUses()) {
                    if ("finish".equals(block.getToolName())) {
                        finished = true;
                        agentCtx.summary = block.getToolInput();
                        toolResults.add(LlmRequest.ContentBlock.toolResult(block.getToolUseId(), "ok"));
                        log.info("Agent called finish()");
                        break;
                    }

                    String result = executeToolCall(agentCtx, block);
                    toolResults.add(LlmRequest.ContentBlock.toolResult(block.getToolUseId(), result));
                }

                messages.add(LlmRequest.Message.toolResults(toolResults));

                agentCtx.turns = turn + 1;

                if (finished) break;
            }

            // Build output
            Map<String, Object> output = agentCtx.buildOutput();
            ctx.getWorkspaceManager().writeStageData(ctx.getCveId(), 5, output);
            ctx.reportProgress("Agent completed in " + agentCtx.turns + " turns");
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
            Path file = cvePath.resolve("stages/5_checkpoint.json");
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), ckpt);
            log.info("Saved checkpoint at turn {}", ctx.turns);
        } catch (Exception e) {
            log.error("Failed to save checkpoint: {}", e.getMessage());
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
                        || msg.contains("Internal Server Error") || msg.contains("returned no content");
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

    private List<LlmRequest.ToolDef> buildToolDefinitions() {
        List<LlmRequest.ToolDef> tools = new ArrayList<>();

        Map<String, Object> writeSchema = new LinkedHashMap<>();
        writeSchema.put("type", "object");
        Map<String, Object> writeProps = new LinkedHashMap<>();
        writeProps.put("path", prop("string", "File path relative to workspace (e.g. vuln-demo/pom.xml, poc/exploit.sh, report/report.md)"));
        writeProps.put("content", prop("string", "Full file content"));
        writeSchema.put("properties", writeProps);
        writeSchema.put("required", Arrays.asList("path", "content"));
        tools.add(new LlmRequest.ToolDef("write_file",
                "Write a file to the workspace. Path must start with vuln-demo/, poc/, or report/.",
                writeSchema));

        Map<String, Object> readSchema = new LinkedHashMap<>();
        readSchema.put("type", "object");
        Map<String, Object> readProps = new LinkedHashMap<>();
        readProps.put("path", prop("string", "File path relative to workspace"));
        readSchema.put("properties", readProps);
        readSchema.put("required", Collections.singletonList("path"));
        tools.add(new LlmRequest.ToolDef("read_file",
                "Read a file from the workspace.",
                readSchema));

        Map<String, Object> buildSchema = new LinkedHashMap<>();
        buildSchema.put("type", "object");
        buildSchema.put("properties", Collections.emptyMap());
        tools.add(new LlmRequest.ToolDef("run_build",
                "Compile the vuln-demo project (mvn package -DskipTests). Returns stdout+stderr.",
                buildSchema));

        Map<String, Object> startSchema = new LinkedHashMap<>();
        startSchema.put("type", "object");
        startSchema.put("properties", Collections.emptyMap());
        tools.add(new LlmRequest.ToolDef("start_app",
                "Start the vuln-demo application on port 18080. Returns status. If already running, stops and restarts.",
                startSchema));

        Map<String, Object> cmdSchema = new LinkedHashMap<>();
        cmdSchema.put("type", "object");
        Map<String, Object> cmdProps = new LinkedHashMap<>();
        cmdProps.put("command", prop("string", "Shell command to execute (curl, grep, bash, etc.)"));
        cmdSchema.put("properties", cmdProps);
        cmdSchema.put("required", Collections.singletonList("command"));
        tools.add(new LlmRequest.ToolDef("run_command",
                "Execute a shell command in the workspace directory. Use for curl, grep, bash scripts, etc.",
                cmdSchema));

        Map<String, Object> finishSchema = new LinkedHashMap<>();
        finishSchema.put("type", "object");
        Map<String, Object> finishProps = new LinkedHashMap<>();
        finishProps.put("vuln_demo_status", prop("string", "Status: startup_ok, compile_ok, compile_failed"));
        finishProps.put("poc_status", prop("string", "Status: verified, unverified, skipped"));
        finishProps.put("report_status", prop("string", "Status: generated, skipped"));
        finishProps.put("notes", prop("string", "Any notes about the generation"));
        finishSchema.put("properties", finishProps);
        tools.add(new LlmRequest.ToolDef("finish",
                "Call when all deliverables are ready. Provide a summary of what was generated.",
                finishSchema));

        return tools;
    }

    private Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    // ==================== Tool Execution ====================

    private String executeToolCall(AgentContext ctx, LlmRequest.ContentBlock toolUse) {
        String toolName = toolUse.getToolName();
        JsonNode input = toolUse.getToolInput();
        log.info("Executing tool: {}", toolName);

        try {
            switch (toolName) {
                case "write_file":  return doWriteFile(ctx, input);
                case "read_file":   return doReadFile(ctx, input);
                case "run_build":   return doRunBuild(ctx);
                case "start_app":   return doStartApp(ctx);
                case "run_command": return doRunCommand(ctx, input);
                default:            return "Unknown tool: " + toolName;
            }
        } catch (Exception e) {
            log.error("Tool {} failed: {}", toolName, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private String doWriteFile(AgentContext ctx, JsonNode input) throws IOException {
        String path = input.path("path").asText("");
        String content = input.path("content").asText("");

        if (path.isEmpty() || content.isEmpty()) return "Error: path and content are required";

        // Security: only allow paths under vuln-demo/, poc/, report/
        if (!path.startsWith("vuln-demo/") && !path.startsWith("poc/") && !path.startsWith("report/")) {
            return "Error: path must start with vuln-demo/, poc/, or report/";
        }
        if (path.contains("..")) return "Error: path traversal not allowed";

        Path target = ctx.cvePath.resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));

        if (path.endsWith(".sh")) {
            target.toFile().setExecutable(true);
        }

        ctx.writtenFiles.add(path);
        log.info("  Wrote: {} ({} bytes)", path, content.length());
        return "ok";
    }

    private String doReadFile(AgentContext ctx, JsonNode input) throws IOException {
        String path = input.path("path").asText("");
        if (path.isEmpty()) return "Error: path is required";
        if (path.contains("..")) return "Error: path traversal not allowed";

        Path target = ctx.cvePath.resolve(path);
        if (!Files.exists(target)) return "Error: file not found: " + path;

        byte[] bytes = Files.readAllBytes(target);
        String content = new String(bytes, StandardCharsets.UTF_8);
        return truncate(content, OUTPUT_TRUNCATE);
    }

    private String doRunBuild(AgentContext ctx) {
        ctx.pipeCtx.reportProgress("Agent: compiling vuln-demo");
        ProcessResult pr = runProcess(ctx.cvePath.resolve("vuln-demo"), COMPILE_TIMEOUT, "bash", "build.sh");
        String status = pr.exitCode == 0 ? "BUILD SUCCESS" : "BUILD FAILED (exit code " + pr.exitCode + ")";
        log.info("  Build: {}", status);
        return status + "\n\n" + truncate(pr.output, OUTPUT_TRUNCATE);
    }

    private String doStartApp(AgentContext ctx) throws Exception {
        ctx.pipeCtx.reportProgress("Agent: starting vuln-demo");

        // Stop existing process if running
        if (ctx.appProcess != null && ctx.appProcess.isAlive()) {
            ctx.appProcess.destroyForcibly();
            ctx.appProcess.waitFor(5, TimeUnit.SECONDS);
            log.info("  Stopped previous vuln-demo process");
        }

        Path vulnDemoPath = ctx.cvePath.resolve("vuln-demo");
        ProcessBuilder pb = new ProcessBuilder("bash", "run.sh");
        pb.directory(vulnDemoPath.toFile());
        pb.redirectErrorStream(true);
        ctx.appProcess = pb.start();

        // Wait for startup
        long deadline = System.currentTimeMillis() + STARTUP_WAIT * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!ctx.appProcess.isAlive()) {
                String output = readProcessOutput(ctx.appProcess);
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
                    return "Application started on port " + VULN_DEMO_PORT + " (HTTP " + code + ")";
                }
            } catch (Exception ignored) {}
            Thread.sleep(1000);
        }

        // Timeout — read whatever output we can get
        String output = readProcessOutput(ctx.appProcess);
        if (ctx.appProcess.isAlive()) {
            return "STARTUP TIMEOUT after " + STARTUP_WAIT + "s — app process still running but port not responding\n\n" + truncate(output, 2000);
        } else {
            return "STARTUP FAILED — process died during startup\n\n" + truncate(output, OUTPUT_TRUNCATE);
        }
    }

    private String doRunCommand(AgentContext ctx, JsonNode input) {
        String command = input.path("command").asText("");
        if (command.isEmpty()) return "Error: command is required";

        // Security check
        String firstWord = command.trim().split("\\s+")[0];
        if (!COMMAND_WHITELIST.contains(firstWord)) {
            return "Error: command '" + firstWord + "' is not allowed. Allowed: " + COMMAND_WHITELIST;
        }

        ctx.pipeCtx.reportProgress("Agent: " + truncate(command, 80));
        ProcessResult pr = runProcess(ctx.cvePath, COMMAND_TIMEOUT, "bash", "-c", command);
        log.info("  Command exit={}: {}", pr.exitCode, truncate(command, 100));
        return "Exit code: " + pr.exitCode + "\n\n" + truncate(pr.output, OUTPUT_TRUNCATE);
    }

    // ==================== Process Management ====================

    private ProcessResult runProcess(Path workDir, int timeoutSec, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            byte[] output = readStream(proc.getInputStream());
            boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new ProcessResult(124, "TIMEOUT after " + timeoutSec + "s\n" + new String(output, StandardCharsets.UTF_8));
            }
            return new ProcessResult(proc.exitValue(), new String(output, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new ProcessResult(-1, "Process error: " + e.getMessage());
        }
    }

    private String readProcessOutput(Process proc) {
        try {
            byte[] bytes = readStream(proc.getInputStream());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Could not read output: " + e.getMessage();
        }
    }

    // ==================== Skeleton ====================

    private void writeMinimalSkeleton(Path vulnDemoPath) throws IOException {
        Files.createDirectories(vulnDemoPath.resolve("src/main/java/com/jvuln/demo"));
        Files.createDirectories(vulnDemoPath.resolve("src/main/resources"));

        String app = "package com.jvuln.demo;\n\n"
                + "import org.springframework.boot.SpringApplication;\n"
                + "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n"
                + "@SpringBootApplication\n"
                + "public class Application {\n"
                + "    public static void main(String[] args) {\n"
                + "        SpringApplication.run(Application.class, args);\n"
                + "    }\n"
                + "}\n";
        Files.write(vulnDemoPath.resolve("src/main/java/com/jvuln/demo/Application.java"),
                app.getBytes(StandardCharsets.UTF_8));

        String build = "#!/bin/bash\ncd \"$(dirname \"$0\")\"\nmvn package -DskipTests -q\n";
        Path buildSh = vulnDemoPath.resolve("build.sh");
        Files.write(buildSh, build.getBytes(StandardCharsets.UTF_8));
        buildSh.toFile().setExecutable(true);

        String run = "#!/bin/bash\ncd \"$(dirname \"$0\")\"\nexec java -jar target/*.jar --server.port=" + VULN_DEMO_PORT + "\n";
        Path runSh = vulnDemoPath.resolve("run.sh");
        Files.write(runSh, run.getBytes(StandardCharsets.UTF_8));
        runSh.toFile().setExecutable(true);

        log.info("Wrote minimal skeleton: Application.java, build.sh, run.sh");
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

    private String extractDiff(Object data, int cap) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode rawDiff = root.path("rawDiff");
        if (!rawDiff.isMissingNode() && rawDiff.isTextual()) {
            String d = rawDiff.asText();
            return d.length() > cap ? d.substring(0, cap) + "\n...[truncated]" : d;
        }
        String full = mapper.writeValueAsString(data);
        return full.length() > cap ? full.substring(0, cap) + "\n...[truncated]" : full;
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

    // ==================== Utilities ====================

    private void copyField(JsonNode src, ObjectNode dst, String field) {
        JsonNode v = src.path(field);
        if (!v.isMissingNode()) dst.set(field, v);
    }

    private byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(s.length() - max) : s;
    }

    // ==================== Inner Classes ====================

    private static class ProcessResult {
        final int exitCode;
        final String output;
        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private class AgentContext {
        final Path cvePath;
        final PipelineContext pipeCtx;
        final Set<String> writtenFiles = new LinkedHashSet<>();
        final List<String> existingFiles = new ArrayList<>();
        Process appProcess;
        JsonNode summary;
        int turns;

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
            if (appProcess != null) {
                appProcess.destroyForcibly();
                log.info("Stopped vuln-demo process");
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> buildOutput() {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", "generated");
            output.put("agentTurns", turns);

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
            for (String sk : Arrays.asList("src/main/java/com/jvuln/demo/Application.java", "build.sh", "run.sh")) {
                if (!vulnDemoFiles.contains(sk)) vulnDemoFiles.add(0, sk);
            }

            // VulnDemo status — infer from files if finish() was not called
            Map<String, Object> vulnDemo = new LinkedHashMap<>();
            String vdStatus;
            if (summary != null) {
                vdStatus = summary.path("vuln_demo_status").asText("unknown");
            } else {
                // Infer: has pom.xml + jar exists = at least compile_ok
                boolean hasPom = writtenFiles.contains("vuln-demo/pom.xml");
                boolean jarExists = Files.exists(cvePath.resolve("vuln-demo/target"))
                        && cvePath.resolve("vuln-demo/target").toFile().listFiles(f -> f.getName().endsWith(".jar")) != null
                        && cvePath.resolve("vuln-demo/target").toFile().listFiles(f -> f.getName().endsWith(".jar")).length > 0;
                vdStatus = jarExists ? "compile_ok" : (hasPom ? "compile_failed" : "not_started");
            }
            vulnDemo.put("status", vdStatus);
            vulnDemo.put("files", vulnDemoFiles);
            output.put("vulnDemo", vulnDemo);

            // PoC status — infer from files if finish() was not called
            Map<String, Object> poc = new LinkedHashMap<>();
            String pocStatus;
            if (summary != null) {
                pocStatus = summary.path("poc_status").asText("unknown");
            } else {
                pocStatus = !pocFiles.isEmpty() ? "unverified" : "skipped";
            }
            poc.put("status", pocStatus);
            poc.put("files", pocFiles);
            output.put("poc", poc);

            // Report status — infer from files if finish() was not called
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
                    "vuln-demo/src/main/java/com/jvuln/demo/Application.java",
                    "vuln-demo/build.sh", "vuln-demo/run.sh")) {
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

            // Reproduction steps — only when vuln-demo compiled successfully
            boolean compiled = "startup_ok".equals(vdStatus) || "compile_ok".equals(vdStatus);
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

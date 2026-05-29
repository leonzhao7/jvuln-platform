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
    private static final int MAX_FIX_ROUNDS = 2;
    private static final int VULN_DEMO_PORT = 18080;
    private static final int COMPILE_TIMEOUT = 120;
    private static final int STARTUP_WAIT = 30;
    private static final int POC_TIMEOUT = 60;
    private static final int VERIFY_TIMEOUT = 30;
    private static final int LOG_TRUNCATE = 3000;

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
        ctx.reportProgress("Starting multi-step artifact generation");

        Object rawIntelligence = ctx.getCompletedStages().get(1).getData();
        String intelligence = trimIntelligence(rawIntelligence);
        String patchDiff = extractDiff(ctx.getCompletedStages().get(2).getData(), 4000);
        String triggerChain = extractTriggerChain(ctx.getCompletedStages().get(4).getData());
        String rootCause = extractRootCause(ctx.getCompletedStages().get(4).getData());

        Path cvePath = ctx.getWorkspaceManager().getCvePath(ctx.getCveId());
        Process vulnDemoProcess = null;

        try {
            // SubStage A: Vuln-Demo Project
            ctx.reportProgress("SubStage A: Generating vuln-demo project");
            Map<String, Object> vulnDemoResult = generateVulnDemo(ctx, cvePath, intelligence,
                    triggerChain, rootCause, patchDiff, rawIntelligence);

            // SubStage B: PoC Script
            boolean vulnDemoRunning = "startup_ok".equals(vulnDemoResult.get("status"));
            if (vulnDemoRunning) {
                vulnDemoProcess = (Process) vulnDemoResult.get("process");
            }
            ctx.reportProgress("SubStage B: Generating PoC scripts");
            Map<String, Object> pocResult = generatePoc(ctx, cvePath, intelligence, triggerChain, vulnDemoRunning);

            // SubStage C: Report
            ctx.reportProgress("SubStage C: Generating educational report");
            Map<String, Object> reportResult = generateReport(ctx, cvePath, intelligence, triggerChain, rootCause, patchDiff);

            // Build summary
            List<Map<String, String>> allFiles = new ArrayList<>();
            addFiles(allFiles, vulnDemoResult, "vuln-demo");
            addFiles(allFiles, pocResult, "poc");
            addFiles(allFiles, reportResult, "report");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", "generated");
            summary.put("vulnDemo", stripProcess(vulnDemoResult));
            summary.put("poc", pocResult);
            summary.put("report", reportResult);
            summary.put("fileCount", allFiles.size());
            summary.put("files", allFiles);

            ctx.getWorkspaceManager().writeStageData(ctx.getCveId(), 5, summary);
            ctx.reportProgress("Artifact generation completed");
            return StageResult.success(5, name(), summary);

        } finally {
            if (vulnDemoProcess != null) {
                vulnDemoProcess.destroyForcibly();
                log.info("Stopped vuln-demo process");
            }
        }
    }

    // ==================== SubStage A: Vuln-Demo ====================

    private Map<String, Object> generateVulnDemo(PipelineContext ctx, Path cvePath,
            String intelligence, String triggerChain, String rootCause,
            String patchDiff, Object rawIntelligence) {

        Path vulnDemoPath = cvePath.resolve("vuln-demo");
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> files = new ArrayList<>();

        try {
            // Clean old AI-generated source files (keep skeleton)
            cleanOldGeneratedFiles(vulnDemoPath);

            // A1: Write local skeleton
            writeLocalSkeleton(vulnDemoPath, rawIntelligence);
            files.addAll(Arrays.asList("pom.xml", "src/main/java/com/jvuln/demo/Application.java", "build.sh", "run.sh"));

            // A2: LLM generates configuration + business code
            String systemPrompt = promptRegistry.getSystemPrompt("gen_vulndemo");
            String userTemplate = promptRegistry.getUserPrompt("gen_vulndemo");
            Map<String, String> vars = new HashMap<>();
            vars.put("intelligence", intelligence);
            vars.put("trigger_chain", triggerChain);
            vars.put("root_cause", rootCause);
            vars.put("patch_diff", patchDiff);
            vars.put("artifact", extractArtifact(rawIntelligence));
            String userPrompt = promptRegistry.render(userTemplate, vars);

            List<LlmRequest.Message> messages = new ArrayList<>();
            messages.add(LlmRequest.Message.user(userPrompt));

            ctx.reportProgress("SubStage A: LLM generating vuln-specific code");
            String content = requestJsonResponse(ctx, systemPrompt, messages);

            List<String> genFiles = writeVulnDemoFiles(vulnDemoPath, content);
            files.addAll(genFiles);

            // A3: Compile fix loop
            ctx.reportProgress("SubStage A: Compiling vuln-demo");
            String compileStatus = compileFixLoop(ctx, vulnDemoPath, messages, systemPrompt);
            result.put("compileStatus", compileStatus);
            int compileAttempts = countFixRounds(messages, "compile");

            // A4: Startup fix loop (only if compile succeeded)
            if ("compile_ok".equals(compileStatus)) {
                ctx.reportProgress("SubStage A: Starting vuln-demo");
                Object[] startupResult = startupFixLoop(ctx, vulnDemoPath, messages, systemPrompt);
                String startupStatus = (String) startupResult[0];
                Process process = (Process) startupResult[1];
                result.put("startupStatus", startupStatus);
                result.put("status", startupStatus);
                if (process != null) {
                    result.put("process", process);
                }
            } else {
                result.put("startupStatus", "skipped");
                result.put("status", compileStatus);
            }

            result.put("compileAttempts", compileAttempts);
            result.put("files", files);

        } catch (Exception e) {
            log.error("SubStage A failed: {}", e.getMessage(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
            result.put("files", files);
        }
        return result;
    }

    // ==================== SubStage B: PoC ====================

    private Map<String, Object> generatePoc(PipelineContext ctx, Path cvePath,
            String intelligence, String triggerChain, boolean vulnDemoRunning) {

        Path pocPath = cvePath.resolve("poc");
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            String systemPrompt = promptRegistry.getSystemPrompt("gen_poc");
            String userTemplate = promptRegistry.getUserPrompt("gen_poc");
            Map<String, String> vars = new HashMap<>();
            vars.put("intelligence", intelligence);
            vars.put("trigger_chain", triggerChain);
            vars.put("vuln_demo_info", vulnDemoRunning
                    ? "The application is running and accessible."
                    : "The application is NOT running (compilation/startup failed). Generate the PoC anyway for reference.");
            String userPrompt = promptRegistry.render(userTemplate, vars);

            List<LlmRequest.Message> messages = new ArrayList<>();
            messages.add(LlmRequest.Message.user(userPrompt));

            ctx.reportProgress("SubStage B: LLM generating PoC");
            String content = requestJsonResponse(ctx, systemPrompt, messages);

            String json = stripMarkdownFence(content);
            JsonNode root = mapper.readTree(json);

            List<String> pocFiles = writePocFiles(pocPath, root);
            result.put("files", pocFiles);

            String verifyCmd = "";
            JsonNode verification = root.path("verification");
            if (!verification.isMissingNode()) {
                verifyCmd = verification.path("command").asText("");
                Map<String, String> verifyMap = new HashMap<>();
                verifyMap.put("command", verifyCmd);
                verifyMap.put("description", verification.path("description").asText(""));
                result.put("verification", verifyMap);
            }

            // B3: Verify if vuln-demo is running
            if (vulnDemoRunning && !verifyCmd.isEmpty()) {
                ctx.reportProgress("SubStage B: Executing and verifying PoC");
                String pocStatus = pocFixLoop(ctx, pocPath, cvePath.resolve("vuln-demo"),
                        messages, systemPrompt, pocFiles, verifyCmd);
                result.put("status", pocStatus);
            } else {
                result.put("status", vulnDemoRunning ? "unverified" : "skipped");
            }

            result.put("pocAttempts", countFixRounds(messages, "poc"));

        } catch (Exception e) {
            log.error("SubStage B failed: {}", e.getMessage(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== SubStage C: Report ====================

    private Map<String, Object> generateReport(PipelineContext ctx, Path cvePath,
            String intelligence, String triggerChain, String rootCause, String patchDiff) {

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String systemPrompt = promptRegistry.getSystemPrompt("gen_report");
            String userTemplate = promptRegistry.getUserPrompt("gen_report");
            Map<String, String> vars = new HashMap<>();
            vars.put("intelligence", intelligence);
            vars.put("trigger_chain", triggerChain);
            vars.put("root_cause", rootCause);
            vars.put("patch_diff", patchDiff);
            String userPrompt = promptRegistry.render(userTemplate, vars);

            ctx.reportProgress("SubStage C: LLM generating report");
            LlmResponse response = ctx.getLlmClient().chat(LlmRequest.generation(systemPrompt, userPrompt));
            String content = response.getContent();

            if (content != null && !content.trim().isEmpty()) {
                Path reportFile = cvePath.resolve("report/report.md");
                Files.createDirectories(reportFile.getParent());
                Files.write(reportFile, content.getBytes(StandardCharsets.UTF_8));
                result.put("status", "generated");
                result.put("file", "report/report.md");
                log.info("Wrote report: report/report.md");
            } else {
                result.put("status", "failed");
                result.put("error", "LLM returned empty report");
            }
        } catch (Exception e) {
            log.error("SubStage C failed: {}", e.getMessage(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== Verification Loops ====================

    private String compileFixLoop(PipelineContext ctx, Path vulnDemoPath,
            List<LlmRequest.Message> messages, String systemPrompt) throws Exception {

        for (int round = 0; round <= MAX_FIX_ROUNDS; round++) {
            ProcessResult pr = runProcess(vulnDemoPath, COMPILE_TIMEOUT, "bash", "build.sh");
            if (pr.exitCode == 0) {
                log.info("Compile succeeded (round {})", round);
                return "compile_ok";
            }
            if (round == MAX_FIX_ROUNDS) break;

            log.warn("Compile failed (round {}), requesting fix", round);
            String errorTrunc = truncate(pr.output, LOG_TRUNCATE);
            messages.add(LlmRequest.Message.user(
                    "Compilation failed. Fix the code.\n\nError output:\n```\n" + errorTrunc + "\n```\n\n"
                    + "Return the corrected files in the same JSON format."));

            ctx.reportProgress("SubStage A: Fixing compile error (round " + (round + 1) + ")");
            LlmResponse resp = ctx.getLlmClient().chat(LlmRequest.generation(systemPrompt, messages));
            String fix = resp.getContent();
            messages.add(LlmRequest.Message.assistant(fix));
            writeVulnDemoFiles(vulnDemoPath, fix);
        }
        return "compile_failed";
    }

    private Object[] startupFixLoop(PipelineContext ctx, Path vulnDemoPath,
            List<LlmRequest.Message> messages, String systemPrompt) throws Exception {

        for (int round = 0; round <= MAX_FIX_ROUNDS; round++) {
            Process proc = startBackground(vulnDemoPath);
            boolean started = waitForStartup(proc, STARTUP_WAIT);

            if (started) {
                log.info("Startup succeeded (round {})", round);
                return new Object[]{"startup_ok", proc};
            }

            proc.destroyForcibly();
            if (round == MAX_FIX_ROUNDS) break;

            String logOutput = readProcessOutput(proc, LOG_TRUNCATE);
            log.warn("Startup failed (round {}), requesting fix", round);
            messages.add(LlmRequest.Message.user(
                    "Application failed to start on port " + VULN_DEMO_PORT + ". Fix the code.\n\n"
                    + "Startup log:\n```\n" + logOutput + "\n```\n\n"
                    + "Return the corrected files in the same JSON format."));

            ctx.reportProgress("SubStage A: Fixing startup error (round " + (round + 1) + ")");
            LlmResponse resp = ctx.getLlmClient().chat(LlmRequest.generation(systemPrompt, messages));
            String fix = resp.getContent();
            messages.add(LlmRequest.Message.assistant(fix));
            writeVulnDemoFiles(vulnDemoPath, fix);

            // Recompile after fix
            ProcessResult compile = runProcess(vulnDemoPath, COMPILE_TIMEOUT, "bash", "build.sh");
            if (compile.exitCode != 0) {
                log.warn("Recompile after startup fix failed");
            }
        }
        return new Object[]{"startup_failed", null};
    }

    private String pocFixLoop(PipelineContext ctx, Path pocPath, Path vulnDemoPath,
            List<LlmRequest.Message> messages, String systemPrompt,
            List<String> pocFiles, String verifyCmd) throws Exception {

        for (int round = 0; round <= MAX_FIX_ROUNDS; round++) {
            // Execute PoC
            String mainPoc = pocFiles.isEmpty() ? "exploit.sh" : pocFiles.get(0);
            Path pocScript = pocPath.resolve(mainPoc);
            ProcessResult pocRun = runProcess(pocPath, POC_TIMEOUT, "bash", pocScript.toString());

            // Execute verification
            ProcessResult verify = runProcess(pocPath, VERIFY_TIMEOUT, "bash", "-c", verifyCmd);
            if (verify.exitCode == 0) {
                log.info("PoC verified (round {})", round);
                return "verified";
            }

            if (round == MAX_FIX_ROUNDS) break;

            log.warn("PoC verification failed (round {}), requesting fix", round);
            messages.add(LlmRequest.Message.user(
                    "PoC verification failed.\n\nPoC output:\n```\n" + truncate(pocRun.output, 2000)
                    + "\n```\n\nVerification command: " + verifyCmd
                    + "\nVerification output:\n```\n" + truncate(verify.output, 1000)
                    + "\n```\n\nFix the PoC or the vuln-demo application. Return the same JSON format."));

            ctx.reportProgress("SubStage B: Fixing PoC (round " + (round + 1) + ")");
            LlmResponse resp = ctx.getLlmClient().chat(LlmRequest.generation(systemPrompt, messages));
            String fix = resp.getContent();
            messages.add(LlmRequest.Message.assistant(fix));

            String fixJson = stripMarkdownFence(fix);
            JsonNode fixRoot = mapper.readTree(fixJson);

            // Apply PoC fixes
            if (fixRoot.has("poc_files")) {
                pocFiles.clear();
                pocFiles.addAll(writePocFiles(pocPath, fixRoot));
            }

            // Apply vuln-demo fixes if present
            if (fixRoot.has("vulndemo_fixes")) {
                JsonNode fixes = fixRoot.path("vulndemo_fixes");
                if (fixes.isArray()) {
                    for (JsonNode f : fixes) {
                        String path = f.path("path").asText("");
                        String content = f.path("content").asText("");
                        if (!path.isEmpty() && !content.isEmpty()) {
                            Path target = vulnDemoPath.resolve(path);
                            Files.createDirectories(target.getParent());
                            Files.write(target, content.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    // Recompile and restart
                    runProcess(vulnDemoPath, COMPILE_TIMEOUT, "bash", "build.sh");
                }
            }
        }
        return "unverified";
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

    private Process startBackground(Path vulnDemoPath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("bash", "run.sh");
        pb.directory(vulnDemoPath.toFile());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private boolean waitForStartup(Process proc, int maxWaitSec) {
        long deadline = System.currentTimeMillis() + maxWaitSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) return false;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + VULN_DEMO_PORT + "/").openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code > 0) return true;
            } catch (Exception ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    private String readProcessOutput(Process proc, int maxLen) {
        try {
            byte[] bytes = readStream(proc.getInputStream());
            String s = new String(bytes, StandardCharsets.UTF_8);
            return truncate(s, maxLen);
        } catch (Exception e) {
            return "Could not read output: " + e.getMessage();
        }
    }

    // ==================== Skeleton Generation ====================

    private void cleanOldGeneratedFiles(Path vulnDemoPath) throws IOException {
        Path srcDir = vulnDemoPath.resolve("src");
        if (Files.exists(srcDir)) {
            // Delete all Java files except Application.java, and all resource files
            Files.walk(srcDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.equals("Application.java");
                    })
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
            log.info("Cleaned old generated files from vuln-demo/src");
        }
        Path target = vulnDemoPath.resolve("target");
        if (Files.exists(target)) {
            Files.walk(target).sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
            log.info("Cleaned vuln-demo/target");
        }
    }

    private void writeLocalSkeleton(Path vulnDemoPath, Object intelligenceData) throws Exception {
        Files.createDirectories(vulnDemoPath.resolve("src/main/java/com/jvuln/demo"));
        Files.createDirectories(vulnDemoPath.resolve("src/main/resources"));

        JsonNode intel = mapper.valueToTree(intelligenceData);
        JsonNode art = intel.path("artifact");
        String groupId = art.path("groupId").asText("");
        String artifactId = art.path("artifactId").asText("");
        String version = art.path("version").asText("");

        // Derive vulnerable version from fixedVersion if not explicit
        if (version.isEmpty()) {
            String fixed = intel.path("fixedVersion").asText("");
            version = deriveVulnerableVersion(fixed);
        }

        // Determine if this is a Spring Boot managed dependency
        String versionProperty = resolveBootManagedProperty(groupId, artifactId);

        // Build pom.xml
        StringBuilder props = new StringBuilder();
        props.append("        <java.version>1.8</java.version>\n");
        if (versionProperty != null && !version.isEmpty()) {
            props.append("        <").append(versionProperty).append(">")
                 .append(version).append("</").append(versionProperty).append(">\n");
            log.info("Overriding Spring Boot managed version: {}={}", versionProperty, version);
        }

        StringBuilder deps = new StringBuilder();
        deps.append("        <dependency>\n")
            .append("            <groupId>org.springframework.boot</groupId>\n")
            .append("            <artifactId>spring-boot-starter-web</artifactId>\n")
            .append("        </dependency>\n");

        if (versionProperty == null && !groupId.isEmpty() && !artifactId.isEmpty() && !version.isEmpty()) {
            deps.append("        <dependency>\n")
                .append("            <groupId>").append(groupId).append("</groupId>\n")
                .append("            <artifactId>").append(artifactId).append("</artifactId>\n")
                .append("            <version>").append(version).append("</version>\n")
                .append("        </dependency>\n");
            log.info("Added explicit dependency: {}:{}:{}", groupId, artifactId, version);
        }

        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <parent>\n"
                + "        <groupId>org.springframework.boot</groupId>\n"
                + "        <artifactId>spring-boot-starter-parent</artifactId>\n"
                + "        <version>2.7.18</version>\n"
                + "    </parent>\n"
                + "    <groupId>com.jvuln</groupId>\n"
                + "    <artifactId>vuln-demo</artifactId>\n"
                + "    <version>1.0.0</version>\n"
                + "    <properties>\n"
                + props.toString()
                + "    </properties>\n"
                + "    <dependencies>\n"
                + deps.toString()
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
        Files.write(vulnDemoPath.resolve("pom.xml"), pom.getBytes(StandardCharsets.UTF_8));

        // Application.java
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

        // build.sh
        String build = "#!/bin/bash\ncd \"$(dirname \"$0\")\"\nmvn package -DskipTests -q\n";
        Path buildSh = vulnDemoPath.resolve("build.sh");
        Files.write(buildSh, build.getBytes(StandardCharsets.UTF_8));
        buildSh.toFile().setExecutable(true);

        // run.sh
        String run = "#!/bin/bash\ncd \"$(dirname \"$0\")\"\nexec java -jar target/*.jar --server.port=" + VULN_DEMO_PORT + "\n";
        Path runSh = vulnDemoPath.resolve("run.sh");
        Files.write(runSh, run.getBytes(StandardCharsets.UTF_8));
        runSh.toFile().setExecutable(true);

        log.info("Wrote local skeleton: pom.xml, Application.java, build.sh, run.sh");
    }

    // ==================== File Writing ====================

    private List<String> writeVulnDemoFiles(Path vulnDemoPath, String llmContent) throws Exception {
        String json = stripMarkdownFence(llmContent);
        JsonNode root = mapper.readTree(json);
        JsonNode filesNode = root.path("files");
        List<String> written = new ArrayList<>();
        if (filesNode.isArray()) {
            for (JsonNode file : filesNode) {
                String path = file.path("path").asText("");
                String content = file.path("content").asText("");
                if (!path.isEmpty() && !content.isEmpty()) {
                    Path target = vulnDemoPath.resolve(path);
                    Files.createDirectories(target.getParent());
                    Files.write(target, content.getBytes(StandardCharsets.UTF_8));
                    written.add(path);
                    log.info("Wrote vuln-demo file: {}", path);
                }
            }
        }
        return written;
    }

    private List<String> writePocFiles(Path pocPath, JsonNode root) throws IOException {
        List<String> written = new ArrayList<>();
        JsonNode pocFiles = root.path("poc_files");
        if (pocFiles.isArray()) {
            for (JsonNode file : pocFiles) {
                String path = file.path("path").asText("");
                String content = file.path("content").asText("");
                if (!path.isEmpty() && !content.isEmpty()) {
                    Path target = pocPath.resolve(path);
                    Files.createDirectories(target.getParent());
                    Files.write(target, content.getBytes(StandardCharsets.UTF_8));
                    target.toFile().setExecutable(true);
                    written.add(path);
                    log.info("Wrote PoC file: {}", path);
                }
            }
        }
        return written;
    }

    // ==================== Data Extraction (from upstream stages) ====================

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
        if (!chain.isMissingNode()) {
            return mapper.writeValueAsString(chain);
        }
        return "{}";
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

    // ==================== Version Resolution ====================

    private static final Map<String, String> BOOT_MANAGED_PROPERTIES = new LinkedHashMap<>();
    static {
        BOOT_MANAGED_PROPERTIES.put("org.apache.tomcat.embed:tomcat-embed-core", "tomcat.version");
        BOOT_MANAGED_PROPERTIES.put("org.apache.tomcat.embed:tomcat-embed-websocket", "tomcat.version");
        BOOT_MANAGED_PROPERTIES.put("org.apache.tomcat:tomcat-catalina", "tomcat.version");
        BOOT_MANAGED_PROPERTIES.put("org.apache.tomcat:tomcat-coyote", "tomcat.version");
        BOOT_MANAGED_PROPERTIES.put("com.fasterxml.jackson.core:jackson-databind", "jackson-bom.version");
        BOOT_MANAGED_PROPERTIES.put("com.fasterxml.jackson.core:jackson-core", "jackson-bom.version");
        BOOT_MANAGED_PROPERTIES.put("io.netty:netty-codec-http", "netty.version");
        BOOT_MANAGED_PROPERTIES.put("io.netty:netty-handler", "netty.version");
        BOOT_MANAGED_PROPERTIES.put("org.yaml:snakeyaml", "snakeyaml.version");
        BOOT_MANAGED_PROPERTIES.put("ch.qos.logback:logback-classic", "logback.version");
        BOOT_MANAGED_PROPERTIES.put("org.apache.logging.log4j:log4j-core", "log4j2.version");
        BOOT_MANAGED_PROPERTIES.put("org.springframework:spring-webmvc", "spring-framework.version");
        BOOT_MANAGED_PROPERTIES.put("org.springframework:spring-beans", "spring-framework.version");
        BOOT_MANAGED_PROPERTIES.put("org.springframework:spring-core", "spring-framework.version");
        BOOT_MANAGED_PROPERTIES.put("org.thymeleaf:thymeleaf", "thymeleaf.version");
        BOOT_MANAGED_PROPERTIES.put("com.h2database:h2", "h2.version");
        BOOT_MANAGED_PROPERTIES.put("org.postgresql:postgresql", "postgresql.version");
    }

    private String resolveBootManagedProperty(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) return null;
        return BOOT_MANAGED_PROPERTIES.get(groupId + ":" + artifactId);
    }

    private String deriveVulnerableVersion(String fixedVersion) {
        if (fixedVersion == null || fixedVersion.isEmpty()) return "";
        // Parse major.minor.patch and decrement patch by 1
        String[] parts = fixedVersion.split("\\.");
        if (parts.length < 3) return "";
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            if (patch > 0) {
                return major + "." + minor + "." + (patch - 1);
            } else if (minor > 0) {
                return major + "." + (minor - 1) + ".99";
            }
        } catch (NumberFormatException ignored) {}
        return "";
    }

    // ==================== Utilities ====================

    private String requestJsonResponse(PipelineContext ctx, String systemPrompt,
            List<LlmRequest.Message> messages) throws Exception {
        LlmResponse response = ctx.getLlmClient().chat(LlmRequest.generation(systemPrompt, messages));
        String content = response.getContent();
        messages.add(LlmRequest.Message.assistant(content));

        // If response doesn't contain JSON, ask the model to retry
        if (content != null && !content.contains("{")) {
            log.warn("LLM response has no JSON, requesting retry. First 100 chars: {}",
                    content.substring(0, Math.min(100, content.length())));
            messages.add(LlmRequest.Message.user(
                    "Your response must be ONLY a JSON object. No explanation text. Return the JSON now."));
            response = ctx.getLlmClient().chat(LlmRequest.generation(systemPrompt, messages));
            content = response.getContent();
            messages.add(LlmRequest.Message.assistant(content));
        }
        return content;
    }

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

    private String stripMarkdownFence(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        // Remove markdown code fences
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline < 0) return s;
            s = s.substring(firstNewline + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        // If the string doesn't start with '{', try to find the first JSON object
        if (!s.startsWith("{")) {
            int braceStart = s.indexOf('{');
            if (braceStart >= 0) {
                // Find the matching closing brace
                int depth = 0;
                int braceEnd = -1;
                for (int i = braceStart; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) { braceEnd = i; break; }
                    }
                }
                if (braceEnd > braceStart) {
                    s = s.substring(braceStart, braceEnd + 1);
                } else {
                    s = s.substring(braceStart);
                }
            }
        }
        return s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(s.length() - max) : s;
    }

    private int countFixRounds(List<LlmRequest.Message> messages, String type) {
        int count = 0;
        for (LlmRequest.Message m : messages) {
            if ("user".equals(m.getRole()) && m.getContent().contains("failed")) count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private void addFiles(List<Map<String, String>> allFiles, Map<String, Object> result, String type) {
        Object filesObj = result.get("files");
        if (filesObj instanceof List) {
            for (Object f : (List<?>) filesObj) {
                Map<String, String> entry = new HashMap<>();
                entry.put("path", type + "/" + f.toString());
                entry.put("type", type);
                allFiles.add(entry);
            }
        }
        Object fileObj = result.get("file");
        if (fileObj instanceof String) {
            Map<String, String> entry = new HashMap<>();
            entry.put("path", (String) fileObj);
            entry.put("type", type);
            allFiles.add(entry);
        }
    }

    private Map<String, Object> stripProcess(Map<String, Object> result) {
        Map<String, Object> clean = new LinkedHashMap<>(result);
        clean.remove("process");
        return clean;
    }

    private static class ProcessResult {
        final int exitCode;
        final String output;
        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}

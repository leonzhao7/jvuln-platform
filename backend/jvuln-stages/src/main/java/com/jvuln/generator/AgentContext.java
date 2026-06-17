package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.store.entity.JavaProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class AgentContext {
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
    Map<String, FileSnapshot> currentFiles = new LinkedHashMap<>();
    String baseUserPrompt = "";
    String sessionSummary = "";
    int turns;
    int reviewRevisions;
    int noProgressTurns;
    int compactionCount;
    String lastProgressSignature = "";
    String abortReason;

    private AgentToolExecutor toolExecutor;

    AgentContext(Path cvePath, PipelineContext pipeCtx) {
        this.cvePath = cvePath;
        this.pipeCtx = pipeCtx;
    }

    void setToolExecutor(AgentToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
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
                            writtenFiles.add(rel);
                        });
            } catch (Exception ignored) {}
        }
    }

    void cleanup() {
        if (toolExecutor != null) {
            toolExecutor.stopTrackedAppProcess(this);
        }
    }

    String deriveCompileStatus() {
        if (!buildHistory.isEmpty()) {
            return buildHistory.get(buildHistory.size() - 1).success ? "compile_ok" : "compile_failed";
        }
        Path targetDir = cvePath.resolve("vuln-demo/target");
        if (Files.exists(targetDir)) {
            java.io.File[] jars = targetDir.toFile().listFiles(f -> f.getName().endsWith(".jar"));
            if (jars != null && jars.length > 0) {
                return "compile_ok";
            }
        }
        return "not_started";
    }

    String deriveStartupStatus() {
        if (!startupHistory.isEmpty()) {
            return startupHistory.get(startupHistory.size() - 1).success ? "startup_ok" : "startup_failed";
        }
        String compileStatus = deriveCompileStatus();
        if ("compile_ok".equals(compileStatus) || "compile_failed".equals(compileStatus)) {
            return "skipped";
        }
        return "not_started";
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

        List<String> vulnDemoFiles = new ArrayList<>();
        List<String> pocFiles = new ArrayList<>();
        String reportFile = null;
        for (String f : writtenFiles) {
            if (f.startsWith("vuln-demo/")) vulnDemoFiles.add(f.substring("vuln-demo/".length()));
            else if (f.startsWith("poc/")) pocFiles.add(f.substring("poc/".length()));
            else if (f.startsWith("report/")) reportFile = f;
        }
        for (String sk : Arrays.asList(
                "pom.xml",
                "src/main/java/com/jvuln/demo/Application.java",
                "src/main/java/com/jvuln/demo/LabInfoController.java",
                "src/main/resources/application.properties",
                "build.sh",
                "run.sh")) {
            if (!vulnDemoFiles.contains(sk)) vulnDemoFiles.add(0, sk);
        }

        String compileStatus = deriveCompileStatus();
        String startupStatus = deriveStartupStatus();
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
            vulnDemo.put("compileMessage", ArtifactGenUtils.truncate(lastBuild.output, 600));
        }
        if (lastStartup != null) {
            vulnDemo.put("startupMessage", ArtifactGenUtils.truncate(lastStartup.output, 600));
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

        if (javaProfile != null) {
            Map<String, Object> profileInfo = new LinkedHashMap<>();
            profileInfo.put("name", javaProfile.getName());
            profileInfo.put("javaVersion", javaProfile.getJavaVersion());
            profileInfo.put("springBootVersion", javaProfile.getSpringBootVersion());
            output.put("javaProfile", profileInfo);
        }

        boolean compiled = "startup_ok".equals(vulnDemoStatus) || "compile_ok".equals(vulnDemoStatus);
        if (compiled) {
            List<Map<String, String>> steps = new ArrayList<>();
            int stepNum = 1;

            Map<String, String> buildStep = new LinkedHashMap<>();
            buildStep.put("step", String.valueOf(stepNum++));
            buildStep.put("title", "Build the vulnerable demo project");
            buildStep.put("command", "cd vuln-demo && mvn package -DskipTests -q");
            buildStep.put("description", "Compile the Spring Boot application with the vulnerable component");
            steps.add(buildStep);

            Map<String, String> startStep = new LinkedHashMap<>();
            startStep.put("step", String.valueOf(stepNum++));
            startStep.put("title", "Start the application");
            startStep.put("command", "cd vuln-demo && java -jar target/*.jar --server.port=18080");
            startStep.put("description", "Launch the application on port 18080. Wait for 'Started Application' in the console output");
            steps.add(startStep);

            if (!pocFiles.isEmpty()) {
                Map<String, String> pocStep = new LinkedHashMap<>();
                pocStep.put("step", String.valueOf(stepNum++));
                pocStep.put("title", "Execute the PoC exploit");
                pocStep.put("command", "bash poc/" + pocFiles.get(0));
                pocStep.put("description", "Run the proof-of-concept script against the running application"
                        + ("unverified".equals(pocStatus) ? " (auto-generated, may require manual adjustment)" : ""));
                steps.add(pocStep);
            }

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

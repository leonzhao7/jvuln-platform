package com.jvuln.generator;

import static com.jvuln.generator.ArtifactGenUtils.shellQuote;
import static com.jvuln.generator.ArtifactGenUtils.singleLine;
import static com.jvuln.generator.ArtifactGenUtils.truncate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jvuln.util.RequestLogContext;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
class ValidationEngine {

    private static final Logger log = LoggerFactory.getLogger(ValidationEngine.class);
    private static final int VULN_DEMO_PORT = 18080;
    private static final int COMPILE_TIMEOUT = 120;
    private static final int STARTUP_WAIT = 30;
    private static final int COMMAND_TIMEOUT = 60;
    private static final int OUTPUT_TRUNCATE = 4000;
    private static final int PROCESS_OUTPUT_BUFFER = 64 * 1024;

    private final AgentToolExecutor toolExecutor;

    ValidationEngine(AgentToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    ValidationResult validateArtifacts(AgentContext ctx, String focus) throws Exception {
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
            result.compileOk = "compile_ok".equals(ctx.deriveCompileStatus());
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
            result.startupOk = "startup_ok".equals(ctx.deriveStartupStatus());
        }

        if (shouldValidatePoc(normalized)) {
            ValidationResult pocResult = validatePoc(ctx);
            result.mergeFrom(pocResult);
        }
        return result;
    }

    String doStartApp(AgentContext ctx) throws Exception {
        ctx.pipeCtx.reportProgress("Backend: starting vuln-demo");

        toolExecutor.stopTrackedAppProcess(ctx);
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
                RequestLogContext.logWebRequest("GET", conn.getURL().toString());
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

    String stopStaleWorkspaceProcesses(AgentContext ctx) {
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

    ProcessResult runProcess(Path workDir, int timeoutSec, String... cmd) {
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

    private boolean shouldValidateCompile(String focus) {
        return "full".equals(focus) || "compile".equals(focus) || "startup".equals(focus) || "poc".equals(focus);
    }

    private boolean shouldValidateStartup(String focus) {
        return "full".equals(focus) || "startup".equals(focus) || "poc".equals(focus);
    }

    private boolean shouldValidatePoc(String focus) {
        return "full".equals(focus) || "poc".equals(focus);
    }
}

package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
class AgentToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(AgentToolExecutor.class);
    private static final int OUTPUT_TRUNCATE = 4000;
    private static final int PROCESS_OUTPUT_BUFFER = 64 * 1024;

    String doWriteFiles(AgentContext ctx, JsonNode input) throws IOException {
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

    String doReadFile(AgentContext ctx, JsonNode input) throws IOException {
        String path = input.path("path").asText("");
        if (path.isEmpty()) return "Error: path is required";
        if (path.contains("..")) return "Error: path traversal not allowed";

        Path target = ctx.cvePath.resolve(path);
        if (!Files.exists(target)) return "Error: file not found: " + path;

        byte[] bytes = Files.readAllBytes(target);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    String doReadLog(AgentContext ctx, JsonNode input) throws IOException {
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

    String readTailBytes(Path target, int tailBytes) throws IOException {
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

    void writeWorkspaceFile(AgentContext ctx, String path, String content) throws IOException {
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

    void stopTrackedAppProcess(AgentContext ctx) {
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
}

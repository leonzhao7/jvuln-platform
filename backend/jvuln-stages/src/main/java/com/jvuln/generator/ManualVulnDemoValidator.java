package com.jvuln.generator;

import com.jvuln.pipeline.model.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 处理人工上传的 vuln-demo 压缩包：解压、清理临时文件、运行验证。
 *
 * 位于 com.jvuln.generator 包内以复用包级私有的 {@link ValidationEngine}、
 * {@link AgentContext} 及其 {@code buildOutput()} 输出结构。
 */
@Component
public class ManualVulnDemoValidator {

    private static final Logger log = LoggerFactory.getLogger(ManualVulnDemoValidator.class);

    private final ValidationEngine validationEngine;
    private final AgentToolExecutor toolExecutor;

    public ManualVulnDemoValidator(ValidationEngine validationEngine, AgentToolExecutor toolExecutor) {
        this.validationEngine = validationEngine;
        this.toolExecutor = toolExecutor;
    }

    /** 验证结果：是否成功，Stage 4 输出结构，以及失败原因。 */
    public static class Result {
        public final boolean success;
        public final Map<String, Object> output;
        public final String failureReason;

        Result(boolean success, Map<String, Object> output, String failureReason) {
            this.success = success;
            this.output = output;
            this.failureReason = failureReason;
        }
    }

    /**
     * 解压上传的 zip 到 CVE 工作目录，替换 vuln-demo/poc 目录，运行完整验证。
     *
     * @param ctx     pipeline 上下文（提供 workspace / 进度回调）
     * @param cvePath CVE 工作目录
     * @param zipData 上传的 zip 字节
     */
    public Result validateUploadedZip(PipelineContext ctx, Path cvePath, byte[] zipData) throws Exception {
        Path vulnDemo = cvePath.resolve("vuln-demo");
        Path poc = cvePath.resolve("poc");

        ctx.reportProgress("Manual upload: clearing existing vuln-demo and poc");
        deleteRecursively(vulnDemo);
        deleteRecursively(poc);
        Files.createDirectories(cvePath);

        ctx.reportProgress("Manual upload: extracting zip");
        extractZip(zipData, cvePath);
        stripDotEntries(cvePath);

        if (!Files.exists(vulnDemo)) {
            return new Result(false, null,
                    "Uploaded archive does not contain a vuln-demo/ directory at its root.");
        }

        AgentContext agentCtx = new AgentContext(cvePath, ctx);
        agentCtx.setToolExecutor(toolExecutor);
        agentCtx.discoverExistingFiles();

        try {
            ctx.reportProgress("Manual upload: running validation (compile -> startup -> poc)");
            ValidationResult validation = validationEngine.validateArtifacts(agentCtx, "full");
            agentCtx.lastValidation = validation;

            Map<String, Object> output = agentCtx.buildOutput();
            output.put("source", "manual-upload");

            String failureReason = deriveFailureReason(validation);
            if (failureReason != null) {
                output.put("failureReason", failureReason);
                return new Result(false, output, failureReason);
            }
            return new Result(true, output, null);
        } finally {
            agentCtx.cleanup();
        }
    }

    private String deriveFailureReason(ValidationResult v) {
        if (!v.compileOk) {
            return "Uploaded vuln-demo build failed"
                    + (v.compileMessage == null || v.compileMessage.trim().isEmpty()
                    ? "." : ": " + v.compileMessage);
        }
        if (!v.startupOk) {
            return "Uploaded vuln-demo startup failed"
                    + (v.startupMessage == null || v.startupMessage.trim().isEmpty()
                    ? "." : ": " + v.startupMessage);
        }
        if (!v.pocVerified) {
            return "PoC verification failed"
                    + (v.pocMessage == null || v.pocMessage.trim().isEmpty()
                    ? ": exploit.sh did not exit 0." : ": " + v.pocMessage);
        }
        return null;
    }

    private void extractZip(byte[] zipData, Path destRoot) throws IOException {
        Path normalizedRoot = destRoot.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = normalizedRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedRoot)) {
                    throw new IOException("Zip entry escapes target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /** 递归删除 vuln-demo/poc 中所有以 '.' 开头的文件和目录。 */
    private void stripDotEntries(Path cvePath) throws IOException {
        for (String dirName : new String[]{"vuln-demo", "poc"}) {
            Path dir = cvePath.resolve(dirName);
            if (!Files.exists(dir)) {
                continue;
            }
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                    if (!d.equals(dir) && d.getFileName().toString().startsWith(".")) {
                        deleteRecursively(d);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().startsWith(".")) {
                        Files.deleteIfExists(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

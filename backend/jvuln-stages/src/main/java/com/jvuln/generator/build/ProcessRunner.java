package com.jvuln.generator.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 进程运行器
 *
 * 职责：执行外部进程（Maven编译、应用启动等），管理进程生命周期和输出缓冲
 *
 * @author JVuln Team
 */
@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    /**
     * 运行外部进程并等待完成
     *
     * @param workDir 工作目录
     * @param timeoutSec 超时时间（秒）
     * @param cmd 命令和参数
     * @return 进程结果
     */
    public ProcessResult runProcess(Path workDir, int timeoutSec, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            ProcessOutputBuffer output = new ProcessOutputBuffer(proc.getInputStream(), DEFAULT_BUFFER_SIZE);
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

    /**
     * 进程执行结果
     */
    public static class ProcessResult {
        private final int exitCode;
        private final String output;

        public ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        public boolean isTimeout() {
            return exitCode == 124;
        }
    }

    /**
     * 进程输出缓冲器（环形缓冲，保留尾部数据）
     */
    public static class ProcessOutputBuffer {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int maxBytes;
        private final Thread reader;

        public ProcessOutputBuffer(final InputStream input, int maxBytes) {
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

        public synchronized String content() {
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        public void await(long timeout, TimeUnit unit) {
            try {
                reader.join(unit.toMillis(timeout));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

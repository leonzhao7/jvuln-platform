package com.jvuln.generator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

class ProcessOutputBuffer {
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

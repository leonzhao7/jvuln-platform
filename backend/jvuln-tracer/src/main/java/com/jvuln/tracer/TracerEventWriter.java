package com.jvuln.tracer;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

final class TracerEventWriter implements Closeable {

    private static final int ARG_CAP = 512;

    private final String outputPath;
    private final long fileCap;
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private volatile BufferedWriter writer;

    TracerEventWriter(String outputPath, long fileCap) {
        this.outputPath = outputPath;
        this.fileCap = fileCap;
        try {
            this.writer = new BufferedWriter(new FileWriter(outputPath, true));
        } catch (Exception e) {
            this.writer = null;
        }
    }

    void writeEvent(int seq, int depth, String cls, String method, String[] args, String retOrThrow) {
        if (writer == null || bytesWritten.get() >= fileCap) return;
        String line = buildLine(seq, depth, cls, method, args, retOrThrow);
        try {
            synchronized (this) {
                if (bytesWritten.get() >= fileCap) return;
                writer.write(line);
                writer.newLine();
                writer.flush();
                bytesWritten.addAndGet(line.length() + 1);
            }
        } catch (Exception ignored) {}
    }

    private String buildLine(int seq, int depth, String cls, String method,
                              String[] args, String retOrThrow) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"seq\":").append(seq);
        sb.append(",\"depth\":").append(depth);
        sb.append(",\"class\":").append(jsonStr(cls));
        sb.append(",\"method\":").append(jsonStr(method));
        sb.append(",\"args\":[");
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(jsonStr(capArg(args[i])));
            }
        }
        sb.append("]");
        if (retOrThrow != null) {
            boolean isThrow = retOrThrow.startsWith("throw:");
            if (isThrow) {
                sb.append(",\"throw\":").append(jsonStr(retOrThrow.substring("throw:".length())));
            } else {
                sb.append(",\"ret\":").append(jsonStr(retOrThrow));
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String capArg(String s) {
        if (s == null) return "null";
        return s.length() > ARG_CAP ? s.substring(0, ARG_CAP) + "..." : s;
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    @Override
    public void close() {
        BufferedWriter w = writer;
        if (w != null) {
            try { w.close(); } catch (IOException ignored) {}
        }
    }
}

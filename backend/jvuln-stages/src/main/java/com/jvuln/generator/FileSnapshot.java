package com.jvuln.generator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

public class FileSnapshot {
    private static final int CONTEXT_FILE_LIMIT = 12000;

    public final String path;
    public final long size;
    public final String sha256;
    public final boolean text;
    public final boolean truncated;
    public final String content;

    private FileSnapshot(String path, long size, String sha256, boolean text, boolean truncated, String content) {
        this.path = path;
        this.size = size;
        this.sha256 = sha256 == null ? "" : sha256;
        this.text = text;
        this.truncated = truncated;
        this.content = content;
    }

    static FileSnapshot fromPath(Path root, Path file, String rel) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        boolean text = isProbablyText(rel, bytes);
        boolean truncated = text && bytes.length > CONTEXT_FILE_LIMIT;
        String content = null;
        if (text) {
            int len = Math.min(bytes.length, CONTEXT_FILE_LIMIT);
            content = new String(bytes, 0, len, StandardCharsets.UTF_8);
        }
        return new FileSnapshot(rel, bytes.length, sha256(bytes), text, truncated, content);
    }

    public String sha256Short() {
        return sha256.length() <= 12 ? sha256 : sha256.substring(0, 12);
    }

    private static boolean isProbablyText(String path, byte[] bytes) {
        if (path != null) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".java") || lower.endsWith(".xml") || lower.endsWith(".properties")
                    || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".sh")
                    || lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".json")
                    || lower.endsWith(".html") || lower.endsWith(".js") || lower.endsWith(".css")) {
                return true;
            }
        }
        int max = Math.min(bytes.length, 4096);
        for (int i = 0; i < max; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}

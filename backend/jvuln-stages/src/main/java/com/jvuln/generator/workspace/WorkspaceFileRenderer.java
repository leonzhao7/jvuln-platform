package com.jvuln.generator.workspace;

import com.jvuln.generator.FileSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 工作区文件渲染器
 *
 * 职责：渲染文件清单、文件内容、文件差异等，用于 Agent 上下文包
 *
 * @author JVuln Team
 */
@Component
public class WorkspaceFileRenderer {

    public String renderFileManifest(Map<String, FileSnapshot> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        List<String> paths = new ArrayList<String>(files.keySet());
        Collections.sort(paths);
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            FileSnapshot file = files.get(path);
            sb.append(path).append(" (").append(file.size).append(" bytes, sha256=")
              .append(file.sha256Short()).append(")\n");
        }
        return sb.toString().trim();
    }

    public String renderCurrentFileContents(Map<String, FileSnapshot> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        List<String> paths = new ArrayList<String>(files.keySet());
        Collections.sort(paths, new Comparator<String>() {
            public int compare(String a, String b) {
                int pa = fileContextPriority(a);
                int pb = fileContextPriority(b);
                if (pa != pb) return Integer.compare(pb, pa);
                return a.compareTo(b);
            }
        });
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            FileSnapshot file = files.get(path);
            if (!file.text || file.content == null) continue;
            sb.append("## ").append(path).append("\n");
            sb.append("```").append(fileFenceType(path)).append("\n");
            sb.append(file.content);
            if (!file.content.endsWith("\n")) sb.append("\n");
            sb.append("```\n");
            if (file.truncated) sb.append("*(content truncated)*\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    public int fileContextPriority(String path) {
        if (path == null) return 0;
        String lower = path.toLowerCase();
        if (lower.endsWith("controller.java") || lower.endsWith("service.java")
                || lower.endsWith("repository.java")) return 10;
        if (lower.endsWith(".java")) return 8;
        if (lower.endsWith("pom.xml")) return 7;
        if (lower.endsWith(".properties") || lower.endsWith(".yml") || lower.endsWith(".yaml")) return 6;
        if (lower.endsWith(".sh")) return 5;
        return 3;
    }

    public String fileFenceType(String path) {
        if (path == null) return "";
        String lower = path.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".sh")) return "bash";
        if (lower.endsWith(".properties")) return "properties";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        return "";
    }

    public String renderSnapshotDiff(Map<String, FileSnapshot> before, Map<String, FileSnapshot> after, int limit) {
        StringBuilder sb = new StringBuilder();
        List<String> allPaths = new ArrayList<String>();
        if (before != null) allPaths.addAll(before.keySet());
        if (after != null) {
            for (String path : after.keySet()) {
                if (!allPaths.contains(path)) allPaths.add(path);
            }
        }
        Collections.sort(allPaths);
        for (String path : allPaths) {
            FileSnapshot oldFile = before == null ? null : before.get(path);
            FileSnapshot newFile = after == null ? null : after.get(path);
            if (oldFile == null && newFile != null) {
                appendLimited(sb, renderAddedFile(newFile), limit);
            } else if (oldFile != null && newFile != null && !oldFile.sha256.equals(newFile.sha256)) {
                appendFileDiffContent(sb, oldFile, newFile, limit);
            }
        }
        return sb.toString();
    }

    public void appendFileDiffContent(StringBuilder sb, FileSnapshot oldFile, FileSnapshot newFile, int limit) {
        if (sb.length() > limit) return;
        if (!oldFile.text || !newFile.text) {
            sb.append("Modified (binary): ").append(newFile.path).append("\n");
            return;
        }
        sb.append(renderLineWindowDiff(newFile.path, oldFile.content, newFile.content));
    }

    public String renderAddedFile(FileSnapshot file) {
        StringBuilder sb = new StringBuilder();
        sb.append("Added: ").append(file.path).append("\n");
        if (file.text && file.content != null) {
            sb.append("```").append(fileFenceType(file.path)).append("\n");
            sb.append(file.content);
            if (!file.content.endsWith("\n")) sb.append("\n");
            sb.append("```\n");
        } else {
            sb.append("(binary file, ").append(file.size).append(" bytes)\n");
        }
        return sb.toString();
    }

    public String renderLineWindowDiff(String path, String oldContent, String newContent) {
        String[] oldLines = oldContent == null ? new String[0] : oldContent.split("\n", -1);
        String[] newLines = newContent == null ? new String[0] : newContent.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("Modified: ").append(path).append("\n");
        int maxLen = Math.max(oldLines.length, newLines.length);
        int diffCount = 0;
        for (int i = 0; i < maxLen; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : null;
            String newLine = i < newLines.length ? newLines[i] : null;
            if (oldLine != null && newLine != null && oldLine.equals(newLine)) continue;
            if (diffCount == 0) sb.append("```diff\n");
            if (oldLine != null && !oldLine.equals(newLine)) {
                sb.append("- ").append(oldLine).append("\n");
            }
            if (newLine != null && !newLine.equals(oldLine)) {
                sb.append("+ ").append(newLine).append("\n");
            }
            diffCount++;
            if (diffCount > 50) {
                sb.append("... (diff truncated)\n");
                break;
            }
        }
        if (diffCount > 0) sb.append("```\n");
        return sb.toString();
    }

    public void appendLimited(StringBuilder sb, String value, int limit) {
        if (sb.length() >= limit) return;
        int remaining = limit - sb.length();
        if (value.length() <= remaining) {
            sb.append(value);
        } else {
            sb.append(value.substring(0, remaining)).append("...\n");
        }
    }
}

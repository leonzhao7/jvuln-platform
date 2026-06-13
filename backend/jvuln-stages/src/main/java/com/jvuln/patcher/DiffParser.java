package com.jvuln.patcher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiffParser {

    private static final Pattern FILE_HEADER = Pattern.compile("^diff --git a/(.*) b/(.*)$", Pattern.MULTILINE);
    private static final Pattern OLD_FILE_HEADER = Pattern.compile("^---\\s+(.*)$", Pattern.MULTILINE);
    private static final Pattern NEW_FILE_HEADER = Pattern.compile("^\\+\\+\\+\\s+(.*)$", Pattern.MULTILINE);
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "(public|protected|private|static|\\s)+(\\w+\\s+)+\\w+\\s*\\([^)]*\\)");

    public static List<PatchInfo.FileDiff> parse(String rawDiff) {
        List<PatchInfo.FileDiff> diffs = new ArrayList<>();
        if (rawDiff == null || rawDiff.trim().isEmpty()) return diffs;

        String[] sections = rawDiff.split("(?=diff --git)");
        for (String section : sections) {
            if (section.trim().isEmpty()) continue;

            Matcher fileMatcher = FILE_HEADER.matcher(section);
            if (!fileMatcher.find()) continue;

            String filePath = fileMatcher.group(2);
            if (!filePath.endsWith(".java")) continue;
            String changeType = detectChangeType(section);

            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();

            for (String line : section.split("\n")) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    added.add(line.substring(1));
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    removed.add(line.substring(1));
                }
            }

            List<PatchInfo.MethodChange> methods = extractMethodChanges(section);

            diffs.add(new PatchInfo.FileDiff(filePath, changeType, section, added, removed, methods));
        }
        return diffs;
    }

    private static String detectChangeType(String section) {
        Matcher oldMatcher = OLD_FILE_HEADER.matcher(section);
        Matcher newMatcher = NEW_FILE_HEADER.matcher(section);
        String oldPath = oldMatcher.find() ? oldMatcher.group(1).trim() : "";
        String newPath = newMatcher.find() ? newMatcher.group(1).trim() : "";
        if ("/dev/null".equals(oldPath)) {
            return "added";
        }
        if ("/dev/null".equals(newPath)) {
            return "deleted";
        }
        return "modified";
    }

    private static List<PatchInfo.MethodChange> extractMethodChanges(String section) {
        List<PatchInfo.MethodChange> changes = new ArrayList<>();
        Pattern hunkHeader = Pattern.compile("^@@.*@@\\s*(.*)$", Pattern.MULTILINE);
        Matcher hunk = hunkHeader.matcher(section);

        while (hunk.find()) {
            String context = hunk.group(1).trim();
            Matcher methodMatcher = JAVA_METHOD.matcher(context);
            if (methodMatcher.find()) {
                changes.add(new PatchInfo.MethodChange(
                        context, "modified", "", ""
                ));
            }
        }
        return changes;
    }
}

package com.jvuln.analyzer;

import java.util.Set;

public class JavaFileChange {
    public final String filePath;
    public final String changeType;
    public final String rawSection;
    public final String removedCode;
    public final String addedCode;
    public final Set<String> methodNames;

    public JavaFileChange(String filePath, String changeType, String rawSection, String removedCode,
                          String addedCode, Set<String> methodNames) {
        this.filePath = filePath;
        this.changeType = changeType;
        this.rawSection = rawSection;
        this.removedCode = removedCode;
        this.addedCode = addedCode;
        this.methodNames = methodNames;
    }

    public int removedLineCount() {
        if (removedCode.isEmpty()) return 0;
        return (int) removedCode.chars().filter(c -> c == '\n').count();
    }

    public int addedLineCount() {
        if (addedCode.isEmpty()) return 0;
        return (int) addedCode.chars().filter(c -> c == '\n').count();
    }

    public String combinedText() {
        return (filePath == null ? "" : filePath) + "\n"
                + (removedCode == null ? "" : removedCode) + "\n"
                + (addedCode == null ? "" : addedCode);
    }
}

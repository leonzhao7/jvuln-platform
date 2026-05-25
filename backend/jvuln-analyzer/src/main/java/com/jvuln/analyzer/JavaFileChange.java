package com.jvuln.analyzer;

import java.util.Set;

class JavaFileChange {
    final String filePath;
    final String rawSection;
    final String removedCode;
    final String addedCode;
    final Set<String> methodNames;

    JavaFileChange(String filePath, String rawSection, String removedCode,
                   String addedCode, Set<String> methodNames) {
        this.filePath = filePath;
        this.rawSection = rawSection;
        this.removedCode = removedCode;
        this.addedCode = addedCode;
        this.methodNames = methodNames;
    }

    int removedLineCount() {
        if (removedCode.isEmpty()) return 0;
        return (int) removedCode.chars().filter(c -> c == '\n').count();
    }
}

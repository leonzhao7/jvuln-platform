package com.jvuln.patcher;

import java.util.List;

public class PatchInfo {

    private final String commitHash;
    private final String commitMessage;
    private final String parentHash;
    private final List<FileDiff> diffs;
    private final String strategy;

    public PatchInfo(String commitHash, String commitMessage, String parentHash,
                     List<FileDiff> diffs, String strategy) {
        this.commitHash = commitHash;
        this.commitMessage = commitMessage;
        this.parentHash = parentHash;
        this.diffs = diffs;
        this.strategy = strategy;
    }

    public String getCommitHash() { return commitHash; }
    public String getCommitMessage() { return commitMessage; }
    public String getParentHash() { return parentHash; }
    public List<FileDiff> getDiffs() { return diffs; }
    public String getStrategy() { return strategy; }

    public static class FileDiff {
        private final String filePath;
        private final String diffContent;
        private final List<String> addedLines;
        private final List<String> removedLines;
        private final List<MethodChange> methodChanges;

        public FileDiff(String filePath, String diffContent, List<String> addedLines,
                        List<String> removedLines, List<MethodChange> methodChanges) {
            this.filePath = filePath;
            this.diffContent = diffContent;
            this.addedLines = addedLines;
            this.removedLines = removedLines;
            this.methodChanges = methodChanges;
        }

        public String getFilePath() { return filePath; }
        public String getDiffContent() { return diffContent; }
        public List<String> getAddedLines() { return addedLines; }
        public List<String> getRemovedLines() { return removedLines; }
        public List<MethodChange> getMethodChanges() { return methodChanges; }
    }

    public static class MethodChange {
        private final String methodName;
        private final String changeType;
        private final String beforeSnippet;
        private final String afterSnippet;

        public MethodChange(String methodName, String changeType,
                            String beforeSnippet, String afterSnippet) {
            this.methodName = methodName;
            this.changeType = changeType;
            this.beforeSnippet = beforeSnippet;
            this.afterSnippet = afterSnippet;
        }

        public String getMethodName() { return methodName; }
        public String getChangeType() { return changeType; }
        public String getBeforeSnippet() { return beforeSnippet; }
        public String getAfterSnippet() { return afterSnippet; }
    }
}

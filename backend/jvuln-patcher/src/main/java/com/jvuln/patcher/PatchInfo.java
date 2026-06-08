package com.jvuln.patcher;

import java.util.List;

public class PatchInfo {

    private final String commitHash;
    private final String commitMessage;
    private final String parentHash;
    private final List<FileDiff> diffs;
    private final String rawDiff;
    private final String strategy;
    private final PatchEvidence patchEvidence;

    public PatchInfo(String commitHash, String commitMessage, String parentHash,
                     List<FileDiff> diffs, String strategy) {
        this(commitHash, commitMessage, parentHash, diffs, null, strategy);
    }

    public PatchInfo(String commitHash, String commitMessage, String parentHash,
                     List<FileDiff> diffs, String rawDiff, String strategy) {
        this(commitHash, commitMessage, parentHash, diffs, rawDiff, strategy, null);
    }

    public PatchInfo(String commitHash, String commitMessage, String parentHash,
                     List<FileDiff> diffs, String rawDiff, String strategy,
                     PatchEvidence patchEvidence) {
        this.commitHash = commitHash;
        this.commitMessage = commitMessage;
        this.parentHash = parentHash;
        this.diffs = diffs;
        this.rawDiff = rawDiff;
        this.strategy = strategy;
        this.patchEvidence = patchEvidence;
    }

    public String getCommitHash() { return commitHash; }
    public String getCommitMessage() { return commitMessage; }
    public String getParentHash() { return parentHash; }
    public List<FileDiff> getDiffs() { return diffs; }
    public String getRawDiff() { return rawDiff; }
    public String getStrategy() { return strategy; }
    public PatchEvidence getPatchEvidence() { return patchEvidence; }

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

    public static class PatchEvidence {
        private final String summary;
        private final String primaryCategory;
        private final int changedFileCount;
        private final int javaFileCount;
        private final List<String> changedFiles;
        private final List<String> modules;
        private final List<CategoryVote> categoryVotes;
        private final List<EvidenceSignal> signals;

        public PatchEvidence(String summary, String primaryCategory, int changedFileCount,
                             int javaFileCount, List<String> changedFiles, List<String> modules,
                             List<CategoryVote> categoryVotes, List<EvidenceSignal> signals) {
            this.summary = summary;
            this.primaryCategory = primaryCategory;
            this.changedFileCount = changedFileCount;
            this.javaFileCount = javaFileCount;
            this.changedFiles = changedFiles;
            this.modules = modules;
            this.categoryVotes = categoryVotes;
            this.signals = signals;
        }

        public String getSummary() { return summary; }
        public String getPrimaryCategory() { return primaryCategory; }
        public int getChangedFileCount() { return changedFileCount; }
        public int getJavaFileCount() { return javaFileCount; }
        public List<String> getChangedFiles() { return changedFiles; }
        public List<String> getModules() { return modules; }
        public List<CategoryVote> getCategoryVotes() { return categoryVotes; }
        public List<EvidenceSignal> getSignals() { return signals; }
    }

    public static class CategoryVote {
        private final String category;
        private final int score;
        private final String reason;

        public CategoryVote(String category, int score, String reason) {
            this.category = category;
            this.score = score;
            this.reason = reason;
        }

        public String getCategory() { return category; }
        public int getScore() { return score; }
        public String getReason() { return reason; }
    }

    public static class EvidenceSignal {
        private final String category;
        private final String filePath;
        private final String token;
        private final int weight;
        private final String reason;

        public EvidenceSignal(String category, String filePath, String token, int weight, String reason) {
            this.category = category;
            this.filePath = filePath;
            this.token = token;
            this.weight = weight;
            this.reason = reason;
        }

        public String getCategory() { return category; }
        public String getFilePath() { return filePath; }
        public String getToken() { return token; }
        public int getWeight() { return weight; }
        public String getReason() { return reason; }
    }
}

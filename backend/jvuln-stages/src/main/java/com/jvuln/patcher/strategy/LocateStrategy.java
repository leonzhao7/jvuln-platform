package com.jvuln.patcher.strategy;

import java.util.List;
import java.util.Optional;

public interface LocateStrategy {
    String name();
    int priority();
    Optional<PatchResult> locate(String cveId, String sourceRepo, List<String> knownCommits) throws Exception;

    class PatchResult {
        private final String commitUrl;
        private final String commitHash;
        private final String commitMessage;
        private final String rawDiff;

        public PatchResult(String commitUrl, String commitHash, String commitMessage, String rawDiff) {
            this.commitUrl = commitUrl;
            this.commitHash = commitHash;
            this.commitMessage = commitMessage;
            this.rawDiff = rawDiff;
        }

        public String getCommitUrl() { return commitUrl; }
        public String getCommitHash() { return commitHash; }
        public String getCommitMessage() { return commitMessage; }
        public String getRawDiff() { return rawDiff; }
    }
}

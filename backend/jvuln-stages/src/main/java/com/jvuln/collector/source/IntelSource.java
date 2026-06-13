package com.jvuln.collector.source;

import com.jvuln.store.model.CveIntelligence;
import java.util.List;

public interface IntelSource {
    String name();
    IntelFragment collect(String cveId) throws Exception;

    class IntelFragment {
        private final String sourceName;
        private final boolean success;
        private final String description;
        private final String cweId;
        private final String cvssScore;
        private final String cvssSeverity;
        private final String artifactGroupId;
        private final String artifactId;
        private final String affectedFrom;
        private final String affectedTo;
        private final String fixedVersion;
        private final String sourceRepo;
        private final List<String> fixCommits;
        private final List<CveIntelligence.Article> articles;
        private final String rawJson;

        public IntelFragment(String sourceName, boolean success, String description, String cweId,
                             String cvssScore, String cvssSeverity, String artifactGroupId,
                             String artifactId, String affectedFrom, String affectedTo,
                             String fixedVersion, String sourceRepo, List<String> fixCommits,
                             List<CveIntelligence.Article> articles, String rawJson) {
            this.sourceName = sourceName;
            this.success = success;
            this.description = description;
            this.cweId = cweId;
            this.cvssScore = cvssScore;
            this.cvssSeverity = cvssSeverity;
            this.artifactGroupId = artifactGroupId;
            this.artifactId = artifactId;
            this.affectedFrom = affectedFrom;
            this.affectedTo = affectedTo;
            this.fixedVersion = fixedVersion;
            this.sourceRepo = sourceRepo;
            this.fixCommits = fixCommits;
            this.articles = articles;
            this.rawJson = rawJson;
        }

        public String getSourceName() { return sourceName; }
        public boolean isSuccess() { return success; }
        public String getDescription() { return description; }
        public String getCweId() { return cweId; }
        public String getCvssScore() { return cvssScore; }
        public String getCvssSeverity() { return cvssSeverity; }
        public String getArtifactGroupId() { return artifactGroupId; }
        public String getArtifactId() { return artifactId; }
        public String getAffectedFrom() { return affectedFrom; }
        public String getAffectedTo() { return affectedTo; }
        public String getFixedVersion() { return fixedVersion; }
        public String getSourceRepo() { return sourceRepo; }
        public List<String> getFixCommits() { return fixCommits; }
        public List<CveIntelligence.Article> getArticles() { return articles; }
        public String getRawJson() { return rawJson; }
    }
}

package com.jvuln.store.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class CveIntelligence {

    private final String cveId;
    private final String description;
    private final CvssScore cvss;
    private final String cweId;
    private final MavenCoordinate artifact;
    private final VersionRange affectedVersions;
    private final String fixedVersion;
    private final String sourceRepo;
    private final List<String> fixCommits;
    private final List<Article> articles;
    private final List<ReferenceFinding> referenceFindings;
    private final Instant collectedAt;

    public CveIntelligence(String cveId, String description, CvssScore cvss, String cweId,
                           MavenCoordinate artifact, VersionRange affectedVersions,
                           String fixedVersion, String sourceRepo,
                           List<String> fixCommits, List<Article> articles,
                           List<ReferenceFinding> referenceFindings, Instant collectedAt) {
        this.cveId = cveId;
        this.description = description;
        this.cvss = cvss;
        this.cweId = cweId;
        this.artifact = artifact;
        this.affectedVersions = affectedVersions;
        this.fixedVersion = fixedVersion;
        this.sourceRepo = sourceRepo;
        this.fixCommits = fixCommits;
        this.articles = articles;
        this.referenceFindings = referenceFindings;
        this.collectedAt = collectedAt;
    }

    public String getCveId() { return cveId; }
    public String getDescription() { return description; }
    public CvssScore getCvss() { return cvss; }
    public String getCweId() { return cweId; }
    public MavenCoordinate getArtifact() { return artifact; }
    public VersionRange getAffectedVersions() { return affectedVersions; }
    public String getFixedVersion() { return fixedVersion; }
    public String getSourceRepo() { return sourceRepo; }
    public List<String> getFixCommits() { return fixCommits; }
    public List<Article> getArticles() { return articles; }
    public List<ReferenceFinding> getReferenceFindings() { return referenceFindings; }
    public Instant getCollectedAt() { return collectedAt; }

    public static class CvssScore {
        private final BigDecimal score;
        private final String vector;
        private final String severity;

        public CvssScore(BigDecimal score, String vector, String severity) {
            this.score = score;
            this.vector = vector;
            this.severity = severity;
        }

        public BigDecimal getScore() { return score; }
        public String getVector() { return vector; }
        public String getSeverity() { return severity; }
    }

    public static class MavenCoordinate {
        private final String groupId;
        private final String artifactId;

        public MavenCoordinate(String groupId, String artifactId) {
            this.groupId = groupId;
            this.artifactId = artifactId;
        }

        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public String toGav() { return groupId + ":" + artifactId; }
    }

    public static class VersionRange {
        private final String from;
        private final String to;

        public VersionRange(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public String getFrom() { return from; }
        public String getTo() { return to; }
    }

    public static class Article {
        private final String title;
        private final String url;
        private final String source;
        private final String summary;
        private final String category;  // fix, analysis, advisory, poc, other

        public Article(String title, String url, String source, String summary) {
            this(title, url, source, summary, null);
        }

        public Article(String title, String url, String source, String summary, String category) {
            this.title = title;
            this.url = url;
            this.source = source;
            this.summary = summary;
            this.category = category;
        }

        public String getTitle() { return title; }
        public String getUrl() { return url; }
        public String getSource() { return source; }
        public String getSummary() { return summary; }
        public String getCategory() { return category; }
    }

    public static class ReferenceFinding {
        private final String kind;
        private final String url;
        private final String discoveredFrom;
        private final String source;
        private final String confidence;

        public ReferenceFinding(String kind, String url, String discoveredFrom,
                                String source, String confidence) {
            this.kind = kind;
            this.url = url;
            this.discoveredFrom = discoveredFrom;
            this.source = source;
            this.confidence = confidence;
        }

        public String getKind() { return kind; }
        public String getUrl() { return url; }
        public String getDiscoveredFrom() { return discoveredFrom; }
        public String getSource() { return source; }
        public String getConfidence() { return confidence; }
    }
}

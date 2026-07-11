package com.jvuln.store.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static com.jvuln.util.ValueUtils.immutableList;
import static com.jvuln.util.ValueUtils.text;

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
    private final List<SourceResult> sourceResults;
    private final List<EvidenceResult> evidenceResults;
    private final DescriptionAdjudication descriptionAdjudication;

    public CveIntelligence(String cveId, String description, CvssScore cvss, String cweId,
                           MavenCoordinate artifact, VersionRange affectedVersions,
                           String fixedVersion, String sourceRepo,
                           List<String> fixCommits, List<Article> articles,
                           List<ReferenceFinding> referenceFindings, Instant collectedAt) {
        this(cveId, description, cvss, cweId, artifact, affectedVersions,
                fixedVersion, sourceRepo, fixCommits, articles, referenceFindings,
                collectedAt, Collections.<SourceResult>emptyList(),
                Collections.<EvidenceResult>emptyList(), DescriptionAdjudication.notRun(""));
    }

    @JsonCreator
    public CveIntelligence(
            @JsonProperty("cveId") String cveId,
            @JsonProperty("description") String description,
            @JsonProperty("cvss") CvssScore cvss,
            @JsonProperty("cweId") String cweId,
            @JsonProperty("artifact") MavenCoordinate artifact,
            @JsonProperty("affectedVersions") VersionRange affectedVersions,
            @JsonProperty("fixedVersion") String fixedVersion,
            @JsonProperty("sourceRepo") String sourceRepo,
            @JsonProperty("fixCommits") List<String> fixCommits,
            @JsonProperty("articles") List<Article> articles,
            @JsonProperty("referenceFindings") List<ReferenceFinding> referenceFindings,
            @JsonProperty("collectedAt") Instant collectedAt,
            @JsonProperty("sourceResults") List<SourceResult> sourceResults,
            @JsonProperty("evidenceResults") List<EvidenceResult> evidenceResults,
            @JsonProperty("descriptionAdjudication") DescriptionAdjudication adjudication) {
        this.cveId = text(cveId);
        this.description = text(description);
        this.cvss = cvss;
        this.cweId = text(cweId);
        this.artifact = artifact;
        this.affectedVersions = affectedVersions;
        this.fixedVersion = text(fixedVersion);
        this.sourceRepo = text(sourceRepo);
        this.fixCommits = immutableList(fixCommits);
        this.articles = immutableList(articles);
        this.referenceFindings = immutableList(referenceFindings);
        this.collectedAt = collectedAt;
        this.sourceResults = immutableList(sourceResults);
        this.evidenceResults = immutableList(evidenceResults);
        this.descriptionAdjudication = adjudication == null
                ? DescriptionAdjudication.notRun("") : adjudication;
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
    public List<SourceResult> getSourceResults() { return sourceResults; }
    public List<EvidenceResult> getEvidenceResults() { return evidenceResults; }
    public DescriptionAdjudication getDescriptionAdjudication() { return descriptionAdjudication; }

    public static class CvssScore {
        private final BigDecimal score;
        private final String vector;
        private final String severity;

        @JsonCreator
        public CvssScore(@JsonProperty("score") BigDecimal score,
                         @JsonProperty("vector") String vector,
                         @JsonProperty("severity") String severity) {
            this.score = score == null ? BigDecimal.ZERO : score;
            this.vector = text(vector);
            this.severity = text(severity);
        }

        public BigDecimal getScore() { return score; }
        public String getVector() { return vector; }
        public String getSeverity() { return severity; }
    }

    public static class MavenCoordinate {
        private final String groupId;
        private final String artifactId;

        @JsonCreator
        public MavenCoordinate(@JsonProperty("groupId") String groupId,
                               @JsonProperty("artifactId") String artifactId) {
            this.groupId = text(groupId);
            this.artifactId = text(artifactId);
        }

        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public String toGav() { return groupId + ":" + artifactId; }
    }

    public static class VersionRange {
        private final String from;
        private final String to;

        @JsonCreator
        public VersionRange(@JsonProperty("from") String from,
                            @JsonProperty("to") String to) {
            this.from = text(from);
            this.to = text(to);
        }

        public String getFrom() { return from; }
        public String getTo() { return to; }
    }

    public static class Article {
        private final String title;
        private final String url;
        private final String source;
        private final String summary;
        private final String category;
        private final List<String> discoveredFrom;
        private final String classificationMethod;
        private final String classificationReason;
        private final BigDecimal classificationConfidence;

        public Article(String title, String url, String source, String summary) {
            this(title, url, source, summary, null);
        }

        public Article(String title, String url, String source, String summary, String category) {
            this(title, url, source, summary, category,
                    source == null || source.isEmpty()
                            ? Collections.<String>emptyList() : Collections.singletonList(source),
                    "", "", BigDecimal.ZERO);
        }

        @JsonCreator
        public Article(
                @JsonProperty("title") String title,
                @JsonProperty("url") String url,
                @JsonProperty("source") String source,
                @JsonProperty("summary") String summary,
                @JsonProperty("category") String category,
                @JsonProperty("discoveredFrom") List<String> discoveredFrom,
                @JsonProperty("classificationMethod") String classificationMethod,
                @JsonProperty("classificationReason") String classificationReason,
                @JsonProperty("classificationConfidence") BigDecimal confidence) {
            this.title = text(title);
            this.url = text(url);
            this.source = text(source);
            this.summary = text(summary);
            this.category = category;
            if (discoveredFrom == null && !this.source.isEmpty()) {
                this.discoveredFrom = Collections.singletonList(this.source);
            } else {
                this.discoveredFrom = immutableList(discoveredFrom);
            }
            this.classificationMethod = text(classificationMethod);
            this.classificationReason = text(classificationReason);
            this.classificationConfidence = validConfidence(confidence);
        }

        public String getTitle() { return title; }
        public String getUrl() { return url; }
        public String getSource() { return source; }
        public String getSummary() { return summary; }
        public String getCategory() { return category; }
        public List<String> getDiscoveredFrom() { return discoveredFrom; }
        public String getClassificationMethod() { return classificationMethod; }
        public String getClassificationReason() { return classificationReason; }
        public BigDecimal getClassificationConfidence() { return classificationConfidence; }

        private static BigDecimal validConfidence(BigDecimal confidence) {
            BigDecimal value = confidence == null ? BigDecimal.ZERO : confidence;
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("classificationConfidence must be between 0 and 1");
            }
            return value;
        }
    }

    public static class ReferenceFinding {
        private final String kind;
        private final String url;
        private final String discoveredFrom;
        private final String source;
        private final String confidence;

        @JsonCreator
        public ReferenceFinding(@JsonProperty("kind") String kind,
                                @JsonProperty("url") String url,
                                @JsonProperty("discoveredFrom") String discoveredFrom,
                                @JsonProperty("source") String source,
                                @JsonProperty("confidence") String confidence) {
            this.kind = text(kind);
            this.url = text(url);
            this.discoveredFrom = text(discoveredFrom);
            this.source = text(source);
            this.confidence = text(confidence);
        }

        public String getKind() { return kind; }
        public String getUrl() { return url; }
        public String getDiscoveredFrom() { return discoveredFrom; }
        public String getSource() { return source; }
        public String getConfidence() { return confidence; }
    }

}

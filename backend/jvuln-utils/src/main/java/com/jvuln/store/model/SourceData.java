package com.jvuln.store.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

import static com.jvuln.util.ValueUtils.immutableList;
import static com.jvuln.util.ValueUtils.text;

public class SourceData {

    private final String cweId;
    private final String cvssScore;
    private final String cvssVector;
    private final String cvssSeverity;
    private final String artifactGroupId;
    private final String artifactId;
    private final String affectedFrom;
    private final String affectedTo;
    private final String fixedVersion;
    private final String sourceRepo;
    private final List<String> fixCommits;
    private final List<CveIntelligence.Article> references;

    @JsonCreator
    public SourceData(
            @JsonProperty("cweId") String cweId,
            @JsonProperty("cvssScore") String cvssScore,
            @JsonProperty("cvssVector") String cvssVector,
            @JsonProperty("cvssSeverity") String cvssSeverity,
            @JsonProperty("artifactGroupId") String artifactGroupId,
            @JsonProperty("artifactId") String artifactId,
            @JsonProperty("affectedFrom") String affectedFrom,
            @JsonProperty("affectedTo") String affectedTo,
            @JsonProperty("fixedVersion") String fixedVersion,
            @JsonProperty("sourceRepo") String sourceRepo,
            @JsonProperty("fixCommits") List<String> fixCommits,
            @JsonProperty("references") List<CveIntelligence.Article> references) {
        this.cweId = text(cweId);
        this.cvssScore = text(cvssScore);
        this.cvssVector = text(cvssVector);
        this.cvssSeverity = text(cvssSeverity);
        this.artifactGroupId = text(artifactGroupId);
        this.artifactId = text(artifactId);
        this.affectedFrom = text(affectedFrom);
        this.affectedTo = text(affectedTo);
        this.fixedVersion = text(fixedVersion);
        this.sourceRepo = text(sourceRepo);
        this.fixCommits = immutableList(fixCommits);
        this.references = immutableList(references);
    }

    public static SourceData empty() {
        return new SourceData("", "", "", "", "", "", "", "", "", "",
                Collections.<String>emptyList(), Collections.<CveIntelligence.Article>emptyList());
    }

    public String getCweId() { return cweId; }
    public String getCvssScore() { return cvssScore; }
    public String getCvssVector() { return cvssVector; }
    public String getCvssSeverity() { return cvssSeverity; }
    public String getArtifactGroupId() { return artifactGroupId; }
    public String getArtifactId() { return artifactId; }
    public String getAffectedFrom() { return affectedFrom; }
    public String getAffectedTo() { return affectedTo; }
    public String getFixedVersion() { return fixedVersion; }
    public String getSourceRepo() { return sourceRepo; }
    public List<String> getFixCommits() { return fixCommits; }
    public List<CveIntelligence.Article> getReferences() { return references; }

}

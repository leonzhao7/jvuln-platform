package com.jvuln.collector;

import com.jvuln.store.model.CveIntelligence;
import com.jvuln.store.model.DescriptionAdjudication;
import com.jvuln.store.model.EvidenceResult;
import com.jvuln.store.model.SourceData;
import com.jvuln.store.model.SourceResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IntelligenceAssembler {

    private static final Pattern DESCRIPTION_AFFECTED = Pattern.compile(
            "v?(\\d+(?:\\.\\d+)+)\\s+and below", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESCRIPTION_COMPONENT = Pattern.compile(
            "^([a-zA-Z0-9_.-]+)\\s+v?\\d+(?:\\.\\d+)+", Pattern.CASE_INSENSITIVE);

    public Draft merge(String cveId, List<SourceResult> sourceResults) {
        Draft draft = new Draft(cveId, sourceResults);
        String sourceDescription = "";
        for (SourceResult result : sourceResults) {
            if (!result.isSuccess()) {
                continue;
            }
            SourceData data = result.getParsedData();
            sourceDescription = first(sourceDescription, result.getOriginalDescription());
            draft.cweId = first(draft.cweId, data.getCweId());
            draft.cvssScore = firstScore(draft.cvssScore, data.getCvssScore());
            draft.cvssVector = first(draft.cvssVector, data.getCvssVector());
            draft.cvssSeverity = first(draft.cvssSeverity, data.getCvssSeverity());
            draft.groupId = first(draft.groupId, data.getArtifactGroupId());
            draft.artifactId = first(draft.artifactId, data.getArtifactId());
            draft.affectedFrom = first(draft.affectedFrom, data.getAffectedFrom());
            draft.affectedTo = first(draft.affectedTo, data.getAffectedTo());
            draft.fixedVersion = first(draft.fixedVersion, data.getFixedVersion());
            draft.sourceRepo = first(draft.sourceRepo, data.getSourceRepo());
            draft.fixCommits.addAll(data.getFixCommits());
            draft.articles.addAll(data.getReferences());
        }
        applyCompatibleFallbacks(draft, sourceDescription);
        return draft;
    }

    private void applyCompatibleFallbacks(Draft draft, String description) {
        if (draft.sourceRepo.isEmpty()) {
            draft.sourceRepo = repositoryFrom(draft.articles);
        }
        if (draft.artifactId.isEmpty()) {
            draft.artifactId = repositoryName(draft.sourceRepo);
        }
        Matcher component = DESCRIPTION_COMPONENT.matcher(description);
        if (component.find() && (draft.artifactId.isEmpty()
                || draft.artifactId.equals(repositoryName(draft.sourceRepo)))) {
            draft.artifactId = component.group(1);
        }
        if (draft.affectedTo.isEmpty()) {
            Matcher affected = DESCRIPTION_AFFECTED.matcher(description);
            if (affected.find()) {
                draft.affectedTo = "<= " + affected.group(1);
            }
        }
    }

    private String repositoryFrom(List<CveIntelligence.Article> articles) {
        for (CveIntelligence.Article article : articles) {
            String url = article.getUrl();
            int host = url.indexOf("github.com/");
            if (host < 0) host = url.indexOf("gitee.com/");
            if (host < 0) continue;
            String prefix = url.substring(0, host);
            String tail = url.substring(host);
            String[] parts = tail.split("/");
            if (parts.length >= 3) {
                return prefix + parts[0] + "/" + parts[1] + "/" + stripGit(parts[2]);
            }
        }
        return "";
    }

    private String repositoryName(String repository) {
        if (repository == null || repository.isEmpty()) return "";
        String normalized = repository;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        return stripGit(slash >= 0 ? normalized.substring(slash + 1) : normalized);
    }

    private String stripGit(String value) {
        return value.endsWith(".git") ? value.substring(0, value.length() - 4) : value;
    }

    private String first(String current, String candidate) {
        return current == null || current.isEmpty()
                ? candidate == null ? "" : candidate : current;
    }

    private BigDecimal firstScore(BigDecimal current, String candidate) {
        if (current.compareTo(BigDecimal.ZERO) != 0 || candidate == null || candidate.isEmpty()) {
            return current;
        }
        try {
            return new BigDecimal(candidate);
        } catch (NumberFormatException e) {
            return current;
        }
    }

    public static class Draft {
        private final String cveId;
        private final List<SourceResult> sourceResults;
        private final Instant collectedAt = Instant.now();
        private BigDecimal cvssScore = BigDecimal.ZERO;
        private String cvssVector = "";
        private String cvssSeverity = "";
        private String cweId = "";
        private String groupId = "";
        private String artifactId = "";
        private String affectedFrom = "";
        private String affectedTo = "";
        private String fixedVersion = "";
        private String sourceRepo = "";
        private final Set<String> fixCommits = new LinkedHashSet<>();
        private final List<CveIntelligence.Article> articles = new ArrayList<>();

        private Draft(String cveId, List<SourceResult> sourceResults) {
            this.cveId = cveId;
            this.sourceResults = Collections.unmodifiableList(
                    new ArrayList<>(sourceResults));
        }

        public List<CveIntelligence.Article> getArticles() {
            return Collections.unmodifiableList(articles);
        }

        public CveIntelligence toIntelligence(
                String description, List<CveIntelligence.Article> classifiedArticles,
                List<EvidenceResult> evidence,
                DescriptionAdjudication adjudication) {
            return new CveIntelligence(cveId, description,
                    new CveIntelligence.CvssScore(cvssScore, cvssVector, cvssSeverity),
                    cweId, new CveIntelligence.MavenCoordinate(groupId, artifactId),
                    new CveIntelligence.VersionRange(affectedFrom, affectedTo),
                    fixedVersion, sourceRepo, new ArrayList<>(fixCommits),
                    classifiedArticles, Collections.<CveIntelligence.ReferenceFinding>emptyList(),
                    collectedAt, sourceResults, evidence, adjudication);
        }
    }
}

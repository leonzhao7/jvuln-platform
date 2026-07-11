package com.jvuln.collector.source;

import com.jvuln.store.model.CveIntelligence;
import com.jvuln.store.model.SourceData;
import com.jvuln.store.model.SourceResult;

import java.util.List;

import static com.jvuln.util.ValueUtils.text;

public interface IntelSource {
    String name();
    IntelFragment collect(String cveId) throws Exception;

    class SourceException extends Exception {
        private final String errorCode;
        private final String rawPayload;

        public SourceException(String errorCode, String message,
                               String rawPayload, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode == null ? "SourceError" : errorCode;
            this.rawPayload = rawPayload == null ? "" : rawPayload;
        }

        public String getErrorCode() { return errorCode; }
        public String getRawPayload() { return rawPayload; }
    }

    class IntelFragment {
        private final String sourceName;
        private final SourceResult.Status status;
        private final String description;
        private final SourceData parsedData;
        private final String rawPayload;

        public IntelFragment(String sourceName, boolean success, String description, String cweId,
                             String cvssScore, String cvssSeverity, String artifactGroupId,
                             String artifactId, String affectedFrom, String affectedTo,
                             String fixedVersion, String sourceRepo, List<String> fixCommits,
                             List<CveIntelligence.Article> articles, String rawJson) {
            this(sourceName, success ? SourceResult.Status.SUCCESS : SourceResult.Status.NOT_FOUND,
                    description, new SourceData(cweId, cvssScore, "", cvssSeverity,
                            artifactGroupId, artifactId, affectedFrom, affectedTo,
                            fixedVersion, sourceRepo, fixCommits, articles), rawJson);
        }

        private IntelFragment(String sourceName, SourceResult.Status status,
                              String description, SourceData parsedData, String rawPayload) {
            this.sourceName = text(sourceName);
            this.status = status;
            this.description = text(description);
            this.parsedData = parsedData == null ? SourceData.empty() : parsedData;
            this.rawPayload = text(rawPayload);
        }

        public static IntelFragment success(String sourceName, String description,
                                            SourceData parsedData, String rawPayload) {
            return new IntelFragment(sourceName, SourceResult.Status.SUCCESS,
                    description, parsedData, rawPayload);
        }

        public static IntelFragment notFound(String sourceName, String rawPayload) {
            return new IntelFragment(sourceName, SourceResult.Status.NOT_FOUND,
                    "", SourceData.empty(), rawPayload);
        }

        public String getSourceName() { return sourceName; }
        public SourceResult.Status getStatus() { return status; }
        public boolean isSuccess() { return status == SourceResult.Status.SUCCESS; }
        public String getDescription() { return description; }
        public SourceData getParsedData() { return parsedData; }
        public String getCweId() { return parsedData.getCweId(); }
        public String getCvssScore() { return parsedData.getCvssScore(); }
        public String getCvssVector() { return parsedData.getCvssVector(); }
        public String getCvssSeverity() { return parsedData.getCvssSeverity(); }
        public String getArtifactGroupId() { return parsedData.getArtifactGroupId(); }
        public String getArtifactId() { return parsedData.getArtifactId(); }
        public String getAffectedFrom() { return parsedData.getAffectedFrom(); }
        public String getAffectedTo() { return parsedData.getAffectedTo(); }
        public String getFixedVersion() { return parsedData.getFixedVersion(); }
        public String getSourceRepo() { return parsedData.getSourceRepo(); }
        public List<String> getFixCommits() { return parsedData.getFixCommits(); }
        public List<CveIntelligence.Article> getArticles() { return parsedData.getReferences(); }
        public String getRawPayload() { return rawPayload; }
        public String getRawJson() { return rawPayload; }

        public static IntelFragment emptyNotFound(String sourceName) {
            return notFound(sourceName, "");
        }

    }
}

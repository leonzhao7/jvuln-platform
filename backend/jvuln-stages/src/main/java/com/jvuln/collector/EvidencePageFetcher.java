package com.jvuln.collector;

import com.jvuln.store.model.EvidenceResult;

import static com.jvuln.util.ValueUtils.text;

public interface EvidencePageFetcher {

    FetchOutcome fetch(String url);

    class FetchOutcome {
        private final EvidenceResult.FetchStatus status;
        private final String excerpt;
        private final String errorMessage;

        private FetchOutcome(EvidenceResult.FetchStatus status,
                             String excerpt, String errorMessage) {
            this.status = status;
            this.excerpt = text(excerpt);
            this.errorMessage = text(errorMessage);
        }

        public static FetchOutcome success(String excerpt) {
            return new FetchOutcome(EvidenceResult.FetchStatus.SUCCESS, excerpt, "");
        }

        public static FetchOutcome failed(String errorMessage) {
            return new FetchOutcome(EvidenceResult.FetchStatus.FAILED, "", errorMessage);
        }

        public static FetchOutcome timedOut(String errorMessage) {
            return new FetchOutcome(EvidenceResult.FetchStatus.TIMED_OUT, "", errorMessage);
        }

        public static FetchOutcome rejected(String errorMessage) {
            return new FetchOutcome(EvidenceResult.FetchStatus.REJECTED, "", errorMessage);
        }

        public EvidenceResult.FetchStatus getStatus() { return status; }
        public String getExcerpt() { return excerpt; }
        public String getErrorMessage() { return errorMessage; }
    }
}

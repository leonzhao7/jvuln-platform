package com.jvuln.collector;

import com.jvuln.store.model.EvidenceResult;

import java.util.Collections;
import java.util.List;

import static com.jvuln.util.ValueUtils.text;

public interface EvidencePageFetcher {

    FetchOutcome fetch(String url);

    /** Downloads a single image, enforcing the same public-URL policy as page fetches. */
    ImageOutcome fetchImage(String url);

    class FetchOutcome {
        private final EvidenceResult.FetchStatus status;
        private final String excerpt;
        private final String errorMessage;
        private final List<String> imageUrls;

        private FetchOutcome(EvidenceResult.FetchStatus status,
                             String excerpt, String errorMessage, List<String> imageUrls) {
            this.status = status;
            this.excerpt = text(excerpt);
            this.errorMessage = text(errorMessage);
            this.imageUrls = imageUrls == null ? Collections.emptyList() : imageUrls;
        }

        public static FetchOutcome success(String excerpt) {
            return new FetchOutcome(EvidenceResult.FetchStatus.SUCCESS, excerpt, "",
                    Collections.emptyList());
        }

        public static FetchOutcome success(String excerpt, List<String> imageUrls) {
            return new FetchOutcome(EvidenceResult.FetchStatus.SUCCESS, excerpt, "", imageUrls);
        }

        public static FetchOutcome failed(String errorMessage) {
            return new FetchOutcome(EvidenceResult.FetchStatus.FAILED, "", errorMessage,
                    Collections.emptyList());
        }

        public static FetchOutcome timedOut(String errorMessage) {
            return new FetchOutcome(EvidenceResult.FetchStatus.TIMED_OUT, "", errorMessage,
                    Collections.emptyList());
        }

        public static FetchOutcome rejected(String errorMessage) {
            return new FetchOutcome(EvidenceResult.FetchStatus.REJECTED, "", errorMessage,
                    Collections.emptyList());
        }

        public EvidenceResult.FetchStatus getStatus() { return status; }
        public String getExcerpt() { return excerpt; }
        public String getErrorMessage() { return errorMessage; }
        public List<String> getImageUrls() { return imageUrls; }
    }

    class ImageOutcome {
        private final boolean success;
        private final byte[] data;
        private final String mediaType;
        private final String errorMessage;

        private ImageOutcome(boolean success, byte[] data, String mediaType, String errorMessage) {
            this.success = success;
            this.data = data;
            this.mediaType = text(mediaType);
            this.errorMessage = text(errorMessage);
        }

        public static ImageOutcome success(byte[] data, String mediaType) {
            return new ImageOutcome(true, data, mediaType, "");
        }

        public static ImageOutcome failed(String errorMessage) {
            return new ImageOutcome(false, null, "", errorMessage);
        }

        public boolean isSuccess() { return success; }
        public byte[] getData() { return data; }
        public String getMediaType() { return mediaType; }
        public String getErrorMessage() { return errorMessage; }
    }
}

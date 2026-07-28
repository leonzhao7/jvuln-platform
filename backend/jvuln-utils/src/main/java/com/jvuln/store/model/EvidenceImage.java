package com.jvuln.store.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import static com.jvuln.util.ValueUtils.text;

public class EvidenceImage {

    private final String sourceUrl;
    private final String mediaType;
    private final String relativePath;

    @JsonCreator
    public EvidenceImage(
            @JsonProperty("sourceUrl") String sourceUrl,
            @JsonProperty("mediaType") String mediaType,
            @JsonProperty("relativePath") String relativePath) {
        this.sourceUrl = text(sourceUrl);
        this.mediaType = text(mediaType);
        this.relativePath = text(relativePath);
    }

    public String getSourceUrl() { return sourceUrl; }
    public String getMediaType() { return mediaType; }
    public String getRelativePath() { return relativePath; }
}

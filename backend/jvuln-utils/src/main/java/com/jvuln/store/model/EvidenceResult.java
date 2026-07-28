package com.jvuln.store.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.jvuln.util.ValueUtils.text;

public class EvidenceResult {

    public enum Kind { SOURCE_DESCRIPTION, SOURCE_FACTS, ADVISORY, PATCH, ANALYSIS }
    public enum FetchStatus { INLINE, SUCCESS, FAILED, TIMED_OUT, REJECTED }

    private final String evidenceId;
    private final Kind kind;
    private final String source;
    private final String title;
    private final String url;
    private final FetchStatus fetchStatus;
    private final String excerpt;
    private final String errorMessage;
    private final List<EvidenceImage> images;

    public EvidenceResult(String evidenceId, Kind kind, String source, String title,
                          String url, FetchStatus fetchStatus, String excerpt,
                          String errorMessage) {
        this(evidenceId, kind, source, title, url, fetchStatus, excerpt, errorMessage, null);
    }

    @JsonCreator
    public EvidenceResult(
            @JsonProperty("evidenceId") String evidenceId,
            @JsonProperty("kind") Kind kind,
            @JsonProperty("source") String source,
            @JsonProperty("title") String title,
            @JsonProperty("url") String url,
            @JsonProperty("fetchStatus") FetchStatus fetchStatus,
            @JsonProperty("excerpt") String excerpt,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("images") List<EvidenceImage> images) {
        this.evidenceId = text(evidenceId);
        this.kind = kind;
        this.source = text(source);
        this.title = text(title);
        this.url = text(url);
        this.fetchStatus = fetchStatus;
        this.excerpt = text(excerpt);
        this.errorMessage = text(errorMessage);
        this.images = images == null || images.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(images));
    }

    public String getEvidenceId() { return evidenceId; }
    public Kind getKind() { return kind; }
    public String getSource() { return source; }
    public String getTitle() { return title; }
    public String getUrl() { return url; }
    public FetchStatus getFetchStatus() { return fetchStatus; }
    public String getExcerpt() { return excerpt; }
    public String getErrorMessage() { return errorMessage; }
    public List<EvidenceImage> getImages() { return images; }

    @JsonIgnore
    public boolean isAvailable() {
        return (fetchStatus == FetchStatus.INLINE || fetchStatus == FetchStatus.SUCCESS)
                && !excerpt.trim().isEmpty();
    }

}

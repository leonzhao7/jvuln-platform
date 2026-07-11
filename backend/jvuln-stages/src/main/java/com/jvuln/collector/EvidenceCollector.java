package com.jvuln.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.store.model.CveIntelligence;
import com.jvuln.store.model.EvidenceResult;
import com.jvuln.store.model.SourceResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.jvuln.util.ValueUtils.limit;

@Component
public class EvidenceCollector {

    private static final int DEFAULT_TOTAL_BUDGET = 24_000;
    private static final int MAX_ADVISORIES = 5;
    private static final int MAX_PATCHES = 5;
    private static final int MAX_ANALYSES = 3;
    private static final int MAX_SOURCE_DESCRIPTION = 3000;
    private static final int MAX_SOURCE_FACTS = 4000;
    private static final Set<String> TRUSTED_ADVISORY_HOSTS = new HashSet<>(Arrays.asList(
            "nvd.nist.gov", "github.com", "security.snyk.io", "access.redhat.com",
            "www.debian.org", "ubuntu.com", "osv.dev"));
    private static final Set<String> TRUSTED_ANALYSIS_HOSTS = new HashSet<>(Arrays.asList(
            "unit42.paloaltonetworks.com", "projectzero.google", "jfrog.com",
            "www.rapid7.com", "blog.sonatype.com", "securitylab.github.com"));
    private final EvidencePageFetcher fetcher;
    private final ObjectMapper mapper;
    private final int totalBudget;

    @Autowired
    public EvidenceCollector(EvidencePageFetcher fetcher, ObjectMapper mapper) {
        this(fetcher, mapper, DEFAULT_TOTAL_BUDGET);
    }

    EvidenceCollector(EvidencePageFetcher fetcher, ObjectMapper mapper, int totalBudget) {
        this.fetcher = fetcher;
        this.mapper = mapper;
        this.totalBudget = totalBudget;
    }

    public List<EvidenceResult> collect(List<SourceResult> sources,
                                        List<CveIntelligence.Article> articles) {
        List<EvidenceResult> evidence = new ArrayList<>();
        Budget budget = new Budget(totalBudget);
        addInlineSources(evidence, budget, sources);
        addLinked(evidence, budget, selectTrustedAdvisories(articles),
                EvidenceResult.Kind.ADVISORY);
        addLinked(evidence, budget, select(articles, "patch", MAX_PATCHES),
                EvidenceResult.Kind.PATCH);
        addLinked(evidence, budget, selectTrustedAnalyses(articles),
                EvidenceResult.Kind.ANALYSIS);
        return Collections.unmodifiableList(evidence);
    }

    private void addInlineSources(List<EvidenceResult> evidence, Budget budget,
                                  List<SourceResult> sources) {
        if (sources == null) {
            return;
        }
        for (SourceResult source : sources) {
            if (!source.isSuccess()) {
                continue;
            }
            String sourceName = source.getSource().name();
            if (!source.getOriginalDescription().trim().isEmpty()) {
                evidence.add(new EvidenceResult(
                        "E-SRC-" + sourceName + "-DESCRIPTION",
                        EvidenceResult.Kind.SOURCE_DESCRIPTION, sourceName,
                        sourceName + " original description", "",
                        EvidenceResult.FetchStatus.INLINE,
                        budget.take(limit(source.getOriginalDescription(),
                                MAX_SOURCE_DESCRIPTION)), ""));
            }
            evidence.add(new EvidenceResult(
                    "E-SRC-" + sourceName + "-FACTS",
                    EvidenceResult.Kind.SOURCE_FACTS, sourceName,
                    sourceName + " normalized facts", "",
                    EvidenceResult.FetchStatus.INLINE,
                    budget.take(limit(serializeFacts(source), MAX_SOURCE_FACTS)), ""));
        }
    }

    private void addLinked(List<EvidenceResult> evidence, Budget budget,
                           List<CveIntelligence.Article> articles,
                           EvidenceResult.Kind kind) {
        for (CveIntelligence.Article article : articles) {
            EvidencePageFetcher.FetchOutcome outcome = fetcher.fetch(article.getUrl());
            String excerpt = outcome.getStatus() == EvidenceResult.FetchStatus.SUCCESS
                    ? budget.take(outcome.getExcerpt()) : "";
            EvidenceResult.FetchStatus status = outcome.getStatus();
            String error = outcome.getErrorMessage();
            if (status == EvidenceResult.FetchStatus.SUCCESS && excerpt.isEmpty()) {
                status = EvidenceResult.FetchStatus.REJECTED;
                error = "Aggregate evidence context budget exhausted";
            }
            evidence.add(new EvidenceResult(stableId(kind, article.getUrl()), kind,
                    String.join(",", article.getDiscoveredFrom()), article.getTitle(),
                    article.getUrl(), status, excerpt, error));
        }
    }

    private List<CveIntelligence.Article> select(
            List<CveIntelligence.Article> articles, String category, int limit) {
        List<CveIntelligence.Article> selected = new ArrayList<>();
        if (articles == null) {
            return selected;
        }
        for (CveIntelligence.Article article : articles) {
            if (category.equals(article.getCategory())) {
                selected.add(article);
                if (selected.size() == limit) break;
            }
        }
        return selected;
    }

    private List<CveIntelligence.Article> selectTrustedAdvisories(
            List<CveIntelligence.Article> articles) {
        List<CveIntelligence.Article> selected = new ArrayList<>();
        if (articles == null) return selected;
        for (CveIntelligence.Article article : articles) {
            if ("advisory".equals(article.getCategory()) && isTrustedAdvisory(article.getUrl())) {
                selected.add(article);
                if (selected.size() == MAX_ADVISORIES) break;
            }
        }
        return selected;
    }

    private boolean isTrustedAdvisory(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            return host.startsWith("security.") || TRUSTED_ADVISORY_HOSTS.contains(host)
                    || path.contains("/security/advisory") || path.contains("/advisories/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private List<CveIntelligence.Article> selectTrustedAnalyses(
            List<CveIntelligence.Article> articles) {
        List<CveIntelligence.Article> selected = new ArrayList<>();
        if (articles == null) {
            return selected;
        }
        for (CveIntelligence.Article article : articles) {
            if ("analysis".equals(article.getCategory()) && isTrustedAnalysis(article.getUrl())) {
                selected.add(article);
                if (selected.size() == MAX_ANALYSES) break;
            }
        }
        return selected;
    }

    private boolean isTrustedAnalysis(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return TRUSTED_ANALYSIS_HOSTS.contains(host)
                    || ("github.com".equals(host) && uri.getPath().contains("/issues/"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String serializeFacts(SourceResult source) {
        try {
            return mapper.writeValueAsString(source.getParsedData());
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize source facts", e);
        }
    }

    private String stableId(EvidenceResult.Kind kind, String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((kind.name() + "\n" + url)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format(Locale.ROOT, "%02X", hash[i] & 0xff));
            }
            return "E-" + kind.name() + "-" + hex;
        } catch (Exception e) {
            throw new IllegalStateException("Could not create evidence ID", e);
        }
    }

    private static class Budget {
        private int remaining;

        private Budget(int remaining) {
            this.remaining = Math.max(0, remaining);
        }

        private String take(String value) {
            if (value == null || value.isEmpty() || remaining == 0) {
                return "";
            }
            String excerpt = value.length() <= remaining
                    ? value : value.substring(0, remaining);
            remaining -= excerpt.length();
            return excerpt;
        }
    }
}

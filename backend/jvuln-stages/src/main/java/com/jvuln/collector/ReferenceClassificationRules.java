package com.jvuln.collector;

import com.jvuln.store.model.CveIntelligence;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

class ReferenceClassificationRules {

    private static final Set<String> ADVISORY_HOSTS = new HashSet<>(Arrays.asList(
            "nvd.nist.gov", "cve.mitre.org", "access.redhat.com", "security.snyk.io",
            "advisories.mageia.org", "www.debian.org", "ubuntu.com"));
    private static final Set<String> ANALYSIS_HOSTS = new HashSet<>(Arrays.asList(
            "unit42.paloaltonetworks.com", "projectzero.google", "jfrog.com",
            "www.rapid7.com", "blog.sonatype.com", "securitylab.github.com"));
    private static final Set<String> POC_HOSTS = new HashSet<>(Arrays.asList(
            "exploit-db.com", "www.exploit-db.com", "packetstormsecurity.com",
            "www.packetstormsecurity.com"));

    Decision classify(CveIntelligence.Article article) {
        URI uri;
        try {
            uri = URI.create(article.getUrl());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = lower(uri.getHost());
        String path = lower(uri.getPath());
        String searchable = lower(article.getTitle() + " " + article.getSummary() + " " + path);

        if (isPatch(path)) {
            return decision("patch", "path.code-change", "1.00");
        }
        if (isAdvisory(host, path)) {
            return decision("advisory", "host.official-advisory", "0.99");
        }
        if (isPoc(host, path)) {
            return decision("poc", "host-or-repository.exploit", "0.98");
        }
        if (isAnalysis(host, path, searchable)) {
            return decision("analysis", "publisher.technical-analysis", "0.94");
        }
        if (isOther(host, path)) {
            return decision("other", "path.documentation", "0.92");
        }
        return null;
    }

    private boolean isPatch(String path) {
        return path.contains("/commit/") || path.contains("/commits/")
                || path.contains("/pull/") || path.contains("/pulls/")
                || path.contains("/merge_requests/") || path.contains("/compare/")
                || path.endsWith(".patch") || path.endsWith(".diff");
    }

    private boolean isAdvisory(String host, String path) {
        return ADVISORY_HOSTS.contains(host) || host.startsWith("security.")
                || path.contains("/advisories/") || path.contains("/security/advisory")
                || path.contains("/security/bulletin") || path.contains("/vuln/detail/")
                || path.matches(".*/ghsa-[a-z0-9-]+.*");
    }

    private boolean isPoc(String host, String path) {
        if (POC_HOSTS.contains(host)) {
            return true;
        }
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (segment.equals("poc") || segment.endsWith("-poc")
                    || segment.equals("exploit") || segment.endsWith("-exploit")
                    || segment.equals("exploits")) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnalysis(String host, String path, String searchable) {
        if (ANALYSIS_HOSTS.contains(host) || path.contains("/blog/")
                || path.contains("/research/") || path.contains("/analysis/")) {
            return true;
        }
        return path.contains("/issues/") && containsSecurityTerm(searchable);
    }

    private boolean isOther(String host, String path) {
        return host.startsWith("docs.") || path.contains("/docs/")
                || path.contains("/documentation/") || path.contains("/wiki/")
                || path.endsWith("/readme") || path.endsWith("/readme.md");
    }

    private boolean containsSecurityTerm(String value) {
        return value.contains("cve-") || value.contains("vulnerab")
                || value.contains("security") || value.contains("exploit")
                || value.contains("patch");
    }

    private Decision decision(String category, String reason, String confidence) {
        return new Decision(category, reason, new BigDecimal(confidence));
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static class Decision {
        private final String category;
        private final String reason;
        private final BigDecimal confidence;

        private Decision(String category, String reason, BigDecimal confidence) {
            this.category = category;
            this.reason = reason;
            this.confidence = confidence;
        }

        String getCategory() { return category; }
        String getReason() { return reason; }
        BigDecimal getConfidence() { return confidence; }
    }
}

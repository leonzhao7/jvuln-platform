package com.jvuln.generator;

import java.util.Collections;
import java.util.List;
import java.util.Set;

final class TraceTarget {
    final String groupId;
    final String artifactId;
    final String version;
    final Set<String> packages;
    final List<String> methodsOfInterest;

    TraceTarget(String groupId, String artifactId, String version,
                Set<String> packages, List<String> methodsOfInterest) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.packages = packages == null ? Collections.<String>emptySet() : packages;
        this.methodsOfInterest = methodsOfInterest == null ? Collections.<String>emptyList() : methodsOfInterest;
    }

    boolean isValid() {
        return groupId != null && !groupId.isEmpty()
                && artifactId != null && !artifactId.isEmpty()
                && version != null && !version.isEmpty()
                && !packages.isEmpty();
    }
}

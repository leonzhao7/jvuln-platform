package com.jvuln.tracer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class TracerConfig {
    final Set<String> includes;
    final String outputPath;

    private TracerConfig(Set<String> includes, String outputPath) {
        this.includes = Collections.unmodifiableSet(includes);
        this.outputPath = outputPath;
    }

    static TracerConfig parse(String agentArgs) {
        Set<String> includes = new LinkedHashSet<>();
        String outputPath = null;
        if (agentArgs == null || agentArgs.trim().isEmpty()) {
            return new TracerConfig(includes, null);
        }
        for (String token : agentArgs.split(",")) {
            token = token.trim();
            if (token.startsWith("includes=")) {
                for (String pkg : token.substring("includes=".length()).split(";")) {
                    String p = pkg.trim();
                    if (!p.isEmpty()) includes.add(p);
                }
            } else if (token.startsWith("out=")) {
                outputPath = token.substring("out=".length()).trim();
            }
        }
        return new TracerConfig(includes, outputPath);
    }
}

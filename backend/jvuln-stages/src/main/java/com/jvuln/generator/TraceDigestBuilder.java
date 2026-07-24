package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TraceDigestBuilder {

    private static final Logger log = LoggerFactory.getLogger(TraceDigestBuilder.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    static Map<String, Object> buildDigest(Path traceFile, TraceTarget target) {
        Map<String, Object> digest = new LinkedHashMap<>();

        if (!Files.exists(traceFile) || !Files.isReadable(traceFile)) {
            digest.put("traceCaptured", false);
            digest.put("reason", "Trace file not found or not readable: " + traceFile.getFileName());
            return digest;
        }

        try {
            List<String> lines = Files.readAllLines(traceFile);
            if (lines.isEmpty()) {
                digest.put("traceCaptured", false);
                digest.put("reason", "Trace file empty — no calls captured in target packages");
                return digest;
            }

            int totalCalls = 0;
            int maxDepth = 0;
            Map<String, Map<String, Object>> reachedMethods = new LinkedHashMap<>();
            String lastCall = null;

            for (String line : lines) {
                try {
                    JsonNode event = mapper.readTree(line);
                    totalCalls++;
                    int depth = event.path("depth").asInt(0);
                    if (depth > maxDepth) maxDepth = depth;

                    String cls = event.path("class").asText("");
                    String method = event.path("method").asText("");
                    String fullMethod = cls + "." + method;
                    lastCall = fullMethod;

                    if (target.methodsOfInterest.contains(fullMethod)) {
                        if (!reachedMethods.containsKey(fullMethod)) {
                            Map<String, Object> methodData = new LinkedHashMap<>();
                            methodData.put("method", fullMethod);

                            JsonNode args = event.path("args");
                            if (args.isArray()) {
                                List<String> argList = new ArrayList<>();
                                for (JsonNode a : args) {
                                    String argStr = a.asText("");
                                    argList.add(ArtifactGenUtils.truncate(argStr, GeneratorConstants.TRACE_ARG_CAP));
                                }
                                methodData.put("argsAtEntry", argList);
                            }

                            String retVal = event.path("ret").asText(null);
                            String throwVal = event.path("throw").asText(null);
                            if (throwVal != null) {
                                methodData.put("outcome", "threw " + ArtifactGenUtils.truncate(throwVal, 200));
                            } else if (retVal != null) {
                                methodData.put("outcome", "returned " + ArtifactGenUtils.truncate(retVal, 200));
                            }

                            reachedMethods.put(fullMethod, methodData);
                        }
                    }
                } catch (Exception ignored) {}
            }

            digest.put("traceCaptured", true);
            digest.put("totalCalls", totalCalls);
            digest.put("maxDepth", maxDepth);

            Map<String, Object> methodsOfInterestDigest = new LinkedHashMap<>();
            List<Map<String, Object>> reached = new ArrayList<>(reachedMethods.values());
            methodsOfInterestDigest.put("reached", reached);

            List<String> notReached = new ArrayList<>();
            for (String m : target.methodsOfInterest) {
                if (!reachedMethods.containsKey(m)) {
                    notReached.add(m);
                }
            }
            methodsOfInterestDigest.put("notReached", notReached);
            digest.put("methodsOfInterest", methodsOfInterestDigest);

            if (lastCall != null) {
                digest.put("lastCallBeforeEnd", lastCall);
            }

            String note = buildNote(reached.size(), target.methodsOfInterest.size(), maxDepth);
            digest.put("note", note);

        } catch (Exception e) {
            log.warn("Failed to build trace digest: {}", e.getMessage());
            digest.put("traceCaptured", false);
            digest.put("reason", "Trace digest build failed: " + e.getMessage());
        }

        return digest;
    }

    private static String buildNote(int reachedCount, int totalOfInterest, int maxDepth) {
        if (reachedCount == 0) {
            return "None of the " + totalOfInterest + " methods of interest were reached. Max call depth=" + maxDepth + ".";
        }
        if (reachedCount == totalOfInterest) {
            return "All " + totalOfInterest + " methods of interest were reached.";
        }
        return reachedCount + " of " + totalOfInterest + " methods of interest were reached.";
    }
}

package com.jvuln.store.model;

import java.util.List;

public class CodeAnalysisResult {

    private final String fileName;
    private final String changeType;
    private final String relevanceReason;
    private final String relevanceLayer;
    private final List<MethodAnalysis> methods;
    private final List<CweMatch> cweMatches;
    private final List<String> callChain;

    public CodeAnalysisResult(String fileName, String changeType, String relevanceReason, String relevanceLayer,
                              List<MethodAnalysis> methods, List<CweMatch> cweMatches,
                              List<String> callChain) {
        this.fileName = fileName;
        this.changeType = changeType;
        this.relevanceReason = relevanceReason;
        this.relevanceLayer = relevanceLayer;
        this.methods = methods;
        this.cweMatches = cweMatches;
        this.callChain = callChain;
    }

    public String getFileName() { return fileName; }
    public String getChangeType() { return changeType; }
    public String getRelevanceReason() { return relevanceReason; }
    public String getRelevanceLayer() { return relevanceLayer; }
    public List<MethodAnalysis> getMethods() { return methods; }
    public List<CweMatch> getCweMatches() { return cweMatches; }
    public List<String> getCallChain() { return callChain; }

    public static class MethodAnalysis {
        private final String methodName;
        private final String signature;
        private final String vulnerableCode;
        private final String fixedCode;
        private final List<String> calledMethods;

        public MethodAnalysis(String methodName, String signature,
                               String vulnerableCode, String fixedCode, List<String> calledMethods) {
            this.methodName = methodName;
            this.signature = signature;
            this.vulnerableCode = vulnerableCode;
            this.fixedCode = fixedCode;
            this.calledMethods = calledMethods;
        }

        public String getMethodName() { return methodName; }
        public String getSignature() { return signature; }
        public String getVulnerableCode() { return vulnerableCode; }
        public String getFixedCode() { return fixedCode; }
        public List<String> getCalledMethods() { return calledMethods; }
    }

    public static class CweMatch {
        private final String cweId;
        private final String cweName;
        private final String matchedPattern;
        private final String matchedCode;
        private final String explanation;

        public CweMatch(String cweId, String cweName, String matchedPattern,
                         String matchedCode, String explanation) {
            this.cweId = cweId;
            this.cweName = cweName;
            this.matchedPattern = matchedPattern;
            this.matchedCode = matchedCode;
            this.explanation = explanation;
        }

        public String getCweId() { return cweId; }
        public String getCweName() { return cweName; }
        public String getMatchedPattern() { return matchedPattern; }
        public String getMatchedCode() { return matchedCode; }
        public String getExplanation() { return explanation; }
    }
}

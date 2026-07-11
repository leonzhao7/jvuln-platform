package com.jvuln.patcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.jvuln.patcher.analyzer.*;
import com.jvuln.patcher.strategy.AiPatchSearchStrategy;
import com.jvuln.patcher.strategy.AiPatchSearchStrategy.AiEnrichment;
import com.jvuln.patcher.strategy.AiPatchSearchStrategy.AiPatchOutcome;
import com.jvuln.patcher.strategy.LocateStrategy;
import com.jvuln.patcher.strategy.MavenSourceDiffStrategy;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
import com.jvuln.store.model.CodeAnalysisResult;
import com.jvuln.store.model.CveIntelligence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 2: Patch Locating + Code Analysis
 * Merges patch location and code analysis into a single patch-analysis stage to reduce overhead
 * and improve pipeline efficiency.
 */
@Component
public class PatchAnalysisStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(PatchAnalysisStage.class);

    // Patch locating components
    private final List<LocateStrategy> strategies;
    private final MavenSourceDiffStrategy mavenStrategy;
    private final AiPatchSearchStrategy aiStrategy;

    // Code analysis components
    private final DiffRelevanceFilter relevanceFilter;
    private final AnalysisRelevanceFilter analysisRelevanceFilter;
    private final AnalysisLayerClassifier analysisLayerClassifier;
    private final VulnerabilityFactResolver factResolver;

    private final ObjectMapper mapper = new ObjectMapper();

    // Regex patterns for code analysis
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@[^@]+@@\\s*(.+)?$", Pattern.MULTILINE);
    private static final Pattern METHOD_SIG = Pattern.compile(
            "(?:public|protected|private|static|\\s)+(?:[\\w<>\\[\\]]+\\s+)+(\\w+)\\s*\\(");

    public PatchAnalysisStage(List<LocateStrategy> strategies,
                              MavenSourceDiffStrategy mavenStrategy,
                              AiPatchSearchStrategy aiStrategy,
                              DiffRelevanceFilter relevanceFilter,
                              AnalysisRelevanceFilter analysisRelevanceFilter,
                              AnalysisLayerClassifier analysisLayerClassifier,
                              VulnerabilityFactResolver factResolver) {
        this.aiStrategy = aiStrategy;
        this.mavenStrategy = mavenStrategy;
        this.strategies = new ArrayList<>(strategies);
        this.strategies.sort(Comparator.comparingInt(LocateStrategy::priority));
        this.relevanceFilter = relevanceFilter;
        this.analysisRelevanceFilter = analysisRelevanceFilter;
        this.analysisLayerClassifier = analysisLayerClassifier;
        this.factResolver = factResolver;
    }

    @Override
    public int number() {
        return 2;
    }

    @Override
    public String name() {
        return "Patch Analysis";
    }

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        String cveId = ctx.getCveId();
        ctx.reportProgress("Starting patch locating and analysis for " + cveId);

        // ========== PART 1: PATCH LOCATING ==========
        StageResult stage1 = ctx.getCompletedStages().get(1);
        if (stage1 == null || stage1.getData() == null) {
            return StageResult.failure(2, name(), "Stage 1 data not available");
        }

        // Extract Stage 1 data
        Object stage1Data = stage1.getData();
        String sourceRepo = extractString(stage1Data, "sourceRepo");
        String groupId = extractNestedString(stage1Data, "artifact", "groupId");
        String artifactId = extractNestedString(stage1Data, "artifact", "artifactId");
        String fixedVersion = extractString(stage1Data, "fixedVersion");
        String affectedTo = extractNestedString(stage1Data, "affectedVersions", "to");
        String description = extractString(stage1Data, "description");
        List<String> knownCommits = extractCommits(stage1Data);
        List<String> articleUrls = extractArticleUrls(stage1Data);

        String effectiveSourceRepo = sourceRepo;
        String effectiveGroupId = groupId;
        String effectiveArtifactId = artifactId;
        String effectiveAffectedTo = affectedTo;
        String effectiveFixed = (fixedVersion != null && !fixedVersion.isEmpty()) ? fixedVersion : null;

        log.info("Patch Analysis Stage: sourceRepo={} artifact={}:{} fixed={} commits={}",
                sourceRepo, groupId, artifactId, fixedVersion, knownCommits.size());

        // Try to locate patch
        LocateStrategy.PatchResult patchResult = locatePatch(ctx, cveId, effectiveSourceRepo,
                effectiveGroupId, effectiveArtifactId, effectiveAffectedTo, effectiveFixed,
                description, knownCommits, articleUrls);

        if (patchResult == null) {
            return StageResult.failure(2, name(),
                    "No fix commit found with any strategy");
        }

        String rawDiff = patchResult.getRawDiff();
        String strategyName = patchResult.getStrategyName();
        ctx.reportProgress("Found patch via " + strategyName + ", analyzing code changes");

        // ========== PART 2: CODE ANALYSIS ==========
        CodeAnalysisOutput analysisOutput = analyzeCode(ctx, cveId, rawDiff,
                stage1Data, patchResult);

        // ========== BUILD COMBINED RESULT ==========
        Map<String, Object> combinedResult = buildCombinedResult(
                patchResult, analysisOutput, strategyName);

        // Save results
        ctx.getWorkspaceManager().writeStageData(cveId, 2, combinedResult);
        ctx.getWorkspaceManager().writeDiff(cveId, rawDiff);

        ctx.reportProgress("Patch analysis complete: " + analysisOutput.filteredFileCount
                + " relevant file(s) analyzed");

        return StageResult.success(2, name(), combinedResult);
    }

    // ========== PATCH LOCATING METHODS ==========

    private LocateStrategy.PatchResult locatePatch(PipelineContext ctx, String cveId,
                                                    String sourceRepo, String groupId, String artifactId,
                                                    String affectedTo, String fixedVersion,
                                                    String description, List<String> knownCommits,
                                                    List<String> articleUrls) throws Exception {
        // Phase 1: try commit-based strategies
        for (LocateStrategy strategy : strategies) {
            if (strategy instanceof MavenSourceDiffStrategy) continue;
            ctx.reportProgress("Trying strategy: " + strategy.name());
            try {
                Optional<LocateStrategy.PatchResult> result =
                        strategy.locate(cveId, sourceRepo, knownCommits);
                if (result.isPresent()) {
                    LocateStrategy.PatchResult pr = result.get();
                    pr.setStrategyName(strategy.name());
                    return pr;
                }
            } catch (Exception e) {
                log.warn("Strategy {} failed: {}", strategy.name(), e.getMessage());
            }
        }

        // Phase 2: Maven source JAR diff
        String effectiveFixed = fixedVersion;
        if (effectiveFixed == null && artifactId != null && !artifactId.isEmpty()
                && affectedTo != null && !affectedTo.isEmpty()) {
            effectiveFixed = mavenStrategy.inferFixedVersion(groupId, artifactId, affectedTo);
            if (effectiveFixed != null) {
                log.info("Patch Analysis Stage: inferred fixedVersion={} from affectedTo='{}'",
                        effectiveFixed, affectedTo);
            }
        }
        if (artifactId != null && !artifactId.isEmpty() && effectiveFixed != null) {
            ctx.reportProgress("Trying strategy: maven-source-diff");
            try {
                Optional<LocateStrategy.PatchResult> result =
                        mavenStrategy.locateByArtifact(cveId, groupId, artifactId, effectiveFixed);
                if (result.isPresent()) {
                    LocateStrategy.PatchResult pr = result.get();
                    pr.setStrategyName("maven-source-diff");
                    return pr;
                }
            } catch (Exception e) {
                log.warn("MavenSourceDiff failed: {}", e.getMessage());
            }
        }

        // Phase 3: AI-guided search
        ctx.reportProgress("Trying strategy: ai-patch-search");
        try {
            Optional<AiPatchOutcome> result = aiStrategy.locateWithAiHints(
                    cveId, sourceRepo, groupId, artifactId,
                    description, affectedTo, effectiveFixed, articleUrls);
            if (result.isPresent()) {
                AiPatchOutcome outcome = result.get();
                if (outcome.getPatchResult() != null) {
                    LocateStrategy.PatchResult pr = outcome.getPatchResult();
                    pr.setStrategyName("ai-patch-search");
                    return pr;
                }
            }
        } catch (Exception e) {
            log.warn("AiPatchSearch failed: {}", e.getMessage());
        }

        return null;
    }

    // ========== CODE ANALYSIS METHODS ==========

    private CodeAnalysisOutput analyzeCode(PipelineContext ctx, String cveId, String rawDiff,
                                           Object stage1Data, LocateStrategy.PatchResult patchResult)
            throws Exception {
        List<JavaFileChange> fileChanges = parseJavaChanges(rawDiff);

        if (fileChanges.isEmpty()) {
            log.warn("No Java file changes found in diff, returning empty analysis");
            return new CodeAnalysisOutput(Collections.emptyList(), 0, 0);
        }

        // Pre-filter to avoid obvious noise
        String cveDescription = extractString(stage1Data, "description");
        String groupId = extractNestedString(stage1Data, "artifact", "groupId");
        String artifactId = extractNestedString(stage1Data, "artifact", "artifactId");
        String affectedComponent = (groupId != null && artifactId != null)
                ? groupId + ":" + artifactId : null;

        List<JavaFileChange> candidates = relevanceFilter.preFilterOnly(fileChanges, cveDescription);
        if (candidates.isEmpty()) {
            candidates = fileChanges;
        }

        ctx.reportProgress("Analyzing " + candidates.size() + " Java file(s)");

        // Traditional analysis
        List<CodeAnalysisResult> traditionalResults = new ArrayList<>();
        for (JavaFileChange change : candidates) {
            CodeAnalysisResult result = analyzeChange(change);
            traditionalResults.add(result);
        }

        // AI filtering and classification
        List<CodeAnalysisResult> results = analysisRelevanceFilter.filter(
                cveId, cveDescription, affectedComponent, patchResult, traditionalResults);
        results = analysisLayerClassifier.classify(results);

        return new CodeAnalysisOutput(results, traditionalResults.size(), results.size());
    }

    private static class CodeAnalysisOutput {
        final List<CodeAnalysisResult> results;
        final int traditionalFileCount;
        final int filteredFileCount;

        CodeAnalysisOutput(List<CodeAnalysisResult> results, int traditional, int filtered) {
            this.results = results;
            this.traditionalFileCount = traditional;
            this.filteredFileCount = filtered;
        }
    }

    private Map<String, Object> buildCombinedResult(LocateStrategy.PatchResult patchResult,
                                                     CodeAnalysisOutput analysisOutput,
                                                     String strategyName) throws Exception {
        // Build patch info
        List<PatchInfo.FileDiff> diffs = DiffParser.parse(patchResult.getRawDiff());
        List<PatchInfo.PatchFile> files = new ArrayList<>();
        for (PatchInfo.FileDiff diff : diffs) {
            files.add(new PatchInfo.PatchFile(diff.getFilePath(), diff.getChangeType()));
        }

        PatchInfo patchInfo = new PatchInfo(
                patchResult.getCommitHash(),
                patchResult.getCommitMessage(),
                "",
                files,
                patchResult.getRawDiff(),
                strategyName
        );

        // Build patch scope from file changes
        List<Map<String, Object>> patchScope = new ArrayList<>();
        for (PatchInfo.PatchFile file : files) {
            Map<String, Object> fileMap = new LinkedHashMap<>();
            fileMap.put("filePath", file.getFilePath());
            fileMap.put("changeType", file.getChangeType());
            patchScope.add(fileMap);
        }

        // Calculate CWE match count
        int totalCwe = 0;
        for (CodeAnalysisResult r : analysisOutput.results) {
            totalCwe += r.getCweMatches().size();
        }

        // Build combined result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patchInfo", patchInfo);
        result.put("patchScope", patchScope);
        result.put("analyzedFiles", analysisOutput.results);
        result.put("layerSummary", analysisLayerClassifier.summarize(analysisOutput.results));
        result.put("traditionalAnalyzedFileCount", analysisOutput.traditionalFileCount);
        result.put("filteredAnalyzedFileCount", analysisOutput.filteredFileCount);
        result.put("totalCweMatches", totalCwe);

        return result;
    }

    private List<JavaFileChange> parseJavaChanges(String rawDiff) {
        List<JavaFileChange> changes = new ArrayList<>();
        Pattern fileHeader = Pattern.compile("^diff --git a/(.*) b/(.*)$", Pattern.MULTILINE);
        String[] sections = rawDiff.split("(?=diff --git )");

        for (String section : sections) {
            if (section.trim().isEmpty()) continue;
            Matcher m = fileHeader.matcher(section);
            if (!m.find()) continue;

            String filePath = m.group(2);
            if (!filePath.endsWith(".java")) continue;
            String changeType = detectChangeType(section);

            StringBuilder removed = new StringBuilder();
            StringBuilder added = new StringBuilder();
            for (String line : section.split("\n")) {
                if (line.startsWith("-") && !line.startsWith("---")) {
                    removed.append(line.substring(1)).append("\n");
                } else if (line.startsWith("+") && !line.startsWith("+++")) {
                    added.append(line.substring(1)).append("\n");
                }
            }

            Set<String> methodNames = extractMethodNames(section);
            changes.add(new JavaFileChange(filePath, changeType, section,
                    removed.toString(), added.toString(), methodNames));
        }
        return changes;
    }

    private String detectChangeType(String section) {
        if (section.contains("--- /dev/null")) return "added";
        if (section.contains("+++ /dev/null")) return "deleted";
        return "modified";
    }

    private Set<String> extractMethodNames(String diffSection) {
        Set<String> names = new LinkedHashSet<>();
        Matcher hunk = HUNK_HEADER.matcher(diffSection);
        while (hunk.find()) {
            String context = hunk.group(1);
            if (context == null) continue;
            Matcher sig = METHOD_SIG.matcher(context);
            if (sig.find()) {
                names.add(sig.group(1));
            }
        }
        return names;
    }

    private CodeAnalysisResult analyzeChange(JavaFileChange change) {
        List<CodeAnalysisResult.MethodAnalysis> methods = new ArrayList<>();
        List<CodeAnalysisResult.CweMatch> cweMatches = new ArrayList<>();
        Set<String> effectiveMethodNames = new LinkedHashSet<>(change.methodNames);
        String analysisCode = change.removedCode + "\n" + change.addedCode;

        if (effectiveMethodNames.isEmpty()) {
            effectiveMethodNames.addAll(extractDeclaredMethods(change));
        }

        // CWE pattern matching
        List<CwePatternMatcher.MatchResult> patternHits =
                CwePatternMatcher.match(change.removedCode);
        for (CwePatternMatcher.MatchResult hit : patternHits) {
            cweMatches.add(new CodeAnalysisResult.CweMatch(
                    hit.cweId, hit.cweName, hit.pattern, hit.matchedCode, hit.explanation));
        }

        // Per-method analysis
        for (String methodName : effectiveMethodNames) {
            List<String> calledMethods = extractCallsFromDiff(analysisCode, methodName);
            String vulnSnippet = extractMethodSnippet(change.rawSection, methodName, true);
            String fixSnippet = extractMethodSnippet(change.rawSection, methodName, false);

            methods.add(new CodeAnalysisResult.MethodAnalysis(
                    methodName, buildSignature(analysisCode, methodName),
                    vulnSnippet, fixSnippet, calledMethods));
        }

        // JavaParser enrichment
        enrichWithJavaParser(methods, change.removedCode);
        enrichWithJavaParser(methods, change.addedCode);

        // Build call chain
        List<String> callChain = buildCallChain(effectiveMethodNames,
                change.removedCode + "\n" + change.addedCode);

        return new CodeAnalysisResult(change.filePath, change.changeType,
                null, null, methods, cweMatches, callChain);
    }

    private Set<String> extractDeclaredMethods(JavaFileChange change) {
        Set<String> names = new LinkedHashSet<>();
        collectDeclaredMethods(names, change.removedCode);
        collectDeclaredMethods(names, change.addedCode);
        return names;
    }

    private void collectDeclaredMethods(Set<String> names, String code) {
        if (code == null || code.trim().isEmpty()) return;
        try {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> result = parser.parse(code);
            CompilationUnit cu = result.getResult().orElse(null);
            if (cu == null) {
                result = parser.parse("class __Temp__ {\n" + code + "\n}");
                cu = result.getResult().orElse(null);
            }
            if (cu != null) {
                for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                    names.add(md.getNameAsString());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private List<String> extractCallsFromDiff(String code, String fromMethod) {
        List<String> calls = new ArrayList<>();
        Pattern callPattern = Pattern.compile("(?<![a-zA-Z0-9_])([a-z][a-zA-Z0-9_]+)\\s*\\(");
        Matcher m = callPattern.matcher(code);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String call = m.group(1);
            if (!call.equals(fromMethod) && !isJavaKeyword(call) && seen.add(call)) {
                calls.add(call);
            }
        }
        return calls;
    }

    private String extractMethodSnippet(String diffSection, String methodName, boolean removed) {
        String prefix = removed ? "-" : "+";
        StringBuilder sb = new StringBuilder();
        boolean inRelevantHunk = false;

        for (String line : diffSection.split("\n")) {
            if (line.startsWith("@@") && line.contains(methodName)) {
                inRelevantHunk = true;
                continue;
            }
            if (line.startsWith("@@")) {
                inRelevantHunk = false;
            }
            if (inRelevantHunk && line.startsWith(prefix) && !line.startsWith(prefix + prefix + prefix)) {
                sb.append(line.substring(1)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String buildSignature(String code, String methodName) {
        Pattern sigPattern = Pattern.compile(
                "((?:public|protected|private|static|\\s)+(?:[\\w<>\\[\\]]+\\s+)+" +
                        Pattern.quote(methodName) + "\\s*\\([^)]*\\))", Pattern.DOTALL);
        Matcher m = sigPattern.matcher(code);
        if (m.find()) {
            return m.group(1).trim().replaceAll("\\s+", " ");
        }
        return methodName + "(...)";
    }

    private List<String> buildCallChain(Set<String> methodNames, String code) {
        List<String> chain = new ArrayList<>(methodNames);
        for (String caller : methodNames) {
            for (String callee : methodNames) {
                if (!caller.equals(callee) && code.contains(callee + "(")) {
                    String edge = caller + " -> " + callee;
                    if (!chain.contains(edge)) {
                        chain.add(edge);
                    }
                }
            }
        }
        return chain;
    }

    private void enrichWithJavaParser(List<CodeAnalysisResult.MethodAnalysis> methods, String code) {
        try {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> result = parser.parse(
                    "class __Temp__ {\n" + code + "\n}");
            if (!result.isSuccessful()) return;

            CompilationUnit cu = result.getResult().orElse(null);
            if (cu == null) return;

            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                String name = md.getNameAsString();
                for (int i = 0; i < methods.size(); i++) {
                    if (methods.get(i).getMethodName().equals(name)) {
                        List<String> calls = new ArrayList<>();
                        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                            calls.add(call.getNameAsString());
                        }
                        CodeAnalysisResult.MethodAnalysis old = methods.get(i);
                        methods.set(i, new CodeAnalysisResult.MethodAnalysis(
                                old.getMethodName(), md.getDeclarationAsString(),
                                old.getVulnerableCode(), old.getFixedCode(), calls));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("JavaParser enrichment skipped: {}", e.getMessage());
        }
    }

    private static boolean isJavaKeyword(String s) {
        switch (s) {
            case "if": case "else": case "for": case "while": case "do":
            case "try": case "catch": case "finally": case "return": case "new":
            case "throw": case "switch": case "case": case "break": case "continue":
            case "import": case "class": case "interface": case "extends": case "implements":
            case "super": case "this": case "void": case "null": case "true": case "false":
                return true;
            default:
                return false;
        }
    }

    // ========== STAGE 1 DATA EXTRACTION HELPERS ==========

    private String extractString(Object data, String field) {
        if (data instanceof CveIntelligence) {
            CveIntelligence intel = (CveIntelligence) data;
            if ("sourceRepo".equals(field)) return intel.getSourceRepo();
            if ("fixedVersion".equals(field)) return intel.getFixedVersion();
            if ("description".equals(field)) return intel.getDescription();
        }
        try {
            JsonNode node = mapper.valueToTree(data);
            return node.path(field).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractNestedString(Object data, String parent, String field) {
        if (data instanceof CveIntelligence) {
            CveIntelligence intel = (CveIntelligence) data;
            if ("artifact".equals(parent) && intel.getArtifact() != null) {
                if ("groupId".equals(field)) return intel.getArtifact().getGroupId();
                if ("artifactId".equals(field)) return intel.getArtifact().getArtifactId();
            }
            if ("affectedVersions".equals(parent) && intel.getAffectedVersions() != null) {
                if ("to".equals(field)) return intel.getAffectedVersions().getTo();
            }
        }
        try {
            JsonNode node = mapper.valueToTree(data);
            return node.path(parent).path(field).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> extractCommits(Object data) {
        if (data instanceof CveIntelligence) {
            List<String> c = ((CveIntelligence) data).getFixCommits();
            return c != null ? c : Collections.emptyList();
        }
        try {
            JsonNode node = mapper.valueToTree(data);
            JsonNode commits = node.path("fixCommits");
            List<String> result = new ArrayList<>();
            if (commits.isArray()) {
                for (JsonNode c : commits) result.add(c.asText());
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<String> extractArticleUrls(Object data) {
        if (data instanceof CveIntelligence) {
            List<CveIntelligence.Article> articles = ((CveIntelligence) data).getArticles();
            if (articles == null) return Collections.emptyList();
            List<String> urls = new ArrayList<>();
            for (CveIntelligence.Article a : articles) {
                if (a.getUrl() != null && !a.getUrl().isEmpty()) urls.add(a.getUrl());
            }
            return urls;
        }
        try {
            JsonNode node = mapper.valueToTree(data);
            JsonNode articles = node.path("articles");
            List<String> result = new ArrayList<>();
            if (articles.isArray()) {
                for (JsonNode a : articles) {
                    String url = a.path("url").asText("");
                    if (!url.isEmpty()) result.add(url);
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}

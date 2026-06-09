package com.jvuln.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
import com.jvuln.store.model.CodeAnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CodeAnalysisStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalysisStage.class);

    private final DiffRelevanceFilter relevanceFilter;
    private final AnalysisRelevanceFilter analysisRelevanceFilter;
    private final AnalysisLayerClassifier analysisLayerClassifier;
    private final PatchEvidenceSynthesizer patchEvidenceSynthesizer;
    private final VulnerabilityFactResolver factResolver;
    private final ObjectMapper mapper;

    public CodeAnalysisStage(DiffRelevanceFilter relevanceFilter, AnalysisRelevanceFilter analysisRelevanceFilter,
                             AnalysisLayerClassifier analysisLayerClassifier,
                             PatchEvidenceSynthesizer patchEvidenceSynthesizer,
                             VulnerabilityFactResolver factResolver, ObjectMapper mapper) {
        this.relevanceFilter = relevanceFilter;
        this.analysisRelevanceFilter = analysisRelevanceFilter;
        this.analysisLayerClassifier = analysisLayerClassifier;
        this.patchEvidenceSynthesizer = patchEvidenceSynthesizer;
        this.factResolver = factResolver;
        this.mapper = mapper;
    }

    // Matches: @@ -602,7 +602,7 @@ protected void doPut(...)
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@[^@]+@@\\s*(.+)?$", Pattern.MULTILINE);
    // Extracts method name from context like "protected void doPut(" or "protected File executePartialPut("
    private static final Pattern METHOD_SIG = Pattern.compile(
            "(?:public|protected|private|static|\\s)+(?:[\\w<>\\[\\]]+\\s+)+(\\w+)\\s*\\(");

    @Override
    public int number() { return 3; }

    @Override
    public String name() { return "Code Analysis"; }

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        String cveId = ctx.getCveId();
        ctx.reportProgress("Reading patch diff for analysis");

        Path diffFile = ctx.getWorkspacePath().resolve("patches/fix.diff");
        if (!Files.exists(diffFile)) {
            return StageResult.failure(3, name(), "No diff file found at " + diffFile);
        }

        String rawDiff = new String(Files.readAllBytes(diffFile), StandardCharsets.UTF_8);
        List<JavaFileChange> fileChanges = parseJavaChanges(rawDiff);

        if (fileChanges.isEmpty()) {
            return StageResult.failure(3, name(), "No Java file changes found in diff");
        }

        // First, do a conservative traditional pre-filter to avoid obvious release noise.
        String cveDescription = extractStage1String(ctx, "description");
        String groupId = extractStage1Nested(ctx, "artifact", "groupId");
        String artifactId = extractStage1Nested(ctx, "artifact", "artifactId");
        String affectedComponent = (groupId != null && artifactId != null)
                ? groupId + ":" + artifactId : null;

        List<JavaFileChange> candidates = relevanceFilter.preFilterOnly(fileChanges, cveDescription);
        if (candidates.isEmpty()) {
            candidates = fileChanges;
        }

        ctx.reportProgress("Analyzing " + candidates.size() + " Java file(s)"
                + (candidates.size() < fileChanges.size()
                   ? " (filtered from " + fileChanges.size() + ")" : ""));

        List<CodeAnalysisResult> traditionalResults = new ArrayList<>();
        for (JavaFileChange change : candidates) {
            ctx.reportProgress("Analyzing " + shortName(change.filePath));
            CodeAnalysisResult result = analyzeChange(change);
            traditionalResults.add(result);
        }

        Object stage1Data = ctx.getCompletedStages().get(1) != null
                ? ctx.getCompletedStages().get(1).getData() : null;
        Object stage2Data = ctx.getCompletedStages().get(2) != null
                ? ctx.getCompletedStages().get(2).getData() : null;

        List<CodeAnalysisResult> results = analysisRelevanceFilter.filter(
                cveId, cveDescription, affectedComponent, stage2Data, traditionalResults);
        results = analysisLayerClassifier.classify(results);
        List<java.util.Map<String, Object>> patchScope = extractPatchScope(stage2Data, fileChanges);
        java.util.Map<String, Object> patchEvidence = patchEvidenceSynthesizer.build(fileChanges, results);

        java.util.Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("patchScope", patchScope);
        output.put("patchEvidence", patchEvidence);
        output.put("analyzedFiles", results);
        output.put("layerSummary", analysisLayerClassifier.summarize(results));
        output.put("traditionalAnalyzedFileCount", traditionalResults.size());
        output.put("filteredAnalyzedFileCount", results.size());
        int totalCwe = 0;
        for (CodeAnalysisResult r : results) totalCwe += r.getCweMatches().size();
        output.put("totalCweMatches", totalCwe);
        output.put("vulnerabilityFacts",
                factResolver.resolve(cveId, stage1Data, stage2Data, patchEvidence, results));

        ctx.getWorkspaceManager().writeStageData(cveId, 3, output);
        ctx.reportProgress("Code analysis complete: " + results.size() + " relevant file(s) retained");
        return StageResult.success(3, name(), output);
    }

    // ── Stage 1 data helpers ──────────────────────────────────────────────────

    private String extractStage1String(PipelineContext ctx, String field) {
        try {
            StageResult s1 = ctx.getCompletedStages().get(1);
            if (s1 == null || s1.getData() == null) return null;
            JsonNode node = mapper.valueToTree(s1.getData());
            String val = node.path(field).asText(null);
            return (val != null && !val.isEmpty()) ? val : null;
        } catch (Exception e) { return null; }
    }

    private String extractStage1Nested(PipelineContext ctx, String parent, String field) {
        try {
            StageResult s1 = ctx.getCompletedStages().get(1);
            if (s1 == null || s1.getData() == null) return null;
            JsonNode node = mapper.valueToTree(s1.getData());
            String val = node.path(parent).path(field).asText(null);
            return (val != null && !val.isEmpty()) ? val : null;
        } catch (Exception e) { return null; }
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

    private List<java.util.Map<String, Object>> extractPatchScope(Object stage2Data, List<JavaFileChange> fileChanges) {
        List<java.util.Map<String, Object>> files = new ArrayList<>();
        try {
            JsonNode root = mapper.valueToTree(stage2Data);
            JsonNode nodes = root.path("files");
            if (!nodes.isArray() || nodes.size() == 0) {
                nodes = root.path("diffs");
            }
            if (nodes.isArray()) {
                for (JsonNode node : nodes) {
                    String filePath = node.path("filePath").asText("");
                    if (filePath.isEmpty()) {
                        continue;
                    }
                    java.util.Map<String, Object> file = new java.util.LinkedHashMap<>();
                    file.put("filePath", filePath);
                    file.put("changeType", node.path("changeType").asText("modified"));
                    files.add(file);
                }
            }
        } catch (Exception ignored) {
        }

        if (!files.isEmpty()) {
            return files;
        }

        for (JavaFileChange change : fileChanges) {
            java.util.Map<String, Object> file = new java.util.LinkedHashMap<>();
            file.put("filePath", change.filePath);
            file.put("changeType", change.changeType);
            files.add(file);
        }
        return files;
    }

    private String detectChangeType(String section) {
        if (section.contains("--- /dev/null")) {
            return "added";
        }
        if (section.contains("+++ /dev/null")) {
            return "deleted";
        }
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

        // CWE pattern matching on the removed (vulnerable) code
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

        // Also try JavaParser on removed code to enrich method signatures
        enrichWithJavaParser(methods, change.removedCode);
        enrichWithJavaParser(methods, change.addedCode);

        // Build call chain between changed methods
        List<String> callChain = buildCallChain(effectiveMethodNames, change.removedCode + "\n" + change.addedCode);

        return new CodeAnalysisResult(change.filePath, change.changeType, null, null, methods, cweMatches, callChain);
    }

    private Set<String> extractDeclaredMethods(JavaFileChange change) {
        Set<String> names = new LinkedHashSet<>();
        collectDeclaredMethods(names, change.removedCode);
        collectDeclaredMethods(names, change.addedCode);
        return names;
    }

    private void collectDeclaredMethods(Set<String> names, String code) {
        if (code == null || code.trim().isEmpty()) {
            return;
        }
        try {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> result = parser.parse(code);
            CompilationUnit cu = result.getResult().orElse(null);
            if (cu == null) {
                result = parser.parse("class __Temp__ {\n" + code + "\n}");
                cu = result.getResult().orElse(null);
            }
            if (cu == null) {
                return;
            }
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                names.add(md.getNameAsString());
            }
        } catch (Exception ignored) {
        }
    }

    private List<String> extractCallsFromDiff(String code, String fromMethod) {
        List<String> calls = new ArrayList<>();
        // Simple regex: find method invocations (word followed by open paren)
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
        // Find cross-calls: if methodA calls methodB and both are in the changed set
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
                        // Replace with JavaParser-enriched version
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

    private static String shortName(String filePath) {
        int slash = filePath.lastIndexOf('/');
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }
}

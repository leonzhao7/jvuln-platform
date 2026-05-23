package com.jvuln.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
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

        ctx.reportProgress("Analyzing " + fileChanges.size() + " Java file(s)");

        List<CodeAnalysisResult> results = new ArrayList<>();
        for (JavaFileChange change : fileChanges) {
            ctx.reportProgress("Analyzing " + shortName(change.filePath));
            CodeAnalysisResult result = analyzeChange(change);
            results.add(result);
        }

        // Wrap in a container map for easy JSON access
        java.util.Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("analyzedFiles", results);
        int totalCwe = 0;
        for (CodeAnalysisResult r : results) totalCwe += r.getCweMatches().size();
        output.put("totalCweMatches", totalCwe);

        ctx.getWorkspaceManager().writeStageData(cveId, 3, output);
        ctx.reportProgress("Code analysis complete: " + results.size() + " file(s) analyzed");
        return StageResult.success(3, name(), output);
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
            changes.add(new JavaFileChange(filePath, section, removed.toString(), added.toString(), methodNames));
        }
        return changes;
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

        // CWE pattern matching on the removed (vulnerable) code
        List<CwePatternMatcher.MatchResult> patternHits =
                CwePatternMatcher.match(change.removedCode);
        for (CwePatternMatcher.MatchResult hit : patternHits) {
            cweMatches.add(new CodeAnalysisResult.CweMatch(
                    hit.cweId, hit.cweName, hit.pattern, hit.matchedCode, hit.explanation));
        }

        // Per-method analysis
        for (String methodName : change.methodNames) {
            List<String> calledMethods = extractCallsFromDiff(change.removedCode, methodName);
            String vulnSnippet = extractMethodSnippet(change.rawSection, methodName, true);
            String fixSnippet = extractMethodSnippet(change.rawSection, methodName, false);

            methods.add(new CodeAnalysisResult.MethodAnalysis(
                    methodName, buildSignature(change.removedCode, methodName),
                    vulnSnippet, fixSnippet, calledMethods));
        }

        // Also try JavaParser on removed code to enrich method signatures
        enrichWithJavaParser(methods, change.removedCode);

        // Build call chain between changed methods
        List<String> callChain = buildCallChain(change.methodNames, change.removedCode);

        return new CodeAnalysisResult(change.filePath, methods, cweMatches, callChain);
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

    private static class JavaFileChange {
        final String filePath;
        final String rawSection;
        final String removedCode;
        final String addedCode;
        final Set<String> methodNames;

        JavaFileChange(String filePath, String rawSection, String removedCode,
                        String addedCode, Set<String> methodNames) {
            this.filePath = filePath;
            this.rawSection = rawSection;
            this.removedCode = removedCode;
            this.addedCode = addedCode;
            this.methodNames = methodNames;
        }
    }
}

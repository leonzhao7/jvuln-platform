package com.jvuln.generator.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.pipeline.stage.Stage;
import com.jvuln.store.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class ReportGenerationStage implements Stage {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerationStage.class);
    private static final int MAX_RETRIES = 2;
    private static final String TASK_PROMPT =
            "为下面提供的 CVE 生成漏洞分析报告，严格遵循系统提示中的固定中文结构，只输出报告 Markdown 本身。";

    private final ObjectMapper mapper;
    private final PromptRegistry promptRegistry;
    private final DataExtractor dataExtractor;
    private final GeneratedFileReader generatedFileReader;

    public ReportGenerationStage(ObjectMapper mapper,
                                 PromptRegistry promptRegistry,
                                 DataExtractor dataExtractor,
                                 GeneratedFileReader generatedFileReader) {
        this.mapper = mapper;
        this.promptRegistry = promptRegistry;
        this.dataExtractor = dataExtractor;
        this.generatedFileReader = generatedFileReader;
    }

    @Override
    public int number() {
        return 5;
    }

    @Override
    public String name() {
        return "Report Generation";
    }

    @Override
    public StageResult execute(PipelineContext ctx) throws Exception {
        try {
            WorkspaceManager workspace = ctx.getWorkspaceManager();
            String cveId = ctx.getCveId();

            Object stage1 = workspace.readStageData(cveId, 1, Object.class);
            Object stage2 = safeRead(workspace, cveId, 2);
            Object stage3 = safeRead(workspace, cveId, 3);
            Object stage4 = safeRead(workspace, cveId, 4);

            Map<String, String> vars = new HashMap<String, String>();
            vars.put("cve_id", cveId);
            vars.put("intelligence", dataExtractor.trimIntelligence(stage1));
            vars.put("vulnerability_facts", dataExtractor.extractVulnerabilityFacts(stage2));
            vars.put("trigger_chain", dataExtractor.extractTriggerChain(stage3));
            vars.put("root_cause", dataExtractor.extractRootCause(stage3));
            vars.put("patch_diff", dataExtractor.extractDiff(ctx, stage4, 40 * 1024));
            vars.put("artifact", dataExtractor.extractArtifact(stage1));
            vars.put("generated_files", generatedFileReader.readGeneratedFiles(ctx.getWorkspacePath()));

            String template = promptRegistry.getPrompt("current/report-user");
            String userPrompt = promptRegistry.render(template, vars);

            String report = callWithRetry(ctx, userPrompt);
            if (report == null || report.trim().isEmpty()) {
                return StageResult.failure(5, name(), "Report generation returned empty output after retries.");
            }

            Path reportFile = ctx.getWorkspacePath().resolve("report/report.md");
            Files.createDirectories(reportFile.getParent());
            Files.write(reportFile, report.getBytes(StandardCharsets.UTF_8));

            ObjectNode summary = mapper.createObjectNode();
            summary.put("reportPath", "report/report.md");
            summary.put("charCount", report.length());
            workspace.writeStageData(cveId, 5, summary);

            ctx.reportProgress("Report generated (" + report.length() + " chars).");
            return StageResult.success(5, name(), summary);
        } catch (Exception e) {
            log.error("Stage 5 report generation failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return StageResult.failure(5, name(), "Report generation failed: " + msg);
        }
    }

    private Object safeRead(WorkspaceManager workspace, String cveId, int stageNum) {
        try {
            if (!workspace.isStageComplete(cveId, stageNum)) {
                return null;
            }
            return workspace.readStageData(cveId, stageNum, Object.class);
        } catch (Exception e) {
            log.warn("Stage 5 could not read stage {} data: {}", stageNum, e.getMessage());
            return null;
        }
    }

    private String callWithRetry(PipelineContext ctx, String userPrompt) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            try {
                LlmRequest request = LlmRequest.generation(
                        LlmPromptStage.REPORT_GENERATION, TASK_PROMPT, userPrompt);
                LlmResponse response = ctx.getLlmClient().chat(request);
                String content = response == null ? null : response.getContent();
                String stripped = stripMarkdownFence(content);
                if (stripped != null && !stripped.trim().isEmpty()) {
                    return stripped;
                }
                log.warn("Stage 5 attempt {} produced empty content.", attempt);
            } catch (Exception e) {
                last = e;
                log.warn("Stage 5 attempt {} failed: {}", attempt, e.getMessage());
            }
            if (attempt <= MAX_RETRIES) {
                Thread.sleep(2000L * attempt);
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    private String stripMarkdownFence(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }
}

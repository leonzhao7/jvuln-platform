package com.jvuln.generator.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.pipeline.model.StageResult;
import com.jvuln.store.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportGenerationStageTest {

    private static final String CVE = "CVE-2099-0001";

    @TempDir
    Path tempDir;

    private ObjectMapper mapper() {
        return new ObjectMapper();
    }

    private WorkspaceManager seededWorkspace() throws Exception {
        WorkspaceManager workspace = new WorkspaceManager(tempDir.toString(), mapper());
        workspace.initCveWorkspace(CVE);
        Map<String, Object> intel = new HashMap<String, Object>();
        intel.put("cveId", CVE);
        intel.put("description", "demo vuln");
        workspace.writeStageData(CVE, 1, intel);
        Path cve = workspace.getCvePath(CVE);
        Files.createDirectories(cve.resolve("poc"));
        Files.write(cve.resolve("poc/exploit.sh"), "echo pwned".getBytes(StandardCharsets.UTF_8));
        return workspace;
    }

    private LlmClient fixedLlm(final String content) {
        return new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                return new LlmResponse(content, 0, 0, "test", "stop");
            }

            @Override
            public Flux<String> chatStream(LlmRequest request) {
                return Flux.error(new UnsupportedOperationException("no stream"));
            }
        };
    }

    private ReportGenerationStage stage() {
        return new ReportGenerationStage(
                mapper(), new PromptRegistry(), new DataExtractor(mapper()), new GeneratedFileReader());
    }

    @Test
    void writesReportAndSucceeds() throws Exception {
        WorkspaceManager workspace = seededWorkspace();
        LlmClient llm = fixedLlm("# " + CVE + " 漏洞分析\n\n## 1. 漏洞介绍\n内容");
        PipelineContext ctx = new PipelineContext(CVE, workspace.getCvePath(CVE), llm, workspace);

        StageResult result = stage().execute(ctx);

        assertTrue(result.isSuccess());
        assertEquals(5, result.getStageNum());
        Path report = workspace.getCvePath(CVE).resolve("report/report.md");
        assertTrue(Files.exists(report));
        String md = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(md.contains("漏洞介绍"));
        assertTrue(workspace.isStageComplete(CVE, 5));
    }

    @Test
    void extractsIntroParagraphAsDescription() throws Exception {
        WorkspaceManager workspace = seededWorkspace();
        String report = "# " + CVE + " 漏洞分析\n\n## 1. 漏洞介绍\n\n"
                + "这是一个位于示例组件的远程代码执行漏洞，攻击者可借此执行任意代码。\n\n"
                + "| 项目 | 内容 |\n| --- | --- |\n| CVE 编号 | " + CVE + " |\n";
        LlmClient llm = fixedLlm(report);
        PipelineContext ctx = new PipelineContext(CVE, workspace.getCvePath(CVE), llm, workspace);

        StageResult result = stage().execute(ctx);

        assertTrue(result.isSuccess());
        com.fasterxml.jackson.databind.JsonNode data =
                (com.fasterxml.jackson.databind.JsonNode) result.getData();
        assertEquals("这是一个位于示例组件的远程代码执行漏洞，攻击者可借此执行任意代码。",
                data.path("description").asText());
    }

    @Test
    void emptyLlmOutputFailsStage() throws Exception {
        WorkspaceManager workspace = seededWorkspace();
        LlmClient llm = fixedLlm("   ");
        PipelineContext ctx = new PipelineContext(CVE, workspace.getCvePath(CVE), llm, workspace);

        StageResult result = stage().execute(ctx);

        assertFalse(result.isSuccess());
        assertEquals(5, result.getStageNum());
    }
}

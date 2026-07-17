package com.jvuln.generator;

import com.jvuln.llm.LlmRequest;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
class ToolDefinitionBuilder {

    private final AgentPhaseEngine phaseEngine;

    ToolDefinitionBuilder(AgentPhaseEngine phaseEngine) {
        this.phaseEngine = phaseEngine;
    }

    List<LlmRequest.ToolDef> buildToolDefinitions(AgentContext ctx) {
        List<LlmRequest.ToolDef> allTools = new ArrayList<>();

        Map<String, Object> planSchema = new LinkedHashMap<>();
        planSchema.put("type", "object");
        Map<String, Object> planProps = new LinkedHashMap<>();
        planProps.put("goal", prop("string", "Short goal for this Stage 4 attempt. MUST start with the exact CVE ID from the system prompt (e.g., 'Build CVE-YYYY-NNNNN reproduction environment')."));
        planProps.put("firstBatchFiles", stringArrayProp("Files to generate in the first broad batch"));
        planProps.put("minimalDeliverables", stringArrayProp("Smallest runnable candidate to prove before polishing"));
        planProps.put("validationSequence", stringArrayProp("Planned validation order, for example build -> startup -> poc"));
        planProps.put("deferredUntilVerified", stringArrayProp("Files or tasks intentionally deferred until validation is green"));
        planProps.put("risks", stringArrayProp("Main failure risks or likely repair targets"));
        planSchema.put("properties", planProps);
        planSchema.put("required", Arrays.asList("goal", "firstBatchFiles", "validationSequence"));
        allTools.add(new LlmRequest.ToolDef("submit_plan",
                "Submit the execution plan before build/start/write/validate actions. You may call submit_plan first and then continue with write_files in the same turn.",
                planSchema));

        Map<String, Object> batchWriteSchema = new LinkedHashMap<>();
        batchWriteSchema.put("type", "object");
        Map<String, Object> batchWriteProps = new LinkedHashMap<>();
        Map<String, Object> filesSchema = new LinkedHashMap<>();
        filesSchema.put("type", "array");
        Map<String, Object> fileItemSchema = new LinkedHashMap<>();
        fileItemSchema.put("type", "object");
        Map<String, Object> fileItemProps = new LinkedHashMap<>();
        fileItemProps.put("path", prop("string", "File path relative to workspace"));
        fileItemProps.put("content", prop("string", "Full file content"));
        fileItemSchema.put("properties", fileItemProps);
        fileItemSchema.put("required", Arrays.asList("path", "content"));
        filesSchema.put("items", fileItemSchema);
        batchWriteProps.put("files", filesSchema);
        batchWriteSchema.put("properties", batchWriteProps);
        batchWriteSchema.put("required", Collections.singletonList("files"));
        allTools.add(new LlmRequest.ToolDef("write_files",
                "Write one or more files in a single call. Every path must start with vuln-demo/ or poc/.",
                batchWriteSchema));

        Map<String, Object> readSchema = new LinkedHashMap<>();
        readSchema.put("type", "object");
        Map<String, Object> readProps = new LinkedHashMap<>();
        readProps.put("path", prop("string", "File path relative to workspace"));
        readSchema.put("properties", readProps);
        readSchema.put("required", Collections.singletonList("path"));
        allTools.add(new LlmRequest.ToolDef("read_file",
                "Read a full source, config, or report file from the workspace.",
                readSchema));

        Map<String, Object> readLogSchema = new LinkedHashMap<>();
        readLogSchema.put("type", "object");
        Map<String, Object> readLogProps = new LinkedHashMap<>();
        readLogProps.put("path", prop("string", "File path relative to workspace"));
        readLogProps.put("tailBytes", prop("integer", "Optional number of bytes to return from the end of the file"));
        readLogSchema.put("properties", readLogProps);
        readLogSchema.put("required", Collections.singletonList("path"));
        allTools.add(new LlmRequest.ToolDef("read_log",
                "Read the tail of a log, build output, or runtime trace file from the workspace.",
                readLogSchema));

        Map<String, Object> validateSchema = new LinkedHashMap<>();
        validateSchema.put("type", "object");
        Map<String, Object> validateProps = new LinkedHashMap<>();
        validateProps.put("focus", prop("string", "Optional validation focus such as compile, startup, poc, or full"));
        validateSchema.put("properties", validateProps);
        allTools.add(new LlmRequest.ToolDef("validate_artifacts",
                "Run backend-controlled validation for build, startup, and PoC evidence. Use this instead of ad-hoc curl loops whenever possible.",
                validateSchema));

        Map<String, Object> inspectRuntimeSchema = new LinkedHashMap<>();
        inspectRuntimeSchema.put("type", "object");
        inspectRuntimeSchema.put("properties", Collections.emptyMap());
        allTools.add(new LlmRequest.ToolDef("inspect_runtime",
                "Return the current runtime metadata and validator-known evidence for the running lab.",
                inspectRuntimeSchema));

        Map<String, Object> finishSchema = new LinkedHashMap<>();
        finishSchema.put("type", "object");
        Map<String, Object> finishProps = new LinkedHashMap<>();
        finishProps.put("vuln_demo_status", prop("string", "Status: startup_ok, compile_ok, compile_failed"));
        finishProps.put("poc_status", prop("string", "Status: verified, unverified, skipped"));
        finishProps.put("verification_evidence", prop("string", "Concrete evidence showing why the PoC is verified or still missing a signal"));
        finishProps.put("remaining_gap", prop("string", "If unverified, the exact missing condition or blocker"));
        finishProps.put("notes", prop("string", "Any notes about the generation"));
        finishSchema.put("properties", finishProps);
        allTools.add(new LlmRequest.ToolDef("finish",
                "Call when all deliverables are ready. Provide a summary of what was generated.",
                finishSchema));

        if (ctx == null) {
            return allTools;
        }

        List<LlmRequest.ToolDef> tools = new ArrayList<>();
        for (LlmRequest.ToolDef tool : allTools) {
            if (phaseEngine.isToolAllowed(ctx.phase, tool.getName())) {
                tools.add(tool);
            }
        }
        return tools;
    }

    private Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private Map<String, Object> stringArrayProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("description", description);
        p.put("items", Collections.singletonMap("type", "string"));
        return p;
    }
}

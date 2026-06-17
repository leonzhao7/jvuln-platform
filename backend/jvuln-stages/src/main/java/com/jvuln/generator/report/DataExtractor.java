package com.jvuln.generator.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.pipeline.model.PipelineContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据提取器
 *
 * 职责：从前序 Stage 的输出中提取 ArtifactGenStage 所需的数据
 *
 * @author JVuln Team
 */
@Component
public class DataExtractor {

    private final ObjectMapper mapper;

    public DataExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 提取 Patch Diff 数据
     *
     * @param ctx Pipeline 上下文
     * @param data Stage 2 输出数据
     * @param cap 最大字符数限制
     * @return Patch diff 字符串
     */
    public String extractDiff(PipelineContext ctx, Object data, int cap) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode rawDiff = root.path("rawDiff");
        if (!rawDiff.isMissingNode() && rawDiff.isTextual()) {
            String d = rawDiff.asText();
            return d.length() > cap ? d.substring(0, cap) + "\n...[truncated]" : d;
        }
        Path diffFile = ctx.getWorkspacePath().resolve("patches/fix.diff");
        if (Files.exists(diffFile)) {
            String d = new String(Files.readAllBytes(diffFile), StandardCharsets.UTF_8);
            return d.length() > cap ? d.substring(0, cap) + "\n...[truncated]" : d;
        }
        String full = mapper.writeValueAsString(data);
        return full.length() > cap ? full.substring(0, cap) + "\n...[truncated]" : full;
    }

    /**
     * 提取漏洞事实信息
     *
     * @param data Stage 2 输出数据
     * @return 漏洞事实 JSON 字符串
     */
    public String extractVulnerabilityFacts(Object data) throws Exception {
        if (data == null) return "{}";
        JsonNode root = mapper.valueToTree(data);
        JsonNode facts = root.path("vulnerabilityFacts");
        return facts.isMissingNode() ? "{}" : mapper.writeValueAsString(facts);
    }

    /**
     * 提取触发链信息
     *
     * @param data Stage 3 输出数据
     * @return 触发链 JSON 字符串
     */
    public String extractTriggerChain(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode chain = root.path("trigger_chain");
        return !chain.isMissingNode() ? mapper.writeValueAsString(chain) : "{}";
    }

    /**
     * 提取根因分析信息
     *
     * @param data Stage 3 输出数据
     * @return 根因分析 JSON 字符串
     */
    public String extractRootCause(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode analysis = root.path("code_analysis");
        if (!analysis.isMissingNode()) {
            ObjectNode out = mapper.createObjectNode();
            copyField(analysis, out, "vuln_root_cause");
            copyField(analysis, out, "fix_description");
            return mapper.writeValueAsString(out);
        }
        return "{}";
    }

    /**
     * 提取 Artifact 信息
     *
     * @param data Stage 1 输出数据
     * @return Artifact 字符串
     */
    public String extractArtifact(Object data) throws Exception {
        JsonNode root = mapper.valueToTree(data);
        JsonNode artifact = root.path("artifact");
        if (!artifact.isMissingNode()) {
            return artifact.isTextual() ? artifact.asText() : mapper.writeValueAsString(artifact);
        }
        return "";
    }

    /**
     * 从 JSON 字符串中提取指定字段
     *
     * @param raw JSON 字符串
     * @param field 字段名
     * @return 字段值
     */
    public String extractJsonString(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(raw);
            return node.path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 复制 JSON 字段（内部辅助方法）
     */
    private void copyField(JsonNode src, ObjectNode dst, String field) {
        JsonNode v = src.path(field);
        if (!v.isMissingNode()) dst.set(field, v);
    }
}

package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvuln.llm.LlmRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * ArtifactGen 阶段通用工具方法
 *
 * 职责：提供字符串处理、Shell 转义、JSON 渲染等公共工具方法
 *
 * @author JVuln Team
 */
public final class ArtifactGenUtils {

    private ArtifactGenUtils() {
    }

    /**
     * 截取字符串尾部（保留最后 max 个字符）
     */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(s.length() - max) : s;
    }

    /**
     * 截取字符串头部（保留前 max 个字符）
     */
    public static String truncateHead(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "\n...[truncated]" : s;
    }

    /**
     * 将多行文本压缩为单行，超出长度截断
     */
    public static String singleLine(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 15)) + "...[truncated]";
    }

    /**
     * Shell 单引号转义
     */
    public static String shellEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "'\"'\"'");
    }

    /**
     * Shell 单引号包裹
     */
    public static String shellQuote(String value) {
        return "'" + shellEscape(value) + "'";
    }

    /**
     * 字节数组转十六进制字符串
     */
    public static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * 去除 Markdown 代码块包围
     */
    public static String stripMarkdownFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline < 0) {
            return s;
        }
        s = s.substring(firstNewline + 1);
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    /**
     * 拼接列表元素为字符串
     */
    public static String joinItems(java.util.List<String> items, String delimiter) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /**
     * 规范化字符串列表（去重、去空）
     */
    public static List<String> normalizeList(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (!out.contains(value.trim())) {
                out.add(value.trim());
            }
        }
        return out;
    }

    /**
     * 从 JsonNode 读取字符串列表
     */
    public static List<String> readList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
            return out;
        }
        String text = node.asText("").trim();
        if (!text.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    /**
     * Copy field from source JsonNode to destination ObjectNode if it exists
     */
    public static void copyField(JsonNode src, ObjectNode dst, String field) {
        JsonNode v = src.path(field);
        if (!v.isMissingNode()) {
            dst.set(field, v);
        }
    }

    /**
     * Extract tool definition names from tool list
     */
    public static String toolDefNames(List<LlmRequest.ToolDef> tools) {
        if (tools == null || tools.isEmpty()) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        for (LlmRequest.ToolDef tool : tools) {
            names.add(tool.getName());
        }
        return names.toString();
    }

    /**
     * Extract tool use names from content blocks
     */
    public static String toolUseNames(List<LlmRequest.ContentBlock> toolUses) {
        if (toolUses == null || toolUses.isEmpty()) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        for (LlmRequest.ContentBlock block : toolUses) {
            names.add(block.getToolName());
        }
        return names.toString();
    }

    /**
     * Extract a string field from JSON string
     */
    public static String extractJsonString(ObjectMapper mapper, String raw, String field) {
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
}

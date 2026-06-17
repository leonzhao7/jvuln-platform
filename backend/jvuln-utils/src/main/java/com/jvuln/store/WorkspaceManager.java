package com.jvuln.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

@Component
public class WorkspaceManager {

    private final Path workspaceRoot;
    private final ObjectMapper objectMapper;

    // CVE ID 格式校验正则表达式
    private static final Pattern CVE_PATTERN = Pattern.compile("^CVE-\\d{4}-\\d{4,}$");

    public WorkspaceManager(@Value("${jvuln.workspace.root:workspace}") String root,
                            ObjectMapper objectMapper) {
        this.workspaceRoot = Paths.get(root).toAbsolutePath();
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * 获取 CVE 工作目录路径，并进行路径遍历防御
     *
     * @param cveId CVE 编号
     * @return CVE 工作目录路径
     * @throws IllegalArgumentException 如果 cveId 格式不正确或存在路径遍历攻击
     */
    public Path getCvePath(String cveId) {
        // 1. 格式校验：必须符合 CVE-YYYY-NNNNN 格式
        if (cveId == null || !CVE_PATTERN.matcher(cveId).matches()) {
            throw new IllegalArgumentException("Invalid CVE ID format: " + cveId);
        }

        // 2. 路径遍历防御：确保解析后的路径在 workspaceRoot 内
        Path resolved = workspaceRoot.resolve(cveId).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new SecurityException("Path traversal attempt detected: " + cveId);
        }

        return resolved;
    }

    public Path initCveWorkspace(String cveId) throws IOException {
        Path cvePath = getCvePath(cveId);
        Files.createDirectories(cvePath.resolve("stages"));
        Files.createDirectories(cvePath.resolve("patches/source/before"));
        Files.createDirectories(cvePath.resolve("patches/source/after"));
        Files.createDirectories(cvePath.resolve("vuln-demo"));
        Files.createDirectories(cvePath.resolve("poc"));
        Files.createDirectories(cvePath.resolve("references/articles"));
        Files.createDirectories(cvePath.resolve("report"));
        return cvePath;
    }

    public Path getStageFile(String cveId, int stageNum) {
        String filename;
        switch (stageNum) {
            case 1: filename = "1_intelligence.json"; break;
            case 2: filename = "2_patch.json"; break;
            case 3: filename = "3_reasoning.json"; break;
            case 4: filename = "4_artifacts.json"; break;
            default: throw new IllegalArgumentException("Invalid stage: " + stageNum);
        }
        return getCvePath(cveId).resolve("stages").resolve(filename);
    }

    public boolean isStageComplete(String cveId, int stageNum) {
        return Files.exists(getStageFile(cveId, stageNum));
    }

    public <T> void writeStageData(String cveId, int stageNum, T data) throws IOException {
        Path file = getStageFile(cveId, stageNum);
        Files.createDirectories(file.getParent());
        objectMapper.writeValue(file.toFile(), data);
    }

    public <T> T readStageData(String cveId, int stageNum, Class<T> type) throws IOException {
        Path file = getStageFile(cveId, stageNum);
        if (!Files.exists(file)) {
            return null;
        }
        return objectMapper.readValue(file.toFile(), type);
    }

    public void writeDiff(String cveId, String diffContent) throws IOException {
        Path diffFile = getCvePath(cveId).resolve("patches/fix.diff");
        Files.write(diffFile, diffContent.getBytes(StandardCharsets.UTF_8));
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}

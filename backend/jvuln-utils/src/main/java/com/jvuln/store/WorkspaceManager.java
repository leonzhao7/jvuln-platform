package com.jvuln.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class WorkspaceManager {

    private final Path workspaceRoot;
    private final ObjectMapper objectMapper;

    public WorkspaceManager(@Value("${jvuln.workspace.root:workspace}") String root) {
        this.workspaceRoot = Paths.get(root).toAbsolutePath();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public Path getCvePath(String cveId) {
        return workspaceRoot.resolve(cveId);
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
            case 3: filename = "3_analysis.json"; break;
            case 4: filename = "4_reasoning.json"; break;
            case 5: filename = "5_artifacts.json"; break;
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

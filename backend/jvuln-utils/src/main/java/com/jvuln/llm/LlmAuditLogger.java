package com.jvuln.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class LlmAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(LlmAuditLogger.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
            .withZone(ZoneId.systemDefault());

    private static final ThreadLocal<Path> CONTEXT_DIR = new ThreadLocal<>();

    private final ObjectMapper prettyMapper;

    public LlmAuditLogger() {
        this.prettyMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static void setContextDir(Path dir) {
        CONTEXT_DIR.set(dir);
    }

    public static void clearContextDir() {
        CONTEXT_DIR.remove();
    }

    public void log(String model, String endpoint, ObjectNode requestBody, String rawResponse) {
        Path contextDir = CONTEXT_DIR.get();
        if (contextDir == null) {
            return;
        }
        try {
            Path auditDir = contextDir.resolve("llm-audit");
            Files.createDirectories(auditDir);

            Instant now = Instant.now();
            String ts = FILE_TS.format(now);
            String shortEndpoint = endpoint.replace("/v1/", "").replace("/", "-");
            String safeModel = model.replaceAll("[^a-zA-Z0-9._-]", "_");
            String fileName = ts + "_" + safeModel + "_" + shortEndpoint + ".json";

            ObjectNode entry = prettyMapper.createObjectNode();
            entry.put("timestamp", now.toString());
            entry.put("model", model);
            entry.put("endpoint", endpoint);
            entry.set("request", requestBody);

            JsonNode responseJson = parseResponseSafe(rawResponse);
            entry.set("response", responseJson);

            Path file = auditDir.resolve(fileName);
            byte[] bytes = prettyMapper.writeValueAsString(entry).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = Files.newOutputStream(file)) {
                os.write(bytes);
            }
        } catch (IOException e) {
            log.warn("Failed to write LLM audit log: {}", e.getMessage());
        }
    }

    private JsonNode parseResponseSafe(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return prettyMapper.createObjectNode().put("_raw", "");
        }
        try {
            return prettyMapper.readTree(raw);
        } catch (Exception e) {
            return prettyMapper.createObjectNode().put("_raw", raw);
        }
    }
}

package com.jvuln.generator.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reads the actually-generated vuln-demo/PoC files into a labeled, size-capped blob for the Stage 5 prompt. */
@Component
public class GeneratedFileReader {

    private static final Logger log = LoggerFactory.getLogger(GeneratedFileReader.class);

    private static final int PER_FILE_CAP = 8 * 1024;
    private static final int TOTAL_CAP = 40 * 1024;
    private static final int MAX_JAVA_SOURCES = 12;

    // Skeleton files that carry little report value; included only after CVE-specific sources.
    private static final List<String> LOW_VALUE_SUFFIXES = java.util.Arrays.asList(
            "Application.java", "LabInfoController.java");

    public String readGeneratedFiles(Path cvePath) {
        StringBuilder blob = new StringBuilder();
        int[] budget = new int[] { TOTAL_CAP };

        appendFile(blob, cvePath, "vuln-demo/pom.xml", budget);
        appendFile(blob, cvePath, "vuln-demo/src/main/resources/application.properties", budget);

        for (Path java : collectJavaSources(cvePath)) {
            if (budget[0] <= 0) {
                break;
            }
            String rel = cvePath.relativize(java).toString().replace('\\', '/');
            appendFile(blob, cvePath, rel, budget);
        }

        appendFile(blob, cvePath, "poc/exploit.sh", budget);

        if (blob.length() == 0) {
            return "(本次运行未找到可读取的 demo/PoC 文件。)";
        }
        return blob.toString();
    }

    private List<Path> collectJavaSources(Path cvePath) {
        Path srcRoot = cvePath.resolve("vuln-demo/src/main/java");
        List<Path> sources = new ArrayList<Path>();
        if (!Files.isDirectory(srcRoot)) {
            return sources;
        }
        try {
            List<Path> all = new ArrayList<Path>();
            Files.walk(srcRoot)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(all::add);
            // CVE-specific sources first, low-value skeletons last.
            all.sort(Comparator.comparingInt(this::lowValueRank));
            for (Path p : all) {
                if (sources.size() >= MAX_JAVA_SOURCES) {
                    break;
                }
                sources.add(p);
            }
        } catch (IOException e) {
            log.warn("Failed to walk demo sources under {}: {}", srcRoot, e.getMessage());
        }
        return sources;
    }

    private int lowValueRank(Path p) {
        String name = p.getFileName().toString();
        for (String suffix : LOW_VALUE_SUFFIXES) {
            if (name.equals(suffix)) {
                return 1;
            }
        }
        return 0;
    }

    private void appendFile(StringBuilder blob, Path cvePath, String relPath, int[] budget) {
        if (budget[0] <= 0) {
            return;
        }
        Path file = cvePath.resolve(relPath);
        if (!Files.isRegularFile(file)) {
            return;
        }
        String content;
        try {
            byte[] bytes = Files.readAllBytes(file);
            content = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read generated file {}: {}", relPath, e.getMessage());
            return;
        }

        boolean truncatedFile = false;
        if (content.length() > PER_FILE_CAP) {
            content = content.substring(0, PER_FILE_CAP);
            truncatedFile = true;
        }
        if (content.length() > budget[0]) {
            content = content.substring(0, Math.max(0, budget[0]));
            truncatedFile = true;
        }

        blob.append("### ").append(relPath).append("\n\n");
        blob.append("```\n").append(content).append("\n```\n");
        if (truncatedFile) {
            blob.append("_(truncated)_\n");
        }
        blob.append("\n");
        budget[0] -= content.length();
    }
}

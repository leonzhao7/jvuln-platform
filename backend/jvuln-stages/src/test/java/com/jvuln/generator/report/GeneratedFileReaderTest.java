package com.jvuln.generator.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedFileReaderTest {

    @Test
    void readsPresentFilesAndSkipsMissing(@TempDir Path cve) throws Exception {
        Files.createDirectories(cve.resolve("vuln-demo"));
        Files.createDirectories(cve.resolve("poc"));
        Files.write(cve.resolve("vuln-demo/pom.xml"),
                "<project>demo-pom</project>".getBytes(StandardCharsets.UTF_8));
        Files.write(cve.resolve("poc/exploit.sh"),
                "echo pwned".getBytes(StandardCharsets.UTF_8));

        GeneratedFileReader reader = new GeneratedFileReader();
        String blob = reader.readGeneratedFiles(cve);

        assertTrue(blob.contains("vuln-demo/pom.xml"));
        assertTrue(blob.contains("demo-pom"));
        assertTrue(blob.contains("poc/exploit.sh"));
        assertTrue(blob.contains("echo pwned"));
        // application.properties was never created -> not listed
        assertFalse(blob.contains("application.properties"));
    }

    @Test
    void perFileCapTruncatesLargeFile(@TempDir Path cve) throws Exception {
        Files.createDirectories(cve.resolve("poc"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            big.append('x');
        }
        Files.write(cve.resolve("poc/exploit.sh"), big.toString().getBytes(StandardCharsets.UTF_8));

        GeneratedFileReader reader = new GeneratedFileReader();
        String blob = reader.readGeneratedFiles(cve);

        assertTrue(blob.contains("truncated"));
        assertTrue(blob.length() < 20000);
    }

    @Test
    void emptyWorkspaceReturnsNonNull(@TempDir Path cve) throws Exception {
        GeneratedFileReader reader = new GeneratedFileReader();
        String blob = reader.readGeneratedFiles(cve);
        assertTrue(blob != null);
    }
}

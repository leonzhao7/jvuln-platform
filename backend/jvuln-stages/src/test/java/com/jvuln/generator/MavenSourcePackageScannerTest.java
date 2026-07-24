package com.jvuln.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class MavenSourcePackageScannerTest {

    @Test
    void scansPackagesFromSourceJar(@TempDir Path tmp) throws Exception {
        Path fakeJar = tmp.resolve("test-1.0.0-sources.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(fakeJar))) {
            addEntry(zip, "org/h2/util/JdbcUtils.java", "package org.h2.util;\nclass JdbcUtils {}");
            addEntry(zip, "org/h2/command/Parser.java", "package org.h2.command;\nclass Parser {}");
            addEntry(zip, "org/h2/command/dml/Select.java", "package org.h2.command.dml;\nclass Select {}");
            addEntry(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0");
        }

        MavenSourcePackageScanner scanner = new MavenSourcePackageScanner();
        Set<String> packages = scanner.scanPackagesFromJar(fakeJar);

        assertEquals(3, packages.size());
        assertTrue(packages.contains("org.h2.util"));
        assertTrue(packages.contains("org.h2.command"));
        assertTrue(packages.contains("org.h2.command.dml"));
    }

    private void addEntry(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes("UTF-8"));
        zip.closeEntry();
    }
}

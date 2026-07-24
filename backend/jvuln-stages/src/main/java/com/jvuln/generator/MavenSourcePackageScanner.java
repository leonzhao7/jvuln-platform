package com.jvuln.generator;

import com.jvuln.util.RequestLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
class MavenSourcePackageScanner {

    private static final Logger log = LoggerFactory.getLogger(MavenSourcePackageScanner.class);
    private static final String CENTRAL = "https://repo1.maven.org/maven2";
    private static final Pattern PACKAGE_DECL = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");

    private final WebClient webClient;

    MavenSourcePackageScanner() {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(60));
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(30 * 1024 * 1024))
                .filter(RequestLogContext.webRequestFilter())
                .defaultHeader("User-Agent", "JVuln-Platform/1.0")
                .build();
    }

    Set<String> scanPackages(String groupId, String artifactId, String version) {
        if (groupId == null || artifactId == null || version == null) {
            log.warn("MavenSourcePackageScanner: missing coordinates");
            return new LinkedHashSet<>();
        }
        String url = buildSourcesUrl(groupId, artifactId, version);
        log.info("Downloading Maven sources JAR: {}", url);

        File tmp = null;
        try {
            tmp = File.createTempFile("jvuln-src-", ".jar");
            downloadJar(url, tmp);
            return scanPackagesFromJar(tmp.toPath());
        } catch (Exception e) {
            log.warn("Failed to scan packages from {}:{}:{}: {}", groupId, artifactId, version, e.getMessage());
            return new LinkedHashSet<>();
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    Set<String> scanPackagesFromJar(Path jarPath) throws Exception {
        Set<String> packages = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".java")) continue;
                String pkg = extractPackage(zip.getInputStream(entry));
                if (pkg != null && !pkg.isEmpty()) {
                    packages.add(pkg);
                }
            }
        }
        log.info("Scanned {} packages from {}", packages.size(), jarPath.getFileName());
        return packages;
    }

    private String extractPackage(InputStream in) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = PACKAGE_DECL.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
                if (line.trim().startsWith("public") || line.trim().startsWith("class")
                        || line.trim().startsWith("interface") || line.trim().startsWith("enum")) {
                    break;
                }
            }
        }
        return null;
    }

    private String buildSourcesUrl(String groupId, String artifactId, String version) {
        String groupPath = groupId.replace('.', '/');
        return CENTRAL + "/" + groupPath + "/" + artifactId + "/" + version
                + "/" + artifactId + "-" + version + "-sources.jar";
    }

    private void downloadJar(String url, File dest) throws Exception {
        try (OutputStream os = new FileOutputStream(dest)) {
            byte[] bytes = webClient.get().uri(url).retrieve().bodyToMono(byte[].class).block();
            if (bytes != null) os.write(bytes);
        }
    }
}

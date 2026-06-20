package com.jvuln.generator;

import com.jvuln.store.entity.JavaProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
class SkeletonWriter {

    private static final Logger log = LoggerFactory.getLogger(SkeletonWriter.class);
    private static final int VULN_DEMO_PORT = 18080;

    void writeMinimalSkeleton(Path vulnDemoPath, JavaProfile profile) throws IOException {
        Files.createDirectories(vulnDemoPath.resolve("src/main/java/com/jvuln/demo"));
        Files.createDirectories(vulnDemoPath.resolve("src/main/resources"));
        Files.createDirectories(vulnDemoPath.getParent().resolve("poc"));
        Files.createDirectories(vulnDemoPath.getParent().resolve("report"));

        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <parent>\n"
                + "        <groupId>org.springframework.boot</groupId>\n"
                + "        <artifactId>spring-boot-starter-parent</artifactId>\n"
                + "        <version>" + profile.getSpringBootVersion() + "</version>\n"
                + "        <relativePath/>\n"
                + "    </parent>\n"
                + "    <groupId>com.jvuln</groupId>\n"
                + "    <artifactId>vuln-demo</artifactId>\n"
                + "    <version>0.0.1-SNAPSHOT</version>\n"
                + "    <packaging>jar</packaging>\n"
                + "    <properties>\n"
                + "        <java.version>" + profile.getMavenJavaVersion() + "</java.version>\n"
                + "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
                + "    </properties>\n"
                + "    <dependencies>\n"
                + "        <dependency>\n"
                + "            <groupId>org.springframework.boot</groupId>\n"
                + "            <artifactId>spring-boot-starter-web</artifactId>\n"
                + "        </dependency>\n"
                + "    </dependencies>\n"
                + "    <build>\n"
                + "        <plugins>\n"
                + "            <plugin>\n"
                + "                <groupId>org.springframework.boot</groupId>\n"
                + "                <artifactId>spring-boot-maven-plugin</artifactId>\n"
                + "            </plugin>\n"
                + "        </plugins>\n"
                + "    </build>\n"
                + "</project>\n";
        writeSkeletonFile(vulnDemoPath.resolve("pom.xml"), pom, false, false);

        String app = "// FOR AUTHORIZED SECURITY EDUCATION ONLY\n"
                + "package com.jvuln.demo;\n\n"
                + "import org.springframework.boot.SpringApplication;\n"
                + "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n"
                + "@SpringBootApplication\n"
                + "public class Application {\n"
                + "    public static void main(String[] args) {\n"
                + "        SpringApplication.run(Application.class, args);\n"
                + "    }\n"
                + "}\n";
        writeSkeletonFile(vulnDemoPath.resolve("src/main/java/com/jvuln/demo/Application.java"), app, false, false);

        String labInfo = "// FOR AUTHORIZED SECURITY EDUCATION ONLY\n"
                + "package com.jvuln.demo;\n\n"
                + "import java.io.File;\n"
                + "import java.util.LinkedHashMap;\n"
                + "import java.util.Map;\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.web.bind.annotation.RestController;\n\n"
                + "@RestController\n"
                + "public class LabInfoController {\n"
                + "    @GetMapping(\"/\")\n"
                + "    public Map<String, Object> index() {\n"
                + "        Map<String, Object> out = new LinkedHashMap<String, Object>();\n"
                + "        out.put(\"status\", \"ok\");\n"
                + "        out.put(\"service\", \"jvuln-demo\");\n"
                + "        return out;\n"
                + "    }\n\n"
                + "    @GetMapping(\"/api/lab/info\")\n"
                + "    public Map<String, Object> info() {\n"
                + "        Map<String, Object> out = new LinkedHashMap<String, Object>();\n"
                + "        out.put(\"status\", \"ok\");\n"
                + "        out.put(\"userDir\", new File(\".\").getAbsoluteFile().getParentFile().getAbsolutePath());\n"
                + "        out.put(\"javaVersion\", System.getProperty(\"java.version\", \"\"));\n"
                + "        out.put(\"tmpdir\", System.getProperty(\"java.io.tmpdir\", \"\"));\n"
                + "        out.put(\"port\", System.getProperty(\"server.port\", \"18080\"));\n"
                + "        return out;\n"
                + "    }\n"
                + "}\n";
        writeSkeletonFile(vulnDemoPath.resolve("src/main/java/com/jvuln/demo/LabInfoController.java"),
                labInfo, false, false);

        String properties = "server.port=" + VULN_DEMO_PORT + "\n"
                + "spring.main.banner-mode=off\n"
                + "logging.level.root=INFO\n";
        writeSkeletonFile(vulnDemoPath.resolve("src/main/resources/application.properties"),
                properties, false, false);

        String javaHome = profile.getJavaHome();
        String build = "#!/bin/bash\ncd \"$(dirname \"$0\")\"\n"
                + "export JAVA_HOME=\"" + javaHome + "\"\n"
                + "export PATH=\"$JAVA_HOME/bin:$PATH\"\n"
                + "mvn package -DskipTests -q\n";
        writeSkeletonFile(vulnDemoPath.resolve("build.sh"), build, true, false);

        String run = "#!/bin/bash\ncd \"$(dirname \"$0\")\"\n"
                + "export JAVA_HOME=\"" + javaHome + "\"\n"
                + "export PATH=\"$JAVA_HOME/bin:$PATH\"\n"
                + "exec java -jar target/*.jar --server.port=" + VULN_DEMO_PORT + "\n";
        writeSkeletonFile(vulnDemoPath.resolve("run.sh"), run, true, false);

        log.info("Ensured baseline skeleton: pom.xml, Application.java, LabInfoController.java, application.properties, build.sh, run.sh");
    }

    private void writeSkeletonFile(Path path, String content, boolean executable, boolean overwrite) throws IOException {
        if (Files.exists(path) && !overwrite) {
            if (executable) {
                path.toFile().setExecutable(true);
            }
            return;
        }
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        if (executable) {
            path.toFile().setExecutable(true);
        }
    }
}

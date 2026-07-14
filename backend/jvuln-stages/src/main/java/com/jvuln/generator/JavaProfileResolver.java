package com.jvuln.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.pipeline.model.PipelineContext;
import com.jvuln.store.JavaProfileRepository;
import com.jvuln.store.entity.JavaProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Java Profile 解析器
 *
 * 职责：基于 CVE 信息和可用的 Java Profile，使用 LLM 选择最合适的 Java/Spring Boot 配置
 */
@Component
class JavaProfileResolver {

    private static final Logger log = LoggerFactory.getLogger(JavaProfileResolver.class);

    private final ObjectMapper mapper;
    private final JavaProfileRepository javaProfileRepo;
    private final PromptRegistry promptRegistry;

    JavaProfileResolver(ObjectMapper mapper, JavaProfileRepository javaProfileRepo,
                        PromptRegistry promptRegistry) {
        this.mapper = mapper;
        this.javaProfileRepo = javaProfileRepo;
        this.promptRegistry = promptRegistry;
    }

    /**
     * 解析最佳 Java Profile
     *
     * @param ctx Pipeline 上下文
     * @param rawIntelligence Stage 1 的情报数据
     * @return 选定的 Java Profile（包含可能的 Spring Boot 版本覆盖）
     */
    JavaProfile resolveJavaProfile(PipelineContext ctx, Object rawIntelligence) {
        List<JavaProfile> profiles = javaProfileRepo.findAll();
        if (profiles.isEmpty()) {
            return createHardcodedFallback();
        }

        try {
            JsonNode intel = mapper.valueToTree(rawIntelligence);
            String groupId = intel.at("/artifact/groupId").asText("");
            String artifactId = intel.at("/artifact/artifactId").asText("");
            String affectedTo = intel.at("/affectedVersions/to").asText("");
            String fixedVersion = intel.at("/fixedVersion").asText("");

            String profileListText = buildProfileList(profiles);
            String taskPrompt = promptRegistry.getPrompt("current/artifact-java-profile-resolver");
            String userPrompt = buildUserPrompt(groupId, artifactId, affectedTo, fixedVersion, profileListText);

            LlmRequest req = LlmRequest.reasoning(
                    LlmPromptStage.ARTIFACT_GENERATION, taskPrompt, userPrompt);
            LlmResponse resp = ctx.getLlmClient().chat(req);
            String raw = stripMarkdownFence(resp.getContent().trim());

            JsonNode result = mapper.readTree(raw);
            String selectedName = result.path("profile").asText("").trim();
            String recommendedSBVersion = result.path("springBootVersion").asText("").trim();

            JavaProfile selected = findProfileByName(profiles, selectedName);
            if (selected == null) {
                throw new IllegalStateException(
                        "LLM returned unknown Java profile: '" + selectedName + "'");
            }
            return applySpringBootVersionOverride(selected, recommendedSBVersion);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Java profile LLM resolution failed", e);
        }
    }

    private String buildProfileList(List<JavaProfile> profiles) {
        StringBuilder sb = new StringBuilder();
        for (JavaProfile p : profiles) {
            sb.append(String.format("- %s: Java %s, Spring Boot %s%s\n",
                    p.getName(), p.getJavaVersion(), p.getSpringBootVersion(),
                    Boolean.TRUE.equals(p.getIsDefault()) ? " (default)" : ""));
        }
        return sb.toString();
    }


    private String buildUserPrompt(String groupId, String artifactId, String affectedTo,
                                   String fixedVersion, String profileList) {
        return String.format(
                "CVE artifact: %s:%s\n" +
                        "Affected versions: %s\n" +
                        "Fixed version: %s\n\n" +
                        "Available profiles:\n%s",
                groupId, artifactId, affectedTo, fixedVersion, profileList
        );
    }

    private String stripMarkdownFence(String raw) {
        if (raw.startsWith("```")) {
            return raw.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("(?s)\\n?```$", "").trim();
        }
        return raw;
    }

    private JavaProfile findProfileByName(List<JavaProfile> profiles, String name) {
        return profiles.stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private JavaProfile applySpringBootVersionOverride(JavaProfile selected, String recommendedSBVersion) {
        if (recommendedSBVersion.isEmpty() || recommendedSBVersion.equals(selected.getSpringBootVersion())) {
            log.info("LLM selected Java profile: {}", selected.getName());
            return selected;
        }

        log.info("LLM recommends Spring Boot {} (profile default: {})",
                recommendedSBVersion, selected.getSpringBootVersion());

        // Clone profile with overridden Spring Boot version
        JavaProfile overridden = new JavaProfile();
        overridden.setName(selected.getName());
        overridden.setJavaVersion(selected.getJavaVersion());
        overridden.setJavaHome(selected.getJavaHome());
        overridden.setSpringBootVersion(recommendedSBVersion);
        overridden.setMavenJavaVersion(selected.getMavenJavaVersion());
        overridden.setSyntaxConstraints(selected.getSyntaxConstraints());
        return overridden;
    }

    private JavaProfile createHardcodedFallback() {
        JavaProfile fallback = new JavaProfile();
        fallback.setName("Default (Java 8)");
        fallback.setJavaVersion("8");
        fallback.setJavaHome(System.getProperty("java.home", "/usr/lib/jvm/java-8-openjdk-amd64"));
        fallback.setSpringBootVersion("2.7.18");
        fallback.setMavenJavaVersion("1.8");
        fallback.setSyntaxConstraints("Java 8 syntax only (no var, no List.of, no records, no text blocks)");
        fallback.setIsDefault(Boolean.TRUE);
        return fallback;
    }
}

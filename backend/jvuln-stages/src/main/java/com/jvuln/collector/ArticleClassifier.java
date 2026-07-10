package com.jvuln.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.store.model.CveIntelligence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 使用LLM对CVE相关的URL进行分类
 * 分类类型：advisory（安全公告）、analysis（技术分析）、patch（补丁/修复）、poc（PoC/Exploit）、other（其他）
 */
@Component
public class ArticleClassifier {

    private static final Logger log = LoggerFactory.getLogger(ArticleClassifier.class);
    private final LlmClient llmClient;
    private final PromptRegistry promptRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ArticleClassifier(LlmClient llmClient, PromptRegistry promptRegistry) {
        this.llmClient = llmClient;
        this.promptRegistry = promptRegistry;
    }

    /**
     * 对文章列表进行去重和分类
     * @param articles 原始文章列表
     * @param cveId CVE编号
     * @return 去重并分类后的文章列表
     */
    public List<CveIntelligence.Article> classifyAndDeduplicate(
            List<CveIntelligence.Article> articles, String cveId) {

        if (articles == null || articles.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 去重：基于URL（标准化后比较）
        Map<String, CveIntelligence.Article> uniqueArticles = new LinkedHashMap<>();
        for (CveIntelligence.Article article : articles) {
            String url = normalizeUrl(article.getUrl());
            if (url != null && !url.isEmpty() && !uniqueArticles.containsKey(url)) {
                uniqueArticles.put(url, article);
            }
        }

        List<CveIntelligence.Article> deduplicated = new ArrayList<>(uniqueArticles.values());

        // 2. 如果文章数量太少或太多，跳过LLM分类
        if (deduplicated.size() < 2) {
            log.info("ArticleClassifier: Only {} article(s), skipping classification", deduplicated.size());
            return deduplicated;
        }

        if (deduplicated.size() > 20) {
            log.warn("ArticleClassifier: {} articles exceed limit, truncating to 20", deduplicated.size());
            deduplicated = deduplicated.subList(0, 20);
        }

        // 3. 调用LLM进行分类
        try {
            log.info("ArticleClassifier: Classifying {} articles for {}", deduplicated.size(), cveId);
            List<CveIntelligence.Article> classified = classifyWithLlm(deduplicated, cveId);
            log.info("ArticleClassifier: Classification complete for {}", cveId);
            return classified;
        } catch (Exception e) {
            log.warn("ArticleClassifier: Failed to classify articles for {}: {}", cveId, e.getMessage());
            // 分类失败时返回原始列表
            return deduplicated;
        }
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        return url.trim().replaceAll("/+$", "");
    }

    private List<CveIntelligence.Article> classifyWithLlm(
            List<CveIntelligence.Article> articles, String cveId) throws Exception {

        String taskPrompt = promptRegistry.getPrompt("current/intelligence-article-classifier");
        String userPrompt = buildUserPrompt(articles, cveId);

        // 使用reasoning方法创建请求（jsonMode=true，temperature=0.0）
        LlmRequest request = new LlmRequest(
            LlmPromptStage.INTELLIGENCE,
            taskPrompt,
            Collections.singletonList(LlmRequest.Message.user(userPrompt)),
            0.0,
            8192,
            true
        );

        LlmResponse response = llmClient.chat(request);
        String content = response.getContent();

        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("LLM returned empty content");
        }

        // 解析LLM返回的分类结果
        return parseClassificationResult(content, articles);
    }


    private String buildUserPrompt(List<CveIntelligence.Article> articles, String cveId) {
        StringBuilder sb = new StringBuilder();
        sb.append("CVE ID: ").append(cveId).append("\n\n");
        sb.append("Classify these ").append(articles.size()).append(" URLs:\n\n");

        for (int i = 0; i < articles.size(); i++) {
            CveIntelligence.Article article = articles.get(i);
            sb.append(i + 1).append(". ");
            sb.append("URL: ").append(article.getUrl()).append("\n");
            if (article.getTitle() != null && !article.getTitle().isEmpty()) {
                sb.append("   Title: ").append(article.getTitle()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private List<CveIntelligence.Article> parseClassificationResult(
            String jsonContent, List<CveIntelligence.Article> originalArticles) throws Exception {

        // 去除可能的markdown代码块标记
        String cleaned = jsonContent.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        // 解析JSON数组
        JsonNode root = mapper.readTree(cleaned);
        if (!root.isArray()) {
            throw new RuntimeException("LLM response is not a JSON array");
        }

        // 构建URL到分类的映射
        Map<String, String> urlToCategory = new HashMap<>();
        for (JsonNode node : root) {
            String url = node.path("url").asText("");
            String category = node.path("category").asText("other");
            if (!url.isEmpty()) {
                urlToCategory.put(url, category);
            }
        }

        // 创建新的Article列表，添加分类信息
        List<CveIntelligence.Article> classified = new ArrayList<>();
        for (CveIntelligence.Article article : originalArticles) {
            String category = urlToCategory.getOrDefault(article.getUrl(), "other");
            CveIntelligence.Article newArticle = new CveIntelligence.Article(
                article.getTitle(),
                article.getUrl(),
                article.getSource(),
                article.getSummary(),
                category
            );
            classified.add(newArticle);
        }

        return classified;
    }
}

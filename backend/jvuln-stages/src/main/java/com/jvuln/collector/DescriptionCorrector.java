package com.jvuln.collector;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmPromptStage;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import com.jvuln.store.model.CveIntelligence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 从技术分析文章中提取内容，使用LLM纠正CVE描述
 */
@Component
public class DescriptionCorrector {

    private static final Logger log = LoggerFactory.getLogger(DescriptionCorrector.class);

    private final LlmClient llmClient;
    private final PromptRegistry promptRegistry;

    // 每篇文章最多提取的字符数
    private static final int MAX_CHARS_PER_ARTICLE = 3000;

    // 最多分析的文章数量
    private static final int MAX_ARTICLES_TO_FETCH = 3;

    // 总tokens预算（约8000字符）
    private static final int TOTAL_CONTEXT_BUDGET = 8000;

    public DescriptionCorrector(LlmClient llmClient, PromptRegistry promptRegistry) {
        this.llmClient = llmClient;
        this.promptRegistry = promptRegistry;
    }

    /**
     * 基于技术分析文章纠正CVE描述
     *
     * @param cveId CVE编号
     * @param officialDescription 官方描述（可能不准确）
     * @param articles 所有参考文章（已分类）
     * @return 纠正后的描述，如果纠错失败则返回原描述
     */
    public String correctDescription(String cveId,
                                     String officialDescription,
                                     List<CveIntelligence.Article> articles) {

        if (officialDescription == null || officialDescription.trim().isEmpty()) {
            log.warn("DescriptionCorrector: Empty official description for {}", cveId);
            return officialDescription;
        }

        if (articles == null || articles.isEmpty()) {
            log.debug("DescriptionCorrector: No articles available for {}", cveId);
            return officialDescription;
        }

        // 1. 筛选技术分析类文章（优先级最高）
        List<CveIntelligence.Article> analysisArticles = articles.stream()
            .filter(a -> "analysis".equals(a.getCategory()))
            .limit(MAX_ARTICLES_TO_FETCH)
            .collect(Collectors.toList());

        if (analysisArticles.isEmpty()) {
            log.info("DescriptionCorrector: No analysis articles found for {}", cveId);
            return officialDescription;
        }

        // 2. 提取文章内容
        List<ArticleContent> contents = fetchArticleContents(analysisArticles);

        if (contents.isEmpty()) {
            log.warn("DescriptionCorrector: Failed to fetch any article content for {}", cveId);
            return officialDescription;
        }

        // 3. LLM纠错
        try {
            log.info("DescriptionCorrector: Correcting description for {} with {} articles",
                cveId, contents.size());
            String corrected = correctWithLlm(cveId, officialDescription, contents);

            // 4. 验证纠正结果
            if (corrected != null && !corrected.trim().isEmpty()
                && !corrected.equals(officialDescription)) {
                log.info("DescriptionCorrector: Description corrected for {}", cveId);
                return corrected;
            } else {
                log.info("DescriptionCorrector: No correction needed for {}", cveId);
                return officialDescription;
            }
        } catch (Exception e) {
            log.warn("DescriptionCorrector: LLM correction failed for {}: {}", cveId, e.getMessage());
            return officialDescription;
        }
    }

    /**
     * 批量提取文章内容
     */
    private List<ArticleContent> fetchArticleContents(List<CveIntelligence.Article> articles) {
        List<ArticleContent> contents = new ArrayList<>();

        for (CveIntelligence.Article article : articles) {
            try {
                ArticleContent content = fetchSingleArticle(article.getUrl(), article.getTitle());
                if (content != null && !content.text.trim().isEmpty()) {
                    contents.add(content);
                }
            } catch (Exception e) {
                log.debug("DescriptionCorrector: Failed to fetch {}: {}",
                    article.getUrl(), e.getMessage());
                // 继续处理其他文章
            }
        }

        return contents;
    }

    /**
     * 提取单篇文章的纯文本内容
     */
    private ArticleContent fetchSingleArticle(String url, String title) throws Exception {
        // 超时和重试设置
        Document doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .timeout(10000)
            .followRedirects(true)
            .get();

        // 移除无用标签
        doc.select("script, style, nav, footer, header, aside, " +
                  "iframe, video, audio, canvas, svg, " +
                  "noscript, form, button, input").remove();

        // 移除评论区、广告等（常见class名）
        doc.select(".comments, .comment, .ad, .advertisement, " +
                  ".sidebar, .footer, .header, .nav, .menu").remove();

        // 提取纯文本
        String fullText = doc.body().text();

        // 智能截断：保留前N个字符
        String excerpt = fullText.length() > MAX_CHARS_PER_ARTICLE
            ? fullText.substring(0, MAX_CHARS_PER_ARTICLE) + "..."
            : fullText;

        // 清理多余空白
        excerpt = excerpt.replaceAll("\\s+", " ").trim();

        log.debug("DescriptionCorrector: Fetched {} chars from {}", excerpt.length(), url);

        return new ArticleContent(url, title, excerpt);
    }

    /**
     * 使用LLM纠正描述
     */
    private String correctWithLlm(String cveId, String officialDescription,
                                   List<ArticleContent> contents) throws Exception {

        String taskPrompt = promptRegistry.getPrompt("current/intelligence-description-corrector");
        String userPrompt = buildUserPrompt(cveId, officialDescription, contents);

        LlmRequest request = new LlmRequest(
            LlmPromptStage.INTELLIGENCE,
            taskPrompt,
            Collections.singletonList(LlmRequest.Message.user(userPrompt)),
            0.1,  // 低temperature，确保准确性
            2048,
            false
        );

        LlmResponse response = llmClient.chat(request);
        return response.getContent().trim();
    }


    private String buildUserPrompt(String cveId, String officialDescription,
                                    List<ArticleContent> contents) {
        StringBuilder sb = new StringBuilder();
        sb.append("CVE ID: ").append(cveId).append("\n\n");
        sb.append("Official Description:\n");
        sb.append(officialDescription).append("\n\n");

        sb.append("Technical Analysis Articles (").append(contents.size()).append("):\n\n");

        int remainingBudget = TOTAL_CONTEXT_BUDGET - officialDescription.length() - 200;

        for (int i = 0; i < contents.size(); i++) {
            ArticleContent content = contents.get(i);

            // 动态分配每篇文章的预算
            int budgetForThis = remainingBudget / (contents.size() - i);
            String excerpt = content.text.length() > budgetForThis
                ? content.text.substring(0, budgetForThis) + "..."
                : content.text;

            sb.append("--- Article ").append(i + 1);
            if (content.title != null && !content.title.isEmpty()) {
                sb.append(": ").append(content.title);
            }
            sb.append(" ---\n");
            sb.append("URL: ").append(content.url).append("\n");
            sb.append(excerpt).append("\n\n");

            remainingBudget -= excerpt.length();
            if (remainingBudget <= 0) break;
        }

        sb.append("Based on the technical articles above, output the correct CVE description:");

        return sb.toString();
    }

    /**
     * 文章内容数据类
     */
    private static class ArticleContent {
        final String url;
        final String title;
        final String text;

        ArticleContent(String url, String title, String text) {
            this.url = url;
            this.title = title;
            this.text = text;
        }
    }
}

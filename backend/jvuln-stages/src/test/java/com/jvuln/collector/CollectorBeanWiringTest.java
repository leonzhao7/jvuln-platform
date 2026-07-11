package com.jvuln.collector;

import com.jvuln.llm.LlmClient;
import com.jvuln.llm.LlmRequest;
import com.jvuln.llm.LlmResponse;
import com.jvuln.llm.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CollectorBeanWiringTest {

    @Test
    void springCreatesArticleClassifierWithItsProductionDependencies() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(TestDependencies.class, ArticleClassifier.class);

            context.refresh();

            assertNotNull(context.getBean(ArticleClassifier.class));
        }
    }

    @Configuration
    static class TestDependencies {
        @Bean
        LlmClient llmClient() {
            return new LlmClient() {
                @Override
                public LlmResponse chat(LlmRequest request) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Flux<String> chatStream(LlmRequest request) {
                    return Flux.error(new UnsupportedOperationException());
                }
            };
        }

        @Bean
        PromptRegistry promptRegistry() {
            return new PromptRegistry();
        }
    }
}

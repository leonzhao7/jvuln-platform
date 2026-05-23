package com.jvuln.llm;

import reactor.core.publisher.Flux;

public interface LlmClient {
    LlmResponse chat(LlmRequest request);
    Flux<String> chatStream(LlmRequest request);
}

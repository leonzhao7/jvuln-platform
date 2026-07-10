package com.jvuln.llm;

import reactor.core.publisher.Flux;

public interface LlmProtocolCaller {
    LlmResponse chat(LlmCall call);
    Flux<String> chatStream(LlmCall call);
    String getName();
}

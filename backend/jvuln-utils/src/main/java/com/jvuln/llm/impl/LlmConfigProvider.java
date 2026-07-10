package com.jvuln.llm.impl;

public interface LlmConfigProvider {

    ActiveConfig getActive();

    class ActiveConfig {
        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final String endpoint;

        public ActiveConfig(String baseUrl, String apiKey, String model, String endpoint) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.endpoint = endpoint;
        }

        public String getBaseUrl()      { return baseUrl; }
        public String getApiKey()       { return apiKey; }
        public String getModel()        { return model; }
        public String getEndpoint()     { return endpoint; }
    }
}

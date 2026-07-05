package com.qacopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Strongly-typed view of the {@code rag.*} configuration tree.
 * Everything tunable (provider, models, chunk sizes, top-k, the relevance threshold) lives here
 * so nothing is hardcoded in business logic. The embedding/LLM provider is selected via
 * {@code rag.embedding.provider} / {@code rag.llm.provider}; each provider has its own auth block.
 */
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    @NestedConfigurationProperty
    private Embedding embedding = new Embedding();
    @NestedConfigurationProperty
    private Llm llm = new Llm();
    @NestedConfigurationProperty
    private Mistral mistral = new Mistral();
    @NestedConfigurationProperty
    private Gemini gemini = new Gemini();
    @NestedConfigurationProperty
    private Chunking chunking = new Chunking();
    @NestedConfigurationProperty
    private Retrieval retrieval = new Retrieval();
    @NestedConfigurationProperty
    private Gate gate = new Gate();

    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Mistral getMistral() { return mistral; }
    public void setMistral(Mistral mistral) { this.mistral = mistral; }
    public Gemini getGemini() { return gemini; }
    public void setGemini(Gemini gemini) { this.gemini = gemini; }
    public Chunking getChunking() { return chunking; }
    public void setChunking(Chunking chunking) { this.chunking = chunking; }
    public Retrieval getRetrieval() { return retrieval; }
    public void setRetrieval(Retrieval retrieval) { this.retrieval = retrieval; }
    public Gate getGate() { return gate; }
    public void setGate(Gate gate) { this.gate = gate; }

    public static class Embedding {
        private String provider = "mistral";
        private String model = "mistral-embed";
        private int dimension = 1024;   // must match the vector(N) column in the migration
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
    }

    public static class Llm {
        private String provider = "mistral";
        private String model = "mistral-small-latest";
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    /** Mistral API auth + endpoint (OpenAI-shaped, Bearer-token auth). */
    public static class Mistral {
        private String apiKey;
        private String baseUrl = "https://api.mistral.ai/v1";
        private int embedBatchSize = 100;
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getEmbedBatchSize() { return embedBatchSize; }
        public void setEmbedBatchSize(int embedBatchSize) { this.embedBatchSize = embedBatchSize; }
    }

    /** Kept so the provider can be switched back via config without code changes. */
    public static class Gemini {
        private String apiKey;
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        private int embedBatchSize = 100;
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getEmbedBatchSize() { return embedBatchSize; }
        public void setEmbedBatchSize(int embedBatchSize) { this.embedBatchSize = embedBatchSize; }
    }

    public static class Chunking {
        private int maxChars = 1000;
        private int overlapChars = 150;
        private int minChars = 300;
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
        public int getOverlapChars() { return overlapChars; }
        public void setOverlapChars(int overlapChars) { this.overlapChars = overlapChars; }
        public int getMinChars() { return minChars; }
        public void setMinChars(int minChars) { this.minChars = minChars; }
    }

    public static class Retrieval {
        private int topK = 5;
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }

    public static class Gate {
        /** Cosine-similarity floor. The ONE allowed numeric gate; tune per corpus (see application.yml). */
        private double similarityThreshold = 0.75;
        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }
    }
}

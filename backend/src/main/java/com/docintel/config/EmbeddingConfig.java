package com.docintel.config;

import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Local ONNX embedding model (sentence-transformers/all-MiniLM-L6-v2, 384 dims).
 * Runs fully inside the JVM - no external embedding API is needed.
 * The model files are downloaded and cached on first use.
 */
@Configuration
public class EmbeddingConfig {

    public static final int EMBEDDING_DIMENSION = 384;

    @Bean
    @Lazy
    public TransformersEmbeddingModel embeddingModel() throws Exception {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        // Without an explicit maxLength the tokenizer truncates so aggressively
        // that chunks sharing the same header boilerplate get near-identical
        // embeddings. 512 tokens covers a full 1000-character chunk.
        model.setTokenizerOptions(java.util.Map.of(
                "maxLength", "512",
                "truncation", "true",
                "padding", "true"));
        model.afterPropertiesSet();
        return model;
    }
}

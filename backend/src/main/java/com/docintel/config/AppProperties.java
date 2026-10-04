package com.docintel.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central project settings, the Java counterpart of the Python AppSettings dataclass.
 */
@ConfigurationProperties(prefix = "docintel")
public record AppProperties(
        String dataRoot,
        String claudeModel,
        String llmProvider,
        String ollamaBaseUrl,
        String ollamaModel,
        double retrievalDistanceThreshold,
        int chunkSize,
        int chunkOverlap
) {

    public Path rawDataDir() {
        return Path.of(dataRoot, "raw");
    }

    public Path processedDataDir() {
        return Path.of(dataRoot, "processed");
    }

    public Path outputDataDir() {
        return Path.of(dataRoot, "output");
    }
}

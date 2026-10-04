package com.docintel.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request/response DTOs, the Java counterpart of src/api/schemas.py.
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record RagQuestionRequest(
            @NotBlank String question,
            @JsonProperty("top_k") @Min(1) @Max(5) Integer topK,
            @JsonProperty("use_cache") Boolean useCache
    ) {
        public int topKOrDefault() {
            return topK == null ? 3 : topK;
        }

        public boolean useCacheOrDefault() {
            return useCache == null || useCache;
        }
    }

    public record AgentQuestionRequest(@NotBlank String question) {
    }

    public record PipelineRunResponse(boolean success, String message) {
    }
}

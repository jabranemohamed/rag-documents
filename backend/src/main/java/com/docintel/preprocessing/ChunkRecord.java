package com.docintel.preprocessing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One RAG-ready text chunk with its metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChunkRecord(
        @JsonProperty("document_id") String documentId,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("chunk_index") int chunkIndex,
        @JsonProperty("source_file") String sourceFile,
        @JsonProperty("text") String text,
        @JsonProperty("text_length") int textLength
) {
}

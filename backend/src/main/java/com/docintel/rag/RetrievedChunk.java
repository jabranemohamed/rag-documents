package com.docintel.rag;

/**
 * One vector-search hit with its metadata and cosine distance.
 */
public record RetrievedChunk(
        String chunkId,
        String text,
        String documentId,
        String sourceFile,
        int chunkIndex,
        int textLength,
        double distance
) {
}

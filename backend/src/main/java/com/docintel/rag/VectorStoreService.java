package com.docintel.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.docintel.preprocessing.ChunkRecord;

/**
 * Step 6 + semantic search: embeds chunks with the local ONNX model and stores
 * them in PostgreSQL/pgvector. Cosine distance is computed with the pgvector
 * <=> operator.
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<TransformersEmbeddingModel> embeddingModelProvider;

    public VectorStoreService(JdbcTemplate jdbcTemplate,
                              ObjectProvider<TransformersEmbeddingModel> embeddingModelProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /** Check whether every expected chunk id already exists in the vector store. */
    public boolean vectorStoreHasAllChunks(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return false;
        }
        String placeholders = String.join(",", chunkIds.stream().map(id -> "?").toList());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE chunk_id IN (" + placeholders + ")",
                Integer.class,
                chunkIds.toArray());
        return count != null && count == chunkIds.size();
    }

    /** Create embeddings for loaded chunks and upsert them into pgvector. */
    public boolean buildVectorStore(List<ChunkRecord> chunks) {
        if (chunks.isEmpty()) {
            log.warn("No chunks available for vector store creation");
            return false;
        }

        List<String> ids = chunks.stream().map(ChunkRecord::chunkId).toList();
        if (vectorStoreHasAllChunks(ids)) {
            log.info("Skipping vector store rebuild. {} chunks already indexed.", ids.size());
            return false;
        }

        TransformersEmbeddingModel embeddingModel = embeddingModelProvider.getObject();
        List<String> texts = chunks.stream().map(ChunkRecord::text).toList();
        List<float[]> embeddings = embeddingModel.embed(texts);

        String upsertSql = """
                INSERT INTO document_chunks (chunk_id, document_id, chunk_index, source_file, text_length, chunk_text, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?::vector)
                ON CONFLICT (chunk_id) DO UPDATE SET
                    document_id = EXCLUDED.document_id,
                    chunk_index = EXCLUDED.chunk_index,
                    source_file = EXCLUDED.source_file,
                    text_length = EXCLUDED.text_length,
                    chunk_text = EXCLUDED.chunk_text,
                    embedding = EXCLUDED.embedding
                """;

        for (int i = 0; i < chunks.size(); i++) {
            ChunkRecord chunk = chunks.get(i);
            jdbcTemplate.update(upsertSql,
                    chunk.chunkId(),
                    chunk.documentId(),
                    chunk.chunkIndex(),
                    chunk.sourceFile(),
                    chunk.textLength(),
                    // Defensive: PostgreSQL rejects NUL bytes in text columns.
                    chunk.text().replace("\u0000", ""),
                    toVectorLiteral(embeddings.get(i)));
        }

        log.info("Vector store updated with {} chunks.", chunks.size());
        return true;
    }

    /** Semantic search by cosine distance, optionally filtered by document id. */
    public List<RetrievedChunk> search(String query, int topK, String documentId) {
        TransformersEmbeddingModel embeddingModel = embeddingModelProvider.getObject();
        String queryVector = toVectorLiteral(embeddingModel.embed(query));

        StringBuilder sql = new StringBuilder("""
                SELECT chunk_id, document_id, chunk_index, source_file, text_length, chunk_text,
                       (embedding <=> ?::vector) AS distance
                FROM document_chunks
                """);
        List<Object> params = new ArrayList<>();
        params.add(queryVector);

        if (documentId != null && !documentId.isBlank()) {
            sql.append(" WHERE document_id = ? ");
            params.add(documentId);
        }
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        params.add(queryVector);
        params.add(topK);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RetrievedChunk(
                rs.getString("chunk_id"),
                rs.getString("chunk_text"),
                rs.getString("document_id"),
                rs.getString("source_file"),
                rs.getInt("chunk_index"),
                rs.getInt("text_length"),
                rs.getDouble("distance")
        ), params.toArray());
    }

    /** All indexed documents with their chunk counts (used by the agent). */
    public List<Map<String, Object>> listDocuments() {
        return jdbcTemplate.query("""
                SELECT document_id, COUNT(*) AS chunk_count, MIN(source_file) AS source_file
                FROM document_chunks
                GROUP BY document_id
                ORDER BY document_id
                """, (rs, rowNum) -> toDocumentRow(rs));
    }

    /** One server-side page of indexed documents (used by the Library UI). */
    public Map<String, Object> listDocumentsPage(int page, int size) {
        long totalDocuments = countIndexedDocuments();
        int totalPages = (int) Math.ceil((double) totalDocuments / size);
        int safePage = Math.max(0, Math.min(page, Math.max(totalPages - 1, 0)));

        List<Map<String, Object>> documents = jdbcTemplate.query("""
                SELECT document_id, COUNT(*) AS chunk_count, MIN(source_file) AS source_file
                FROM document_chunks
                GROUP BY document_id
                ORDER BY document_id
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> toDocumentRow(rs), size, safePage * size);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", safePage);
        response.put("page_size", size);
        response.put("total_documents", totalDocuments);
        response.put("total_pages", totalPages);
        response.put("total_chunks", countIndexedChunks());
        response.put("documents", documents);
        return response;
    }

    private Map<String, Object> toDocumentRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("document_id", rs.getString("document_id"));
        document.put("chunk_count", rs.getInt("chunk_count"));
        document.put("source_file", rs.getString("source_file"));
        return document;
    }

    public long countIndexedDocuments() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT document_id) FROM document_chunks", Long.class);
        return count == null ? 0 : count;
    }

    public long countIndexedChunks() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document_chunks", Long.class);
        return count == null ? 0 : count;
    }

    static String toVectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        return builder.append(']').toString();
    }
}

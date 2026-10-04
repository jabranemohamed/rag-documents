package com.docintel.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.docintel.config.AppProperties;
import com.docintel.ingestion.PdfIngestionService;
import com.docintel.preprocessing.ChunkRecord;
import com.docintel.util.JsonFiles;

/**
 * Step 5: load chunk JSON files as the input for embeddings and the vector
 * store, and save a document manifest.
 */
@Service
public class ChunkLoaderService {

    private static final Logger log = LoggerFactory.getLogger(ChunkLoaderService.class);

    private final AppProperties props;

    public ChunkLoaderService(AppProperties props) {
        this.props = props;
    }

    public boolean isValidRagChunk(ChunkRecord chunk) {
        return chunk.documentId() != null
                && chunk.chunkId() != null
                && chunk.sourceFile() != null
                && chunk.text() != null
                && !chunk.text().strip().isEmpty();
    }

    public List<ChunkRecord> loadAllChunks() {
        Path chunksDir = props.processedDataDir().resolve("chunks");
        List<ChunkRecord> allChunks = new ArrayList<>();

        if (!Files.isDirectory(chunksDir)) {
            log.warn("No chunks folder found in {}", chunksDir);
            return allChunks;
        }

        List<Path> chunkFiles = PdfIngestionService.listFiles(chunksDir, "*_chunks.json", false);
        for (Path chunkFile : chunkFiles) {
            for (ChunkRecord chunk : JsonFiles.readList(chunkFile, ChunkRecord.class)) {
                if (isValidRagChunk(chunk)) {
                    allChunks.add(chunk);
                }
            }
        }

        log.info("Loaded {} valid chunks from {} file(s)", allChunks.size(), chunkFiles.size());
        return allChunks;
    }

    public Path saveDocumentManifest(List<ChunkRecord> chunks) {
        if (chunks.isEmpty()) {
            log.warn("No chunks available for manifest creation");
            return null;
        }

        Map<String, Integer> chunkCountsByDocument = new TreeMap<>();
        for (ChunkRecord chunk : chunks) {
            chunkCountsByDocument.merge(chunk.documentId(), 1, Integer::sum);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("total_chunks", chunks.size());
        manifest.put("total_documents", chunkCountsByDocument.size());
        manifest.put("document_ids", new TreeSet<>(chunkCountsByDocument.keySet()));
        manifest.put("chunk_counts_by_document", chunkCountsByDocument);

        Path outputPath = props.processedDataDir().resolve("rag_inputs").resolve("document_manifest.json");
        JsonFiles.write(outputPath, manifest);
        log.info("Document manifest created: {}", outputPath);
        return outputPath;
    }
}

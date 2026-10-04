package com.docintel.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.docintel.config.AppProperties;
import com.docintel.ingestion.PdfIngestionService;
import com.docintel.preprocessing.ChunkRecord;
import com.docintel.preprocessing.TextChunkerService;
import com.docintel.preprocessing.TextCleanerService;
import com.docintel.rag.ChunkLoaderService;
import com.docintel.rag.VectorStoreService;
import com.docintel.util.JsonFiles;

/**
 * Orchestrates the document pipeline: PDFs (text + OCR for images) ->
 * cleaned text -> chunks -> embeddings -> pgvector. Every step is idempotent,
 * so re-running only processes new documents.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final AppProperties props;
    private final PdfIngestionService pdfIngestionService;
    private final TextCleanerService textCleanerService;
    private final TextChunkerService textChunkerService;
    private final ChunkLoaderService chunkLoaderService;
    private final VectorStoreService vectorStoreService;

    public PipelineService(AppProperties props,
                           PdfIngestionService pdfIngestionService,
                           TextCleanerService textCleanerService,
                           TextChunkerService textChunkerService,
                           ChunkLoaderService chunkLoaderService,
                           VectorStoreService vectorStoreService) {
        this.props = props;
        this.pdfIngestionService = pdfIngestionService;
        this.textCleanerService = textCleanerService;
        this.textChunkerService = textChunkerService;
        this.chunkLoaderService = chunkLoaderService;
        this.vectorStoreService = vectorStoreService;
    }

    public synchronized Map<String, Object> runFullPipeline() {
        log.info("Starting PDF ingestion...");
        List<Path> extractedFiles = pdfIngestionService.processPdfs();

        log.info("Cleaning extracted document text...");
        textCleanerService.cleanExtractedDocuments();

        log.info("Creating RAG-ready text chunks...");
        textChunkerService.chunkCleanedDocuments();

        log.info("Validating text chunks...");
        textChunkerService.validateChunkFiles();

        log.info("Loading chunks for RAG...");
        List<ChunkRecord> chunks = chunkLoaderService.loadAllChunks();
        chunkLoaderService.saveDocumentManifest(chunks);

        log.info("Building vector store...");
        boolean vectorStoreUpdated = vectorStoreService.buildVectorStore(chunks);

        Map<String, Integer> chunkCountsByDocument = new TreeMap<>();
        for (ChunkRecord chunk : chunks) {
            chunkCountsByDocument.merge(chunk.documentId(), 1, Integer::sum);
        }

        log.info("Saving processing summary...");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_documents_processed", chunkCountsByDocument.size());
        summary.put("total_chunks_indexed", vectorStoreService.countIndexedChunks());
        summary.put("vector_store_updated", vectorStoreUpdated);
        summary.put("chunk_counts_by_document", chunkCountsByDocument);
        JsonFiles.write(processingSummaryPath(), summary);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents_processed", extractedFiles.size());
        result.put("chunks_loaded", chunks.size());
        result.put("vector_store_updated", vectorStoreUpdated);
        result.put("vector_store_chunks", vectorStoreService.countIndexedChunks());
        return result;
    }

    private Path processingSummaryPath() {
        return props.outputDataDir().resolve("processing_summary.json");
    }

    /** Last processing summary, used by the agent and the status endpoint. */
    public Map<String, Object> loadProcessingSummary() {
        Path summaryPath = processingSummaryPath();
        if (!Files.exists(summaryPath)) {
            return Map.of("error", "processing_summary.json was not found - run the pipeline first");
        }
        return JsonFiles.readMap(summaryPath);
    }

    /** Artifact availability, used by GET /pipeline/status. */
    public Map<String, Object> getOutputFileStatus() {
        Map<String, Path> outputFiles = new LinkedHashMap<>();
        outputFiles.put("rag_answers", props.outputDataDir().resolve("rag_answers.json"));
        outputFiles.put("processing_summary", processingSummaryPath());
        outputFiles.put("document_manifest",
                props.processedDataDir().resolve("rag_inputs").resolve("document_manifest.json"));

        Map<String, Object> status = new LinkedHashMap<>();
        outputFiles.forEach((name, path) -> {
            Map<String, Object> fileStatus = new LinkedHashMap<>();
            fileStatus.put("exists", Files.exists(path));
            fileStatus.put("path", path.toString());
            status.put(name, fileStatus);
        });
        return status;
    }

    public Map<String, Object> getPipelineStatus() {
        Path summaryPath = processingSummaryPath();
        Map<String, Object> summary = Files.exists(summaryPath) ? JsonFiles.readMap(summaryPath) : null;

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("project_root", Path.of(props.dataRoot()).toAbsolutePath().normalize().getParent().toString());
        status.put("output_files", getOutputFileStatus());
        status.put("processing_summary", summary);
        return status;
    }
}

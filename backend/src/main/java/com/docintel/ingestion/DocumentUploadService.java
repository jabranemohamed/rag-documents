package com.docintel.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.docintel.pipeline.PipelineService;
import com.docintel.config.AppProperties;

/**
 * Upload of new PDF documents: the file is stored under data/raw/uploads,
 * then the pipeline runs (text extraction with OCR for image pages, cleaning,
 * chunking, embeddings into pgvector). Every pipeline step is idempotent, so
 * only the newly uploaded document is actually processed.
 */
@Service
public class DocumentUploadService {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadService.class);
    private static final Pattern SAFE_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    private final AppProperties props;
    private final PipelineService pipelineService;

    public DocumentUploadService(AppProperties props, PipelineService pipelineService) {
        this.props = props;
        this.pipelineService = pipelineService;
    }

    /** Resolve a document id back to its source PDF under data/raw. */
    public Path resolveDocumentPdf(String documentId) {
        for (Path pdfPath : PdfIngestionService.listFiles(props.rawDataDir(), "*.pdf", true)) {
            if (PdfIngestionService.documentIdFor(pdfPath).equals(documentId)) {
                return pdfPath;
            }
        }
        return null;
    }

    /** Store one uploaded PDF under data/raw/uploads and run the pipeline. */
    public Map<String, Object> uploadAndProcess(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename() == null ? "document.pdf" : file.getOriginalFilename();
        String sanitizedName = SAFE_CHARS.matcher(originalName).replaceAll("_");

        if (!sanitizedName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported: " + originalName);
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty: " + originalName);
        }

        Path uploadsDir = props.rawDataDir().resolve("uploads");
        Files.createDirectories(uploadsDir);
        Path storedPath = uploadsDir.resolve(sanitizedName);
        file.transferTo(storedPath.toAbsolutePath());

        String documentId = PdfIngestionService.documentIdFor(storedPath);
        log.info("Uploaded document stored at: {} (document id: {})", storedPath, documentId);

        // Idempotent pipeline: only the new document's artifacts are produced.
        Map<String, Object> pipelineResult = pipelineService.runFullPipeline();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("document_id", documentId);
        response.put("stored_file", storedPath.toString());
        response.put("original_filename", originalName);
        response.put("size_bytes", file.getSize());
        response.put("pipeline", pipelineResult);
        return response;
    }
}

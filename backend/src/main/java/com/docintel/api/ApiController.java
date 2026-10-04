package com.docintel.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.docintel.agent.DocumentAgentService;
import com.docintel.api.ApiDtos.AgentQuestionRequest;
import com.docintel.api.ApiDtos.PipelineRunResponse;
import com.docintel.api.ApiDtos.RagQuestionRequest;
import com.docintel.ingestion.DocumentUploadService;
import com.docintel.pipeline.PipelineService;
import com.docintel.rag.RagService;
import com.docintel.rag.VectorStoreService;

import jakarta.validation.Valid;

/**
 * REST layer of the PDF document intelligence service.
 */
@RestController
public class ApiController {

    private final PipelineService pipelineService;
    private final RagService ragService;
    private final DocumentAgentService documentAgentService;
    private final DocumentUploadService documentUploadService;
    private final VectorStoreService vectorStoreService;

    public ApiController(PipelineService pipelineService,
                         RagService ragService,
                         DocumentAgentService documentAgentService,
                         DocumentUploadService documentUploadService,
                         VectorStoreService vectorStoreService) {
        this.pipelineService = pipelineService;
        this.ragService = ragService;
        this.documentAgentService = documentAgentService;
        this.documentUploadService = documentUploadService;
        this.vectorStoreService = vectorStoreService;
    }

    @GetMapping("/health")
    public Map<String, Object> healthCheck() {
        return Map.of("status", "ok", "service", "PDF Document Intelligence API (Java + Claude)");
    }

    @GetMapping("/")
    public Map<String, Object> apiHome() {
        Map<String, Object> home = new LinkedHashMap<>();
        home.put("service", "PDF Document Intelligence API (Java + Claude)");
        home.put("docs", "/swagger-ui.html");
        home.put("health", "/health");
        home.put("pipeline_status", "/pipeline/status");
        return home;
    }

    @GetMapping("/pipeline/status")
    public Map<String, Object> getPipelineStatus() {
        return pipelineService.getPipelineStatus();
    }

    @PostMapping("/pipeline/run")
    public PipelineRunResponse runPipeline() {
        try {
            pipelineService.runFullPipeline();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
        return new PipelineRunResponse(true, "Pipeline completed successfully.");
    }

    @GetMapping("/documents")
    public Map<String, Object> listDocuments(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        return vectorStoreService.listDocumentsPage(Math.max(page, 0), safeSize);
    }

    /** Serve the original PDF: inline for viewing, attachment with ?download=true. */
    @GetMapping("/documents/{documentId}/file")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> getDocumentFile(
            @org.springframework.web.bind.annotation.PathVariable String documentId,
            @RequestParam(name = "download", defaultValue = "false") boolean download) {
        java.nio.file.Path pdfPath = documentUploadService.resolveDocumentPdf(documentId);
        if (pdfPath == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No PDF found for document_id=" + documentId);
        }

        String disposition = (download ? "attachment" : "inline")
                + "; filename=\"" + pdfPath.getFileName() + "\"";

        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(new org.springframework.core.io.FileSystemResource(pdfPath));
    }

    @PostMapping("/documents/upload")
    public Map<String, Object> uploadDocument(@RequestParam("file") MultipartFile file) {
        try {
            return documentUploadService.uploadAndProcess(file);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @PostMapping("/rag/ask")
    public Map<String, Object> askRagQuestion(@Valid @RequestBody RagQuestionRequest request) {
        Map<String, Object> response = ragService.runRagQuestion(
                request.question(), request.topKOrDefault(), request.useCacheOrDefault(), null);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RAG response was not created");
        }
        return response;
    }

    @PostMapping("/agent/ask")
    public Map<String, Object> askAgent(@Valid @RequestBody AgentQuestionRequest request) {
        return documentAgentService.runDocumentAgent(request.question());
    }
}

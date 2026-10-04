package com.docintel.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.docintel.config.AppProperties;
import com.docintel.llm.LlmProvider;
import com.docintel.util.JsonFiles;

/**
 * Steps 8-10: grounded RAG question answering with the configured LLM
 * provider (Claude by default, Ollama in free local mode), plus the JSON
 * answer cache. Mirrors src/rag/qa_pipeline.py.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String RAG_SYSTEM_PROMPT =
            "You are a document intelligence assistant. Answer the user's question "
            + "using only the provided document context. If the answer is not present "
            + "in the context, say that the document context does not contain enough "
            + "information. Keep the answer concise and mention source numbers used.";

    private final AppProperties props;
    private final LlmProvider llmProvider;
    private final VectorStoreService vectorStoreService;

    public RagService(AppProperties props,
                      LlmProvider llmProvider,
                      VectorStoreService vectorStoreService) {
        this.props = props;
        this.llmProvider = llmProvider;
        this.vectorStoreService = vectorStoreService;
    }

    // ---------------------------------------------------------------- cache

    static String normalizeQuestion(String question) {
        return String.join(" ", question.toLowerCase().strip().split("\\s+"));
    }

    private Path ragCachePath() {
        return props.outputDataDir().resolve("rag_answers.json");
    }

    private Map<String, Map<String, Object>> loadRagCache() {
        Path cachePath = ragCachePath();
        Map<String, Map<String, Object>> cache = new LinkedHashMap<>();
        if (!Files.exists(cachePath)) {
            return cache;
        }
        for (Map<String, Object> response : JsonFiles.readMapList(cachePath)) {
            Object question = response.get("question");
            if (question != null) {
                cache.put(normalizeQuestion(question.toString()), response);
            }
        }
        return cache;
    }

    public Map<String, Object> getCachedRagResponse(String question) {
        Map<String, Object> cached = loadRagCache().get(normalizeQuestion(question));
        if (cached != null) {
            log.info("Using cached RAG answer for question: {}", question);
        }
        return cached;
    }

    public Path saveRagResponses(List<Map<String, Object>> ragResponses) {
        if (ragResponses.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Object>> cache = loadRagCache();
        for (Map<String, Object> response : ragResponses) {
            cache.put(normalizeQuestion(String.valueOf(response.get("question"))), response);
        }
        Path outputPath = ragCachePath();
        JsonFiles.write(outputPath, new ArrayList<>(cache.values()));
        log.info("RAG answers saved to: {}", outputPath);
        return outputPath;
    }

    // ------------------------------------------------------------- context

    public List<Map<String, Object>> buildSourceReferences(List<RetrievedChunk> searchResults) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int index = 1; index <= searchResults.size(); index++) {
            RetrievedChunk result = searchResults.get(index - 1);
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("source_number", index);
            source.put("chunk_id", result.chunkId());
            source.put("document_id", result.documentId());
            source.put("source_file", result.sourceFile());
            source.put("chunk_index", result.chunkIndex());
            source.put("cosine_distance", result.distance());
            sources.add(source);
        }
        return sources;
    }

    public String buildContextFromResults(List<RetrievedChunk> searchResults) {
        List<String> contextBlocks = new ArrayList<>();
        for (int index = 1; index <= searchResults.size(); index++) {
            RetrievedChunk result = searchResults.get(index - 1);
            contextBlocks.add(String.join("\n", List.of(
                    "[Source " + index + "]",
                    "Document ID: " + result.documentId(),
                    "Source file: " + result.sourceFile(),
                    "Chunk index: " + result.chunkIndex(),
                    "Text:",
                    result.text()
            )));
        }
        return String.join("\n\n", contextBlocks);
    }

    public boolean hasRelevantContext(List<RetrievedChunk> searchResults, double maxDistance) {
        return !searchResults.isEmpty() && searchResults.get(0).distance() <= maxDistance;
    }

    private Map<String, Object> buildNoContextResponse(String question, List<RetrievedChunk> searchResults) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("answer", "No relevant document context was found for this question");
        response.put("sources", buildSourceReferences(searchResults));
        return response;
    }

    // ------------------------------------------------------------------ LLM

    private Map<String, Object> answerQuestionWithContext(String question, List<RetrievedChunk> searchResults) {
        if (searchResults.isEmpty()) {
            log.warn("No retrieved chunks available for RAG answer generation.");
            return null;
        }
        if (!llmProvider.isConfigured()) {
            log.warn("LLM provider '{}' is not configured. Skipping RAG answer generation.",
                    llmProvider.name());
            return null;
        }

        String context = buildContextFromResults(searchResults);
        String userMessage = "Question: " + question + "\n\nDocument context:\n" + context;
        String answer = llmProvider.complete(RAG_SYSTEM_PROMPT, userMessage);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", question);
        response.put("answer", answer);
        response.put("sources", buildSourceReferences(searchResults));
        return response;
    }

    // ------------------------------------------------------------ main flow

    public Map<String, Object> runRagQuestion(String question, int topK, boolean useCache, String documentId) {
        if (useCache) {
            Map<String, Object> cachedResponse = getCachedRagResponse(question);
            if (cachedResponse != null) {
                return cachedResponse;
            }
        }

        List<RetrievedChunk> searchResults = vectorStoreService.search(question, topK, documentId);

        if (!hasRelevantContext(searchResults, props.retrievalDistanceThreshold())) {
            log.info("Skipping LLM call because retrieved context is above the distance threshold: {}",
                    props.retrievalDistanceThreshold());
            return buildNoContextResponse(question, searchResults);
        }

        Map<String, Object> response = answerQuestionWithContext(question, searchResults);
        if (response != null && useCache) {
            saveRagResponses(List.of(response));
        }
        return response;
    }
}

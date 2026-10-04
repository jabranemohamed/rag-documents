package com.docintel.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.docintel.config.AppProperties;

/**
 * Free local provider backed by Ollama (http://localhost:11434). JSON
 * completions use Ollama's "format" parameter, which constrains decoding to
 * the given JSON schema.
 */
@Component
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    private final AppProperties props;
    private final RestClient restClient;

    public OllamaProvider(AppProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        // Local generation over a long claim document can take a while.
        requestFactory.setReadTimeout(600_000);
        this.restClient = RestClient.builder()
                .baseUrl(props.ollamaBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public boolean isConfigured() {
        try {
            restClient.get().uri("/api/tags").retrieve().toBodilessEntity();
            return true;
        } catch (Exception ex) {
            log.warn("Ollama is not reachable at {}: {}", props.ollamaBaseUrl(), ex.getMessage());
            return false;
        }
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, null);
    }

    @Override
    public String completeJson(String systemPrompt, String userMessage, Map<String, Object> jsonSchema) {
        return chat(systemPrompt, userMessage, jsonSchema);
    }

    @SuppressWarnings("unchecked")
    private String chat(String systemPrompt, String userMessage, Map<String, Object> format) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.ollamaModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)));
        body.put("stream", false);
        // num_ctx must cover the full claim document; Ollama's default (4096) is too small.
        body.put("options", Map.of("temperature", 0, "num_ctx", 16384));
        if (format != null) {
            body.put("format", format);
        }

        // Read as String and parse manually: Ollama error responses sometimes
        // arrive without a JSON content type, which breaks typed extraction.
        String rawResponse = restClient.post()
                .uri("/api/chat")
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            Map<String, Object> response = com.docintel.util.JsonFiles.MAPPER.readValue(
                    rawResponse, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            if (!(response.get("message") instanceof Map<?, ?> message)) {
                throw new IllegalStateException("Unexpected Ollama response: " + rawResponse);
            }
            return String.valueOf(message.get("content")).strip();
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalStateException("Invalid Ollama response: " + rawResponse, ex);
        }
    }
}

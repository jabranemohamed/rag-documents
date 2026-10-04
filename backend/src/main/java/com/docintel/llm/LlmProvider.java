package com.docintel.llm;

import java.util.Map;

/**
 * Abstraction over the chat LLM so the pipeline can run either on Claude
 * (default, best quality) or on a free local model served by Ollama.
 * Selected via the LLM_PROVIDER environment variable ("claude" | "ollama").
 */
public interface LlmProvider {

    /** Provider key: "claude" or "ollama". */
    String name();

    /** Whether the provider is ready to serve requests (API key present / server reachable). */
    boolean isConfigured();

    /** Plain text completion. */
    String complete(String systemPrompt, String userMessage);

    /**
     * JSON completion constrained by the given JSON schema. Returns the raw
     * JSON string (guaranteed schema-valid on Claude structured outputs;
     * schema-guided on Ollama via its "format" parameter).
     */
    String completeJson(String systemPrompt, String userMessage, Map<String, Object> jsonSchema);
}

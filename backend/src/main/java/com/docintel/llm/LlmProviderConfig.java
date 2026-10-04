package com.docintel.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.docintel.config.AppProperties;

/**
 * Selects the active LLM provider from configuration
 * (LLM_PROVIDER env var: "claude" by default, or "ollama" for the free local mode).
 */
@Configuration
public class LlmProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderConfig.class);

    @Bean
    @Primary
    public LlmProvider activeLlmProvider(AppProperties props,
                                         ClaudeProvider claudeProvider,
                                         OllamaProvider ollamaProvider) {
        LlmProvider selected = "ollama".equalsIgnoreCase(props.llmProvider())
                ? ollamaProvider
                : claudeProvider;
        log.info("Active LLM provider: {} (model: {})",
                selected.name(),
                selected.name().equals("ollama") ? props.ollamaModel() : props.claudeModel());
        return selected;
    }
}

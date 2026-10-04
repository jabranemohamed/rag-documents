package com.docintel.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the official Anthropic SDK client. The API key is resolved from the
 * ANTHROPIC_API_KEY environment variable. When no key is configured the bean
 * is still created lazily; LLM-dependent services check {@link #hasApiKey()}
 * before calling Claude, mirroring the Python project's graceful degradation.
 */
@Configuration
public class ClaudeConfig {

    public static boolean hasApiKey() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key != null && !key.isBlank();
    }

    @Bean
    public AnthropicClient anthropicClient() {
        if (hasApiKey()) {
            var builder = AnthropicOkHttpClient.builder().fromEnv();
            // A user-scoped key (sk-ant-usr-...) is not bound to a workspace:
            // the API then requires the anthropic-workspace-id header.
            String workspaceId = System.getenv("ANTHROPIC_WORKSPACE_ID");
            if (workspaceId != null && !workspaceId.isBlank()) {
                builder.putHeader("anthropic-workspace-id", workspaceId);
            }
            return builder.build();
        }
        // Placeholder client so the application can boot without a key;
        // services guard every call with hasApiKey().
        return AnthropicOkHttpClient.builder().apiKey("missing-api-key").build();
    }
}

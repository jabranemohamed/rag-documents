package com.docintel.llm;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.docintel.config.AppProperties;
import com.docintel.config.ClaudeConfig;

/**
 * Claude via the official Anthropic SDK. JSON completions use structured
 * outputs, so responses are guaranteed to be valid JSON matching the schema.
 */
@Component
public class ClaudeProvider implements LlmProvider {

    private final AppProperties props;
    private final AnthropicClient anthropicClient;

    public ClaudeProvider(AppProperties props, AnthropicClient anthropicClient) {
        this.props = props;
        this.anthropicClient = anthropicClient;
    }

    @Override
    public String name() {
        return "claude";
    }

    @Override
    public boolean isConfigured() {
        return ClaudeConfig.hasApiKey();
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(props.claudeModel())
                .maxTokens(16000L)
                .system(systemPrompt)
                .addUserMessage(userMessage)
                .build();
        return extractText(anthropicClient.messages().create(params));
    }

    @Override
    public String completeJson(String systemPrompt, String userMessage, Map<String, Object> jsonSchema) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(props.claudeModel())
                .maxTokens(16000L)
                .system(systemPrompt)
                .outputConfig(OutputConfig.builder()
                        .format(JsonOutputFormat.builder()
                                .schema(JsonValue.from(jsonSchema))
                                .build())
                        .build())
                .addUserMessage(userMessage)
                .build();
        return extractText(anthropicClient.messages().create(params));
    }

    /** Collect text blocks from a Claude response, handling safety refusals gracefully. */
    public static String extractText(Message message) {
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);

        if (message.stopReason().equals(StopReason.REFUSAL) && text.isBlank()) {
            return "The model declined to answer this request.";
        }
        return text.strip();
    }
}

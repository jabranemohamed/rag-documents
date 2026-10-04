package com.docintel.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.docintel.config.AppProperties;
import com.docintel.llm.LlmProvider;
import com.docintel.pipeline.PipelineService;
import com.docintel.rag.RagService;
import com.docintel.rag.VectorStoreService;
import com.docintel.util.JsonFiles;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Document agent.
 *
 * With the Claude provider, tool routing uses the API's native tool use (the
 * SDK BetaToolRunner drives the loop). With the free local Ollama provider,
 * the agent falls back to a two-step flow: schema-constrained JSON tool
 * selection, Java-side execution, then a final answer generation call.
 * All tools are read-only; destructive requests are blocked before any LLM call.
 */
@Service
public class DocumentAgentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAgentService.class);

    static final Set<String> UNSAFE_REQUEST_KEYWORDS = Set.of(
            "delete", "drop", "remove", "update", "insert", "overwrite", "truncate", "modify");

    static final Set<String> ALLOWED_TOOLS = Set.of(
            "list_documents", "summarize_pipeline_outputs", "ask_documents");

    private static final String AGENT_SYSTEM_PROMPT = """
            You are a document intelligence assistant over a library of PDF documents.
            Choose exactly one of the provided read-only tools to answer each user request,
            then write a final answer grounded only in the tool result.

            Tool selection guidance:
            - list_documents: which documents are indexed, how many chunks each one has.
            - summarize_pipeline_outputs: processing status, document counts, generated artifacts.
            - ask_documents: any question whose answer must come from the content of the
              documents (facts, explanations, summaries, lookups).

            Final answer rules:
            - Do not invent facts that are not present in the tool result.
            - If the result is empty, say that no matching information was found.
            - Keep the answer concise and mention document ids when they are available.
            - All tools are read-only; never claim to have modified anything.
            """;

    /** Per-request context handed to tool instances created by the tool runner. */
    record AgentContext(VectorStoreService vectorStoreService,
                        PipelineService pipelineService,
                        RagService ragService,
                        String userRequest,
                        List<Map<String, Object>> invocations) {
    }

    static final ThreadLocal<AgentContext> CONTEXT = new ThreadLocal<>();

    private final AppProperties props;
    private final AnthropicClient anthropicClient;
    private final LlmProvider llmProvider;
    private final VectorStoreService vectorStoreService;
    private final PipelineService pipelineService;
    private final RagService ragService;

    public DocumentAgentService(AppProperties props,
                                AnthropicClient anthropicClient,
                                LlmProvider llmProvider,
                                VectorStoreService vectorStoreService,
                                PipelineService pipelineService,
                                RagService ragService) {
        this.props = props;
        this.anthropicClient = anthropicClient;
        this.llmProvider = llmProvider;
        this.vectorStoreService = vectorStoreService;
        this.pipelineService = pipelineService;
        this.ragService = ragService;
    }

    // ------------------------------------------------------------ guardrails

    public boolean isUnsafeRequest(String userRequest) {
        String normalized = userRequest.toLowerCase();
        return UNSAFE_REQUEST_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private Map<String, Object> buildBlockedResponse(String userRequest) {
        String finalAnswer = "I can only read and analyze the indexed documents. "
                + "I cannot modify, delete, overwrite, or insert anything.";

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("tool_name", "blocked_request");
        decision.put("arguments", Map.of());
        decision.put("reason", "The request attempted a write or destructive action.");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user_request", userRequest);
        response.put("tool_decision", decision);
        response.put("tool_used", "blocked_request");
        response.put("final_answer", finalAnswer);
        response.put("result", Map.of("error", finalAnswer));
        return response;
    }

    // ------------------------------------------------------------- main flow

    public Map<String, Object> runDocumentAgent(String userRequest) {
        if (isUnsafeRequest(userRequest)) {
            return buildBlockedResponse(userRequest);
        }

        if (!llmProvider.isConfigured()) {
            return buildNoProviderResponse(userRequest);
        }

        return "claude".equals(llmProvider.name())
                ? runClaudeToolUseAgent(userRequest)
                : runGenericAgent(userRequest);
    }

    private Map<String, Object> buildNoProviderResponse(String userRequest) {
        String message = "The LLM provider '" + llmProvider.name()
                + "' is not configured (missing API key or unreachable server), so the agent cannot answer.";

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("tool_name", "ask_documents");
        decision.put("arguments", Map.of("question", userRequest));
        decision.put("reason", "LLM provider unavailable, so no tool selection was possible.");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user_request", userRequest);
        response.put("tool_decision", decision);
        response.put("tool_used", "ask_documents");
        response.put("final_answer", message);
        response.put("result", Map.of("error", message));
        return response;
    }

    // --------------------------------------------- Claude: native tool use

    private Map<String, Object> runClaudeToolUseAgent(String userRequest) {
        AgentContext context = new AgentContext(
                vectorStoreService, pipelineService, ragService, userRequest, new ArrayList<>());
        CONTEXT.set(context);
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(props.claudeModel())
                    .maxTokens(16000L)
                    .putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13")
                    .system(AGENT_SYSTEM_PROMPT)
                    .addTool(ListDocuments.class)
                    .addTool(SummarizePipelineOutputs.class)
                    .addTool(AskDocuments.class)
                    .addUserMessage(userRequest)
                    .build();

            BetaToolRunner toolRunner = anthropicClient.beta().messages().toolRunner(params);

            BetaMessage lastMessage = null;
            for (BetaMessage message : toolRunner) {
                lastMessage = message;
            }

            String finalAnswer = lastMessage == null ? "" : extractBetaText(lastMessage);

            Map<String, Object> lastInvocation = context.invocations().isEmpty()
                    ? null
                    : context.invocations().get(context.invocations().size() - 1);

            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("tool_name", lastInvocation == null ? "direct_answer" : lastInvocation.get("tool_name"));
            decision.put("arguments", lastInvocation == null ? Map.of() : lastInvocation.get("arguments"));
            decision.put("reason", "Selected by Claude native tool use.");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("user_request", userRequest);
            response.put("tool_decision", decision);
            response.put("tool_used", decision.get("tool_name"));
            response.put("final_answer", finalAnswer);
            response.put("result", lastInvocation == null ? null : lastInvocation.get("result"));
            response.put("tool_invocations", context.invocations());
            return response;
        } finally {
            CONTEXT.remove();
        }
    }

    private static String extractBetaText(BetaMessage message) {
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right)
                .strip();
    }

    // ------------------------------ generic provider (Ollama): two-step flow

    private Map<String, Object> toolDecisionSchema() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("type", "object");
        arguments.put("properties", Map.of(
                "question", Map.of("type", List.of("string", "null"))));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "tool_name", Map.of("type", "string", "enum", new ArrayList<>(ALLOWED_TOOLS)),
                "arguments", arguments,
                "reason", Map.of("type", "string")));
        schema.put("required", List.of("tool_name", "arguments", "reason"));
        return schema;
    }

    private String buildToolSelectionUserMessage(String userRequest) {
        return """
                Choose exactly one tool for the user request below.
                For ask_documents, arguments must contain "question".

                User request:
                %s""".formatted(userRequest).strip();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runGenericAgent(String userRequest) {
        // Step 1: schema-constrained tool selection.
        Map<String, Object> decision;
        try {
            String decisionJson = llmProvider.completeJson(
                    AGENT_SYSTEM_PROMPT, buildToolSelectionUserMessage(userRequest), toolDecisionSchema());
            decision = JsonFiles.MAPPER.readValue(decisionJson, Map.class);
        } catch (Exception ex) {
            log.warn("Tool selection failed, falling back to RAG: {}", ex.getMessage());
            decision = new LinkedHashMap<>();
            decision.put("tool_name", "ask_documents");
            decision.put("arguments", Map.of("question", userRequest));
            decision.put("reason", "Fallback used because the model returned an invalid decision.");
        }

        String toolName = String.valueOf(decision.get("tool_name"));
        Map<String, Object> arguments = decision.get("arguments") instanceof Map<?, ?> rawArgs
                ? new LinkedHashMap<>((Map<String, Object>) rawArgs)
                : new LinkedHashMap<>();
        if (!ALLOWED_TOOLS.contains(toolName)) {
            toolName = "ask_documents";
            arguments = new LinkedHashMap<>(Map.of("question", userRequest));
        }

        // Step 2: execute the tool in Java (read-only).
        Object result = executeTool(toolName, arguments, userRequest);

        // Step 3: turn the raw tool result into a final answer.
        String finalAnswer = generateFinalAnswer(userRequest, toolName, result);

        Map<String, Object> toolDecision = new LinkedHashMap<>();
        toolDecision.put("tool_name", toolName);
        toolDecision.put("arguments", arguments);
        toolDecision.put("reason", String.valueOf(decision.getOrDefault("reason", "Selected by LLM tool router.")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user_request", userRequest);
        response.put("tool_decision", toolDecision);
        response.put("tool_used", toolName);
        response.put("final_answer", finalAnswer);
        response.put("result", result);
        return response;
    }

    private Object executeTool(String toolName, Map<String, Object> arguments, String userRequest) {
        switch (toolName) {
            case "list_documents":
                return vectorStoreService.listDocuments();
            case "summarize_pipeline_outputs":
                return pipelineService.loadProcessingSummary();
            default: {
                Object question = arguments.getOrDefault("question", userRequest);
                String effectiveQuestion = question == null || "null".equals(String.valueOf(question))
                        ? userRequest
                        : String.valueOf(question);
                arguments.put("question", effectiveQuestion);
                return ragService.runRagQuestion(effectiveQuestion, 3, true, null);
            }
        }
    }

    private String generateFinalAnswer(String userRequest, String toolName, Object result) {
        String resultJson;
        try {
            resultJson = JsonFiles.MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            resultJson = String.valueOf(result);
        }

        String userMessage = """
                Answer the user's request using only the tool result below.

                User request:
                %s

                Tool used:
                %s

                Tool result:
                %s""".formatted(userRequest, toolName, resultJson).strip();

        try {
            return llmProvider.complete(AGENT_SYSTEM_PROMPT, userMessage);
        } catch (Exception ex) {
            log.warn("Final answer generation failed: {}", ex.getMessage());
            return "The tool returned a result but the final answer could not be generated.";
        }
    }

    // ----------------------------------------- Claude tool runner tooling

    private static String recordAndSerialize(String toolName, Object arguments, Object result) {
        AgentContext context = CONTEXT.get();
        if (context != null) {
            Map<String, Object> invocation = new LinkedHashMap<>();
            invocation.put("tool_name", toolName);
            invocation.put("arguments", arguments);
            invocation.put("result", result);
            context.invocations().add(invocation);
        }
        try {
            return JsonFiles.MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize tool result for {}", toolName, ex);
            return "{\"error\": \"Failed to serialize tool result\"}";
        }
    }

    @JsonClassDescription("List the indexed documents with their chunk counts and source files")
    public static class ListDocuments implements Supplier<String> {
        @Override
        public String get() {
            AgentContext context = CONTEXT.get();
            return recordAndSerialize("list_documents", Map.of(),
                    context.vectorStoreService().listDocuments());
        }
    }

    @JsonClassDescription("Summarize the document pipeline outputs: processed document counts, "
            + "indexed chunks and generated artifact files")
    public static class SummarizePipelineOutputs implements Supplier<String> {
        @Override
        public String get() {
            AgentContext context = CONTEXT.get();
            return recordAndSerialize("summarize_pipeline_outputs", Map.of(),
                    context.pipelineService().loadProcessingSummary());
        }
    }

    @JsonClassDescription("Ask a natural-language question against the indexed documents using "
            + "RAG retrieval (returns an answer with source references)")
    public static class AskDocuments implements Supplier<String> {
        @JsonProperty("question")
        @JsonPropertyDescription("The question to answer from the documents")
        public String question;

        @Override
        public String get() {
            AgentContext context = CONTEXT.get();
            String effectiveQuestion = question == null || question.isBlank()
                    ? context.userRequest()
                    : question;
            Object result = context.ragService().runRagQuestion(effectiveQuestion, 3, true, null);
            return recordAndSerialize("ask_documents", Map.of("question", effectiveQuestion), result);
        }
    }
}

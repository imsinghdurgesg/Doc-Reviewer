package com.app.docreview.retrievalcomparison.llm.litellm;

import com.app.docreview.contracts.comparison.ComparisonModelOutput;
import com.app.docreview.contracts.comparison.ComparisonModelRequest;
import com.app.docreview.retrievalcomparison.comparison.prompt.ComparisonPrompt;
import com.app.docreview.retrievalcomparison.comparison.validation.ComparisonOutputValidator;
import com.app.docreview.retrievalcomparison.llm.ComparisonModelGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * LiteLLM-backed comparison. Architecture §9.5, §10.
 *
 * <p>One of only two classes in this service that touch Spring AI. Uses the comparison model alias,
 * which is configured separately from the extraction alias (§10.1) even when both currently resolve
 * to the same model — they are different jobs and will be tuned separately.
 */
@Component
@Slf4j
public class LiteLlmComparisonModelGateway implements ComparisonModelGateway {

    private final ChatClient chatClient;
    private final LiteLlmProperties properties;
    private final com.app.docreview.retrievalcomparison.llm.RuntimeAiConfigProvider aiConfig;
    private final SecureRandom random = new SecureRandom();

    public LiteLlmComparisonModelGateway(
            ChatClient.Builder chatClientBuilder,
            LiteLlmProperties properties,
            com.app.docreview.retrievalcomparison.llm.RuntimeAiConfigProvider aiConfig) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
        this.aiConfig = aiConfig;
    }

    @Override
    public Result compare(ComparisonModelRequest request) {
        String nonce = HexFormat.of().formatHex(randomBytes());
        long started = System.nanoTime();

        // Resolved per call so an Admin change applies without a restart. The provider and key are
        // REQUIRED: see RuntimeAiConfig.requireBaseUrl() for why an absent one must fail here rather
        // than fall through to the client's default endpoint (§35).
        var runtime = aiConfig.current();
        var options = OpenAiChatOptions.builder()
                .model(com.app.docreview.retrievalcomparison.llm.RuntimeAiConfig.pick(
                        runtime.comparisonModel(), properties.models().comparison()))
                // Low temperature (§10.4). Determinism is not achievable; versioned
                // prompts plus saved results are what make a comparison auditable.
                .temperature(0.1)
                .baseUrl(runtime.requireBaseUrl())
                .apiKey(runtime.requireApiKey());

        try {
            var response = chatClient.prompt()
                    .system(ComparisonPrompt.system(nonce, runtime.comparisonInstructions()))
                    .user(ComparisonPrompt.user(request, nonce))
                    .options(options)
                    .call();

            var output = response.entity(ComparisonModelOutput.class);
            var chatResponse = response.chatResponse();
            long latencyMs = (System.nanoTime() - started) / 1_000_000;

            int promptTokens = 0;
            int completionTokens = 0;
            if (chatResponse != null && chatResponse.getMetadata() != null
                    && chatResponse.getMetadata().getUsage() != null) {
                var usage = chatResponse.getMetadata().getUsage();
                promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            }

            // §10.4 requires the alias, token usage, latency, and outcome to be recorded for every
            // call. IDs, enums, and counts only — §10.5 forbids logging prompts or document bodies,
            // and the reason text quotes the standard verbatim.
            //
            // At INFO rather than DEBUG: this is the per-call audit trail for model spend, and it is
            // the first thing needed when a comparison run costs more or takes longer than expected.
            log.info("Compared {} via alias '{}' -> {} ({} prompt / {} completion tokens, {} ms)",
                    request.requirement().requirementId(), properties.models().comparison(),
                    output == null ? "UNPARSEABLE" : output.status(),
                    promptTokens, completionTokens, latencyMs);

            if (output == null) {
                // Distinguishes "the proxy answered but the payload did not bind" from the exception
                // path below. Without this the two are indistinguishable in a log, and they have
                // different fixes: a schema/prompt problem versus a transport or routing one.
                log.warn("Comparison of {} returned a response that bound to no output. "
                                + "Check that alias '{}' routes to a chat model returning JSON.",
                        request.requirement().requirementId(), properties.models().comparison());
            }

            // The version covers the administered guidance too (§19.2): without it two comparisons
            // record COMPARE-V1 having been given different instructions.
            return new Result(output, properties.models().comparison(),
                    com.app.docreview.retrievalcomparison.llm.PromptVersions.forGuidance(
                            ComparisonPrompt.PROMPT_VERSION, runtime.comparisonInstructions()),
                    ComparisonOutputValidator.SCHEMA_VERSION,
                    promptTokens, completionTokens, latencyMs, output != null, List.of());

        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            // Not a crash: §18.4 requires one retry, then COMPARISON_FAILED for this requirement
            // while the others continue. One bad response must not discard a hundred good findings.
            //
            // Alias, exception type, and cause chain — never the response body (§10.5). The cause
            // chain is what makes a proxy problem diagnosable: the OpenAI client surfaces envelope
            // faults ("`choices` is not set") below the top-level message, so logging only
            // getMessage() names a symptom and hides which layer actually failed.
            log.warn("Comparison failed for {} via alias '{}' after {} ms: {}: {}{}",
                    request.requirement().requirementId(), properties.models().comparison(),
                    latencyMs, e.getClass().getSimpleName(), e.getMessage(),
                    LiteLlmRequirementExtractionModelGateway.causeChain(e));
            return new Result(null, properties.models().comparison(),
                    com.app.docreview.retrievalcomparison.llm.PromptVersions.forGuidance(
                            ComparisonPrompt.PROMPT_VERSION, runtime.comparisonInstructions()),
                    ComparisonOutputValidator.SCHEMA_VERSION,
                    0, 0, latencyMs, false,
                    List.of("model response could not be parsed: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return bytes;
    }
}

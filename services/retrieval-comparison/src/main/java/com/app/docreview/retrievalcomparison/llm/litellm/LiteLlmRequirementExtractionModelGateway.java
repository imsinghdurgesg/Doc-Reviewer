package com.app.docreview.retrievalcomparison.llm.litellm;

import com.app.docreview.contracts.evidence.EvidenceSpan;
import com.app.docreview.contracts.extraction.RequirementCandidate;
import com.app.docreview.contracts.requirement.ExtractedRequirement;
import com.app.docreview.retrievalcomparison.extraction.prompt.ExtractionPrompt;
import com.app.docreview.retrievalcomparison.extraction.validation.ExtractionValidator;
import com.app.docreview.retrievalcomparison.llm.RequirementExtractionModelGateway;
import com.app.docreview.retrievalcomparison.spans.EvidenceSpanReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * LiteLLM-backed requirement extraction. Architecture §9.4, §10.
 *
 * <p><strong>The only class in this service that touches Spring AI.</strong> §10.3 requires domain
 * code to depend on the gateway interface, so the provider can change without reaching into
 * extraction logic.
 *
 * <p>One call per candidate group, never per document (§12.4). A whole-document prompt both exceeds
 * context limits on a real standard and invites the model to invent requirements from regions it was
 * not asked about — which validation cannot detect, because the spans would still be genuine.
 */
@Component
@Slf4j
public class LiteLlmRequirementExtractionModelGateway implements RequirementExtractionModelGateway {

    private final ChatClient chatClient;
    private final com.app.docreview.retrievalcomparison.llm.RuntimeAiConfigProvider aiConfig;
    private final LiteLlmProperties properties;
    private final EvidenceSpanReader spanReader;
    private final SecureRandom random = new SecureRandom();

    public LiteLlmRequirementExtractionModelGateway(
            ChatClient.Builder chatClientBuilder,
            com.app.docreview.retrievalcomparison.llm.RuntimeAiConfigProvider aiConfig,
            LiteLlmProperties properties,
            EvidenceSpanReader spanReader) {
        this.chatClient = chatClientBuilder.build();
        this.aiConfig = aiConfig;
        this.properties = properties;
        this.spanReader = spanReader;
    }

    @Override
    public Result extract(RequirementCandidate candidate, List<String> allowedSpanIds, Context context) {
        List<EvidenceSpan> spans = spanReader.byIds(context.standardVersionId(), allowedSpanIds);
        // A per-request nonce, so document content cannot close the evidence delimiter and have its
        // remaining text read as instructions (§12.4). A fixed delimiter is guessable by whoever
        // authored the standard.
        String nonce = HexFormat.of().formatHex(randomBytes());

        long started = System.nanoTime();
        try {
            var response = chatClient.prompt()
                    // Administered guidance, appended below the protected instructions.
                    .system(ExtractionPrompt.system(nonce, aiConfig.current().extractionInstructions()))
                    .user(ExtractionPrompt.user(candidate, spans, context.standardTitle(), nonce))
                    // options() takes a BUILDER, not a built options object — verified against the
                    // Spring AI 2.0.0 signature `options(B extends ChatOptions.Builder<?>)`.
                    // Per-request so extraction and comparison can use different models while
                    // sharing one proxy and key (§10.3).
                    .options(extractionOptions())
                    .call();

            var payload = response.entity(ExtractionResponse.class);
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

            List<ExtractedRequirement> requirements =
                    payload == null || payload.requirements() == null
                            ? List.of() : payload.requirements();

            // Counts and IDs only. §10.5 forbids logging complete prompts or document bodies, and
            // requirement text IS standard content.
            log.info("Extracted {} requirement(s) from candidate {} ({} prompt / {} completion "
                            + "tokens, {} ms)",
                    requirements.size(), candidate.candidateId(),
                    promptTokens, completionTokens, latencyMs);

            return new Result(requirements,
                    properties.models().requirementExtraction(),
                    com.app.docreview.retrievalcomparison.llm.PromptVersions.forGuidance(
                            ExtractionPrompt.PROMPT_VERSION,
                            aiConfig.current().extractionInstructions()),
                    ExtractionValidator.SCHEMA_VERSION,
                    promptTokens, completionTokens, latencyMs, true, List.of());

        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            // A malformed or unparseable response is NOT a crash: §12.8 allows one retry with the
            // errors, then NEEDS_REVIEW.
            //
            // Logged with the model alias, exception TYPE, and cause chain — never the response body
            // (§10.5 forbids logging document content, and a failed response can echo the prompt).
            // The alias matters because a misrouted alias and a genuinely bad response look
            // identical from the message alone; the cause chain matters because the OpenAI client
            // reports envelope problems ("`choices` is not set") several frames below the surface
            // message, and without it the log names a symptom rather than a cause.
            log.warn("Extraction failed for candidate {} via alias '{}' after {} ms: {}: {}{}",
                    candidate.candidateId(), properties.models().requirementExtraction(),
                    latencyMs, e.getClass().getSimpleName(), e.getMessage(), causeChain(e));
            return new Result(List.of(),
                    properties.models().requirementExtraction(),
                    com.app.docreview.retrievalcomparison.llm.PromptVersions.forGuidance(
                            ExtractionPrompt.PROMPT_VERSION,
                            aiConfig.current().extractionInstructions()),
                    ExtractionValidator.SCHEMA_VERSION,
                    0, 0, latencyMs, false,
                    List.of("model response could not be parsed: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /**
     * Per-call options, with Admin overrides applied.
     *
     * <p>Resolved here rather than at construction so a provider change takes effect without a
     * restart. The provider and key are REQUIRED — see {@code RuntimeAiConfig.requireBaseUrl()} for
     * why an absent one must fail rather than reach the client's default endpoint (§35).
     */
    private OpenAiChatOptions.Builder extractionOptions() {
        var runtime = aiConfig.current();
        return OpenAiChatOptions.builder()
                .model(com.app.docreview.retrievalcomparison.llm.RuntimeAiConfig.pick(
                        runtime.extractionModel(), properties.models().requirementExtraction()))
                // Low temperature for extraction (§10.4). Not determinism — that is not
                // achievable — but less variance between re-extractions.
                .temperature(0.1)
                .baseUrl(runtime.requireBaseUrl())
                .apiKey(runtime.requireApiKey());
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return bytes;
    }

    /**
     * Renders the exception's cause chain as {@code " <- Type: message"} segments.
     *
     * <p>Not a stack trace: §34 keeps traces out of anything that can reach a user, and a trace here
     * would be dozens of framework frames. The cause chain is the useful middle ground — it names
     * WHICH layer failed (HTTP, deserialization, schema binding) in one line.
     *
     * <p>Bounded at four causes so a deeply wrapped framework exception cannot flood the log.
     */
    static String causeChain(Throwable throwable) {
        var sb = new StringBuilder();
        Throwable cause = throwable.getCause();
        int depth = 0;
        while (cause != null && depth < 4) {
            sb.append(" <- ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            cause = cause.getCause();
            depth++;
        }
        return sb.toString();
    }

    /**
     * Mirrors {@code requirement-extraction-v1.json}.
     *
     * <p>A record so Spring AI's structured-output converter can bind it, and so nothing can mutate
     * a response after it has been validated.
     */
    public record ExtractionResponse(List<ExtractedRequirement> requirements) {}
}

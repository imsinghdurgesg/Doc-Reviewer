package com.app.docreview.retrievalcomparison.llm.litellm;

import com.app.docreview.retrievalcomparison.extraction.prompt.DocumentContextPrompt;
import com.app.docreview.retrievalcomparison.llm.DocumentContextModelGateway;
import com.app.docreview.retrievalcomparison.llm.RuntimeAiConfig;
import com.app.docreview.retrievalcomparison.llm.RuntimeAiConfigProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Infers a document's context through the configured provider. Architecture §10.3, §15.1.
 *
 * <p>One call per candidate lookup. Uses the EXTRACTION model alias: this is a reading-and-classifying
 * task like requirement extraction, and it should not be billed to or bounded by whatever model an
 * administrator chose for comparison verdicts.
 *
 * <p><strong>The vocabulary is enforced here, not merely requested in the prompt.</strong> A model that
 * returns a near-synonym would cause the governing standard to be ranked down, so every returned label
 * is matched back against the permitted list and discarded if absent. The prompt asks; this class
 * guarantees.
 */
@Component
@Slf4j
public class LiteLlmDocumentContextModelGateway implements DocumentContextModelGateway {

    /**
     * Characters of the document sent to the model.
     *
     * <p>Enough for a title, purpose, and scope section; short enough that the call stays cheap and
     * that a procedure's many pages of steps do not dilute what the document IS. Matches
     * {@code LiteLlmStandardProfileModelGateway} so both sides of the match see comparable amounts of
     * text.
     */
    private static final int MAX_CHARS = 6000;

    private final ChatClient.Builder chatClient;
    private final LiteLlmProperties properties;
    private final RuntimeAiConfigProvider aiConfig;
    private final SecureRandom random = new SecureRandom();

    public LiteLlmDocumentContextModelGateway(ChatClient.Builder chatClient,
                                              LiteLlmProperties properties,
                                              RuntimeAiConfigProvider aiConfig) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.aiConfig = aiConfig;
    }

    /** JSON the model returns. Field names match the prompt exactly. */
    private record ContextResponse(
            String department,
            String standardFamily,
            String documentType,
            Double confidence) {
    }

    @Override
    public Result infer(String openingText, Vocabulary vocabulary) {
        if (openingText == null || openingText.isBlank() || vocabulary == null) {
            return Result.unavailable();
        }
        if (vocabulary.isEmpty()) {
            // Nothing to match against, so any answer is a guaranteed mismatch penalty. Skipping the
            // call also avoids paying for a request whose every possible output must be discarded.
            log.debug("Skipping document context inference: no candidate labels to choose from");
            return Result.unavailable();
        }

        String text = openingText.length() > MAX_CHARS
                ? openingText.substring(0, MAX_CHARS)
                : openingText;
        String nonce = HexFormat.of().formatHex(randomBytes());

        try {
            var runtime = aiConfig.current();
            String alias = RuntimeAiConfig.pick(
                    runtime.extractionModel(), properties.models().requirementExtraction());

            var payload = chatClient.build().prompt()
                    .system(DocumentContextPrompt.system(nonce))
                    .user(DocumentContextPrompt.user(text, vocabulary.departments(),
                            vocabulary.standardFamilies(), vocabulary.documentTypes(), nonce))
                    .options(OpenAiChatOptions.builder()
                            .model(alias)
                            // Low temperature: re-running a lookup on the same document should not
                            // reclassify it, or the candidate order would shift between page loads.
                            .temperature(0.1)
                            .baseUrl(runtime.requireBaseUrl())
                            .apiKey(runtime.requireApiKey()))
                    .call()
                    .entity(ContextResponse.class);

            if (payload == null) {
                return Result.unavailable();
            }

            // Every label re-checked against the permitted list. A hallucinated near-synonym is worse
            // than null here, so anything unrecognised is dropped rather than passed through. The check
            // lives on Vocabulary, where it is directly testable.
            String department = vocabulary.permittedDepartment(payload.department());
            String family = vocabulary.permittedStandardFamily(payload.standardFamily());
            String documentType = vocabulary.permittedDocumentType(payload.documentType());

            if (dropped(payload.department(), department)
                    || dropped(payload.standardFamily(), family)
                    || dropped(payload.documentType(), documentType)) {
                // Deliberately not logged with the value: it is model output derived from document
                // content (§35). That a drop happened is what signals a drifting prompt.
                log.info("Discarded at least one out-of-vocabulary context label");
            }

            double confidence = clampConfidence(payload.confidence());

            log.info("Inferred document context via alias {}: department={}, family={}, type={} "
                            + "(confidence {})",
                    alias, present(department), present(family), present(documentType), confidence);

            return new Result(department, family, documentType, confidence, alias, true);

        } catch (Exception e) {
            // NEVER fatal. Inference is an optional ranking signal: a comparison must still run with a
            // neutral weight rather than fail over it. Class name only, since an error body can echo
            // document text (§35).
            log.warn("Could not infer document context ({}); ranking without it",
                    e.getClass().getSimpleName());
            return Result.unavailable();
        }
    }

    /** True when the model proposed something and the vocabulary check rejected it. */
    private static boolean dropped(String proposed, String accepted) {
        return proposed != null && !proposed.isBlank() && accepted == null;
    }

    /**
     * Confidence bounded to 0.0–1.0, defaulting to 0.0.
     *
     * <p>An absent or out-of-range value must not become a high confidence: this number decides whether
     * the reviewer is shown settled values or an explicit question, and a fabricated 1.0 would suppress
     * the prompt to check.
     */
    private static double clampConfidence(Double value) {
        if (value == null || value.isNaN()) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Logs whether a dimension was answered, never what it was answered with (§35). */
    private static String present(String value) {
        return value == null ? "none" : "set";
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return bytes;
    }
}

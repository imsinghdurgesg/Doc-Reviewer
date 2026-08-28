package com.app.docreview.retrievalcomparison.llm.litellm;

import com.app.docreview.retrievalcomparison.extraction.prompt.StandardProfilePrompt;
import com.app.docreview.retrievalcomparison.llm.RuntimeAiConfig;
import com.app.docreview.retrievalcomparison.llm.RuntimeAiConfigProvider;
import com.app.docreview.retrievalcomparison.llm.StandardProfileModelGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * Derives a standard's descriptive metadata through the configured provider. Architecture §10.3.
 *
 * <p>One call per standard version at index time. Uses the EXTRACTION model alias rather than the
 * comparison one: this is a reading-and-summarising task like requirement extraction, and it should
 * not be billed to or bounded by whatever model an administrator chose for comparison verdicts.
 */
@Component
@Slf4j
public class LiteLlmStandardProfileModelGateway implements StandardProfileModelGateway {

    /**
     * Characters of the document sent to the model.
     *
     * <p>Enough for a title, purpose, and scope section; short enough that the call stays cheap and
     * that a standard's forty pages of clauses do not dilute what it is ABOUT. The caller passes the
     * opening spans, and this is a second bound in case those are unusually long.
     */
    private static final int MAX_CHARS = 6000;

    private final ChatClient.Builder chatClient;
    private final LiteLlmProperties properties;
    private final RuntimeAiConfigProvider aiConfig;
    private final SecureRandom random = new SecureRandom();

    public LiteLlmStandardProfileModelGateway(ChatClient.Builder chatClient,
                                              LiteLlmProperties properties,
                                              RuntimeAiConfigProvider aiConfig) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.aiConfig = aiConfig;
    }

    /** JSON the model returns. Field names match the prompt exactly. */
    private record ProfileResponse(String description, String category, List<String> tags) {
    }

    @Override
    public Result describe(String title, String openingText) {
        if (openingText == null || openingText.isBlank()) {
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
                    .system(StandardProfilePrompt.system(nonce, runtime.profileInstructions()))
                    .user(StandardProfilePrompt.user(title, text, nonce))
                    .options(OpenAiChatOptions.builder()
                            .model(alias)
                            // Low temperature: re-indexing the same standard should not reclassify it.
                            .temperature(0.1)
                            .baseUrl(runtime.requireBaseUrl())
                            .apiKey(runtime.requireApiKey()))
                    .call()
                    .entity(ProfileResponse.class);

            if (payload == null) {
                return Result.unavailable();
            }

            // Sanitised, not trusted. Tags are lowercased and bounded so one verbose response cannot
            // dominate the profile text that the vector is built from.
            var tags = payload.tags() == null ? List.<String>of() : payload.tags().stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(tag -> tag.trim().toLowerCase(java.util.Locale.ROOT))
                    .distinct()
                    .limit(8)
                    .toList();

            log.info("Derived profile metadata for '{}' via alias {} ({} tags)",
                    title, alias, tags.size());
            return new Result(trimOrNull(payload.description()), trimOrNull(payload.category()),
                    tags, alias, true);

        } catch (Exception e) {
            // NEVER fatal. Metadata is optional: a standard must still index, with a weaker profile,
            // rather than fail over a field that only affects match quality. Class name only, since
            // an error body can echo document text (§35).
            log.warn("Could not derive profile metadata for '{}' ({}); indexing without it",
                    title, e.getClass().getSimpleName());
            return Result.unavailable();
        }
    }

    private static String trimOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return bytes;
    }
}

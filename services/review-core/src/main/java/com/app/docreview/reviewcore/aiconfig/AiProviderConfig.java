package com.app.docreview.reviewcore.aiconfig;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The deployment's model provider settings, set from Admin. Architecture §10, §30.
 *
 * <p><strong>A single document, by design.</strong> One shared configuration rather than one per
 * user: §19.2 records the model alias and prompt version on every comparison so a past result can be
 * explained, and per-user keys would mean two reviewers getting different verdicts on the same
 * document while both stamps claimed the same run was reproducible.
 *
 * <p>No {@code @Data} (§25): this holds an encrypted credential, and a generated {@code toString()}
 * is how secrets reach logs and actuator output.
 */
@Document(collection = "ai_provider_config")
@Getter
public class AiProviderConfig {

    /** Fixed id. The collection holds exactly one row, and a constant id makes that structural. */
    public static final String SINGLETON_ID = "ai-provider-config";

    @Id
    private String id = SINGLETON_ID;

    /** {@code litellm} or {@code openai}. Both speak the OpenAI-compatible API. */
    @Setter private String provider;

    /**
     * Base URL of the OpenAI-compatible server.
     *
     * <p>For LiteLLM this is the proxy ROOT without {@code /v1} — the client appends
     * {@code /v1/chat/completions} itself, so including it produces {@code /v1/v1/...} and a 404 that
     * reads like a routing fault rather than a configuration one.
     */
    @Setter private String baseUrl;

    /**
     * AES-GCM ciphertext of the API key, never the key itself.
     *
     * <p>Read back only by {@code AiProviderConfigService} when building a request. It is never
     * returned to the browser, on any endpoint — the Admin screen shows whether a key is SET, not
     * what it is.
     */
    @Setter private String encryptedApiKey;

    @Setter private String comparisonModel;
    @Setter private String extractionModel;
    @Setter private String embeddingModel;

    /**
     * Administrator-authored comparison guidance.
     *
     * <p>Appended to, never replacing, the protected output contract: §12.8's response schema, the
     * evidence-only rule and the injection guard are applied after this text, so custom guidance
     * cannot remove the constraints that keep findings citable.
     */
    @Setter private String comparisonInstructions;

    /**
     * Administrator-authored guidance for requirement extraction.
     *
     * <p>Same contract as {@code comparisonInstructions}: appended below the protected instructions,
     * which win on conflict. Exists because extraction decides how a standard becomes discrete
     * requirements — whether a clause with three obligations is one requirement or three — and that
     * judgement varies by organization. Without it the only way to influence it was a code change.
     */
    @Setter private String extractionInstructions;

    /**
     * Administrator-authored guidance for the standard profile.
     *
     * <p>Shapes the description, category, and tags derived from a standard, which in turn decide
     * which standard an uploaded document is matched to (§15.1). Useful for imposing a house
     * vocabulary — "use our category names, not your own" — without a fixed list in code.
     */
    @Setter private String profileInstructions;

    @Setter private Instant updatedAt;
    @Setter private String updatedBy;

    /** Whether the last "Save & test" against this configuration succeeded. */
    @Setter private Boolean lastTestOk;
    @Setter private String lastTestMessage;
    @Setter private Instant lastTestedAt;

    protected AiProviderConfig() {
    }

    public static AiProviderConfig empty() {
        var config = new AiProviderConfig();
        config.id = SINGLETON_ID;
        return config;
    }

    public boolean hasApiKey() {
        return encryptedApiKey != null && !encryptedApiKey.isBlank();
    }
}

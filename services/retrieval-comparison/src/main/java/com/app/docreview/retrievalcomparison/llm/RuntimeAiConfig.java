package com.app.docreview.retrievalcomparison.llm;

/**
 * The model settings a single call should use. Architecture §10, §30.
 *
 * <p>Resolved per request rather than injected at boot, because an administrator can change the
 * provider from the Admin screen and the change has to take effect without a restart. Every field is
 * nullable: a blank one means "use what the environment configured", so a deployment that never
 * opens Admin behaves exactly as before.
 *
 * @param baseUrl         provider root WITHOUT {@code /v1}; the client appends it
 * @param apiKey          decrypted for this call only, never logged and never persisted here
 * @param comparisonModel alias for the comparison call
 * @param extractionModel alias for requirement extraction
 * @param embeddingModel  alias for embeddings
 * @param comparisonInstructions administrator guidance, appended to — never replacing — the
 *                        protected output contract (§12.8)
 */
public record RuntimeAiConfig(
        String baseUrl,
        String apiKey,
        String comparisonModel,
        String extractionModel,
        String embeddingModel,
        String comparisonInstructions,

        /**
         * Guidance for requirement extraction and the standard profile (§10.4).
         *
         * <p>Same contract as {@code comparisonInstructions}: appended below the protected
         * instructions of the respective prompt, which win on conflict.
         */
        String extractionInstructions,
        String profileInstructions
) {

    /** Nothing configured through Admin; every call falls back to environment settings. */
    public static RuntimeAiConfig empty() {
        return new RuntimeAiConfig(null, null, null, null, null, null, null, null);
    }

    /** The first non-blank of the override and the fallback. */
    public static String pick(String override, String fallback) {
        return override != null && !override.isBlank() ? override : fallback;
    }

    /**
     * The provider root, or a failure if no administrator has set one. Architecture §10, §35.
     *
     * <p>Deliberately NOT falling back to a built-in default. An absent base URL makes the OpenAI
     * client use its own default endpoint, which would send document text to a third-party host that
     * nobody configured — and report it as a missing-credential error. Refusing is the only safe
     * reading of "no provider is configured".
     */
    public String requireBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AiConfigUnavailableException(
                    "No model provider is configured. Set the server URL under "
                            + "Admin -> AI & matching.");
        }
        return baseUrl;
    }

    /** The provider key, or a failure if none is stored. Never logged (§35). */
    public String requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiConfigUnavailableException(
                    "No provider API key is configured. Add one under Admin -> AI & matching.");
        }
        return apiKey;
    }
}

package com.app.docreview.retrievalcomparison.llm;

/**
 * Thrown when the administered model configuration cannot be resolved. Architecture §9.2, §10, §35.
 *
 * <p><strong>Why this is an exception rather than a fallback.</strong> The provider, key, and model
 * aliases are administered in review-core, so when they cannot be read this service does not know
 * which provider to call. The previous behaviour — falling back to this service's own environment —
 * meant an empty configuration let the OpenAI client use its DEFAULT endpoint, so a misconfigured
 * internal URL quietly aimed document text at a third-party host and reported a credential error.
 *
 * <p>A failed comparison is recoverable: the administrator fixes the configuration and retries.
 * Sending content to an unintended provider is not.
 */
public class AiConfigUnavailableException extends RuntimeException {

    public AiConfigUnavailableException(String message) {
        super(message);
    }

    public AiConfigUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

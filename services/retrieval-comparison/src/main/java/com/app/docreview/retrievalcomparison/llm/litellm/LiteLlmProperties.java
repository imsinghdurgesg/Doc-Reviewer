package com.app.docreview.retrievalcomparison.llm.litellm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * LiteLLM model selection. Architecture §10.1, §10.2.
 *
 * <p>Extraction and comparison are separate properties even when they resolve to the same model
 * today (§10.1): they are different jobs — structured decomposition over a short passage versus
 * judgement over two documents — and will be tuned and evaluated separately.
 *
 * <p>These values are stable ALIASES in a fully configured LiteLLM. Provider names work too, at the
 * cost of making a model change an application release rather than a proxy config change. Either
 * way the name lives only in configuration, never in code.
 *
 * <p>A record with no generated {@code toString()}: configuration objects surface in startup logs
 * and actuator output, and §10.5 forbids logging the proxy key.
 */
@ConfigurationProperties(prefix = "docreview.ai")
public record LiteLlmProperties(
        Duration timeout,
        Models models
) {

    public record Models(
            String requirementExtraction,
            String comparison,
            String embedding,
            String reranker
    ) {}
}

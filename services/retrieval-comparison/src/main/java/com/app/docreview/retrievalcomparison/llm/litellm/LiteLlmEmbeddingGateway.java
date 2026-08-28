package com.app.docreview.retrievalcomparison.llm.litellm;

import com.app.docreview.retrievalcomparison.llm.EmbeddingGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LiteLLM-backed embeddings. Architecture §9.3, §10.
 *
 * <p>Requests are chunked into bounded batches. Providers cap inputs per call and total tokens per
 * request, and those limits differ per model — so a single 500-input call fails on some routes and
 * succeeds on others. A fixed conservative batch is predictable everywhere.
 */
@Component
@Slf4j
public class LiteLlmEmbeddingGateway implements EmbeddingGateway {

    /** Inputs per request. Low enough for common provider caps, high enough to avoid chattiness. */
    private static final int BATCH_SIZE = 64;

    private final EmbeddingModel embeddingModel;
    private final com.app.docreview.retrievalcomparison.llm.RuntimeAiConfigProvider aiConfig;
    private final LiteLlmProperties properties;

    /**
     * Discovered on first use rather than configured.
     *
     * <p>A configured dimension count can disagree with the model's real output, and the mismatch
     * only surfaces when Qdrant rejects an insert — with an error that names neither the model nor
     * the config. Asking the model is the only way to be right.
     */
    private volatile int cachedDimensions = -1;

    public LiteLlmEmbeddingGateway(
            EmbeddingModel embeddingModel,
            LiteLlmProperties properties,
            com.app.docreview.retrievalcomparison.llm.RuntimeAiConfigProvider aiConfig) {
        this.aiConfig = aiConfig;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(inputs.size());

        int batchCount = 0;
        long startedNanos = System.nanoTime();

        for (int start = 0; start < inputs.size(); start += BATCH_SIZE) {
            List<String> batch = inputs.subList(start, Math.min(start + BATCH_SIZE, inputs.size()));
            batchCount++;

            org.springframework.ai.embedding.EmbeddingResponse response;
            try {
                response = embeddingModel.call(new EmbeddingRequest(batch, embeddingOptions()));
            } catch (Exception e) {
                // Rethrown, unlike the chat gateways: an embedding failure has no partial-success
                // story. Missing vectors mean the chunks are not indexed, so retrieval would return
                // nothing for them and a comparison would report MISSING for requirements whose
                // evidence exists but was never searchable — a confidently wrong answer.
                //
                // Logged first with the alias and cause chain, because the most common cause is an
                // alias pointing at a CHAT model rather than an embedding one, and the raw provider
                // error rarely says so.
                log.error("Embedding call failed for batch {} of {} ({} inputs) via alias '{}': "
                                + "{}: {}{}. Verify the alias routes to an embedding model.",
                        batchCount, (inputs.size() + BATCH_SIZE - 1) / BATCH_SIZE, batch.size(),
                        properties.models().embedding(), e.getClass().getSimpleName(),
                        e.getMessage(),
                        LiteLlmRequirementExtractionModelGateway.causeChain(e));
                throw e;
            }

            // Fail loudly on a count mismatch. Silently short results would shift every subsequent
            // vector onto the wrong chunk, and retrieval would keep returning plausible nonsense.
            if (response.getResults().size() != batch.size()) {
                throw new IllegalStateException(
                        "Embedding provider returned %d vectors for %d inputs via alias '%s'"
                                .formatted(response.getResults().size(), batch.size(),
                                        properties.models().embedding()));
            }
            response.getResults().forEach(r -> vectors.add(r.getOutput()));
        }

        if (cachedDimensions < 0 && !vectors.isEmpty()) {
            cachedDimensions = vectors.get(0).length;
            log.info("Embedding model {} produces {} dimensions",
                    properties.models().embedding(), cachedDimensions);
        }
        // §10.4 requires the alias, latency, and outcome recorded per call. Counts only — inputs are
        // confidential document text (§35). At INFO because batch COUNT is the proxy-load figure:
        // it is what shows whether batching is working or a document is issuing one call per chunk.
        log.info("Embedded {} input(s) in {} batch(es) via alias '{}' in {} ms",
                inputs.size(), batchCount, properties.models().embedding(),
                (System.nanoTime() - startedNanos) / 1_000_000);
        return vectors;
    }

    @Override
    public int dimensions() {
        if (cachedDimensions < 0) {
            // One probe call. Cheaper than trusting configuration and discovering the truth when a
            // collection insert fails.
            embed(List.of("dimension probe"));
        }
        return cachedDimensions;
    }

    @Override
    public String modelAlias() {
        return properties.models().embedding();
    }

    /**
     * Per-call options, with Admin overrides applied.
     *
     * <p>Resolved per batch rather than at construction so a provider change takes effect without a
     * restart. Each field falls back to the environment.
     */
    private OpenAiEmbeddingOptions embeddingOptions() {
        var runtime = aiConfig.current();

        // ONLY the model alias is set here.
        //
        // Setting .baseUrl()/.apiKey() on these options compiles and runs but does NOTHING:
        // OpenAiEmbeddingModel holds a final OpenAIClient built when the bean was created and reads
        // the URL and key from its OWN constructor options, never from the request's. Passing them
        // here reads as working configuration while every call still goes wherever the bean was
        // pointed — which is how embedding calls reached api.openai.com while the administered
        // provider was an internal gateway.
        //
        // The provider is therefore bound at startup from spring.ai.openai.*, which the deployment
        // seeds from the administered values. Changing the provider in Admin needs a restart of this
        // service for embeddings; the chat gateways DO honour a per-request override, because
        // OpenAiChatOptions carries its own baseUrl and the chat model applies it.
        return OpenAiEmbeddingOptions.builder()
                .model(com.app.docreview.retrievalcomparison.llm.RuntimeAiConfig.pick(
                        runtime.embeddingModel(), properties.models().embedding()))
                .build();
    }
}

package com.app.docreview.retrievalcomparison.llm;

import java.util.List;

/**
 * Port for embeddings. Architecture §9.3.
 *
 * <p>Batched by design. Indexing a standard produces hundreds of chunks, and one HTTP round trip per
 * chunk turns a seconds-long operation into a minutes-long one — while burning rate limit on
 * request count rather than tokens.
 */
public interface EmbeddingGateway {

    /**
     * @return one vector per input, in the SAME ORDER. Order is the only thing tying a vector back
     *         to its chunk; a reordering implementation would silently attach every embedding to the
     *         wrong passage, and retrieval would still "work".
     */
    List<float[]> embed(List<String> inputs);

    /**
     * Dimensions this gateway's model produces.
     *
     * <p>Needed before a collection is created: Qdrant fixes vector size at creation, so a mismatch
     * is only discovered on first insert.
     */
    int dimensions();

    /** The alias in use, recorded per index for compatibility checks (§13.4). */
    String modelAlias();
}

package com.app.docreview.contracts.retrieval;

import java.time.Instant;

/**
 * What makes an index's vectors comparable. Architecture §13.4.
 *
 * <p>Recorded per collection so incompatible vectors cannot be mixed. The failure this prevents is
 * quiet and severe: embeddings from two different models occupy different spaces, so cosine
 * similarity between them is arithmetically valid and semantically meaningless. Retrieval keeps
 * working and starts returning confident nonsense.
 *
 * <p>§13.4: "Changing the embedding model or dimensions requires a new collection or a full rebuild."
 * A dimension change is at least loud — Qdrant rejects the insert. A model change at the SAME
 * dimensions is silent, which is why the alias is tracked and not only the size.
 */
public record IndexCompatibility(
        String embeddingModelAlias,
        int embeddingDimensions,
        String distanceMetric,
        String chunkingStrategyVersion,
        String indexSchemaVersion,
        Instant indexCreationDate
) {

    /**
     * True when vectors written under {@code other} may be searched together with these.
     *
     * <p>Deliberately strict. Anything less means comparing vectors from different spaces, and there
     * is no partial-compatibility case worth supporting.
     */
    public boolean compatibleWith(IndexCompatibility other) {
        return other != null
                && embeddingModelAlias.equals(other.embeddingModelAlias)
                && embeddingDimensions == other.embeddingDimensions
                && distanceMetric.equals(other.distanceMetric)
                && indexSchemaVersion.equals(other.indexSchemaVersion);
    }
}

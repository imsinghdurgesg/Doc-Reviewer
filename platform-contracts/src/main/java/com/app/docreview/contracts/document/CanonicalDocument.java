package com.app.docreview.contracts.document;

import java.util.List;

/**
 * The parsed form of one immutable document version. Architecture §11.2.
 *
 * <p>Stored as versioned JSON in MinIO with metadata in MongoDB (§11.2): the artifact is large and
 * read whole, so object storage suits it, while queries run against the metadata.
 *
 * <p>{@code contentHash} ties this artifact to the exact source bytes it came from. A canonical
 * document whose hash no longer matches its source has been re-parsed from different input, and any
 * evidence span pointing into it is suspect.
 */
public record CanonicalDocument(
        String documentVersionId,
        DocumentType documentType,
        String title,
        String sourceObjectKey,
        String contentHash,
        int pages,
        List<CanonicalNode> nodes
) {}

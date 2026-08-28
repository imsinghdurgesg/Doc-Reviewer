package com.app.docreview.contracts.retrieval;

import java.util.List;

/**
 * A standard version that may be relevant to an uploaded document. Architecture §15.1.
 *
 * <p>{@code selected} and {@code rejectionReason} are both present because §17.6's distinction
 * between MISSING and INSUFFICIENT_EVIDENCE starts here. If a standard was rejected below the
 * relevance threshold, a requirement it contains was never evaluated — reporting that as a
 * compliance gap would assert something that was never tested.
 */
public record StandardCandidate(
        String standardId,
        String standardVersionId,
        String standardKey,
        String title,
        String owner,

        /**
         * Ranking metadata, returned so a reviewer can see WHY a standard was ranked where it was —
         * and so the retrieval evaluation can measure the wrong-department rate (§15.1).
         */
        String department,
        String standardFamily,
        String documentType,

        /**
         * The version label as an administrator entered it, e.g. {@code 2.4}.
         *
         * <p>Shown so a reviewer choosing between two versions of one standard can tell them apart.
         * Never parsed for ordering — see {@code StandardVersionPreference} on why labels have no
         * reliable sort.
         */
        String version,

        /**
         * DRAFT, ACTIVE, DEPRECATED, or ARCHIVED at index time.
         *
         * <p>Returned so the reviewer can see they are being offered a superseded version, and so the
         * evaluation harness can measure the superseded-candidate rate without a labelled set (§8.1).
         */
        String lifecycle,

        List<String> tags,
        RetrievalScores scores,
        boolean selected,
        String rejectionReason
) {}

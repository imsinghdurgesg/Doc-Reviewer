package com.app.docreview.contracts.document;

/**
 * What a retrieval chunk contains. Architecture §11.4, §15.1, §15.2.
 *
 * <p><strong>Why the type matters.</strong> Retrieval previously distinguished only {@code BODY} from
 * {@code CONTROL_SECTION}, so a definition, a scope statement, and an obligation were all equally
 * eligible to be returned as evidence that a requirement was met. They are not equivalent: a definition
 * explains a term, an exception REMOVES an obligation, and only a requirement imposes one. Retrieving
 * the wrong kind produces a confident finding built on text that never obliged anything.
 *
 * <p><strong>These are retrieval hints, never authorization.</strong> A chunk typed {@code GUIDANCE}
 * is ranked lower for obligation retrieval; it is not hidden, because the classifier is heuristic and a
 * misfiled obligation must stay findable. Hard exclusion by chunk type would let one classification
 * mistake silently remove a requirement from every comparison.
 *
 * <p>Ordered from document-level to clause-level, which is also roughly decreasing breadth.
 */
public enum ChunkType {

    /**
     * Title, purpose, ownership, version — what the document IS.
     *
     * <p>Used to match a process document to a standard, not to evaluate compliance: a purpose
     * statement obliges nothing, so a finding citing one has no normative basis.
     */
    DOCUMENT_PROFILE,

    /** What the standard covers. Bounds interpretation; imposes nothing by itself. */
    SCOPE,

    /**
     * Who or what the standard applies to, and under what conditions.
     *
     * <p>Distinct from {@link #SCOPE}: scope says what subject matter is covered, applicability says
     * whether THIS document's situation falls inside it. Phase 2's applicability decision reads these.
     */
    APPLICABILITY,

    /**
     * An obligation, prohibition, or conditional duty — the only type that can be complied with.
     *
     * <p>The primary retrieval target for comparison. Everything else provides context for judging a
     * requirement rather than being a requirement.
     */
    REQUIREMENT,

    /**
     * A term definition.
     *
     * <p>Never a requirement, however imperative the wording: "Privileged Access means access that
     * bypasses controls" defines, it does not oblige. §12.8 rule 9 forbids promoting definitions to
     * controls, and typing them is how that rule reaches retrieval.
     */
    DEFINITION,

    /** Advisory, explanatory, or best-practice text. Non-normative: "should consider", "may". */
    GUIDANCE,

    /**
     * A carve-out that removes or relaxes an obligation.
     *
     * <p>Retrieved WITH requirements deliberately. An exception found without its requirement reads as
     * permission; a requirement found without its exception reports a gap the standard expressly
     * allows.
     */
    EXCEPTION,

    /**
     * What evidence demonstrates compliance — records, approvals, logs, retention periods.
     *
     * <p>Valuable for locating proof in the process document, and dangerous as a finding basis on its
     * own: naming an expected artefact is not an obligation to produce it.
     */
    EVIDENCE_EXPECTATION,

    /**
     * A chunk indexed before the taxonomy existed, whose type was never determined.
     *
     * <p><strong>Not a synonym for GUIDANCE.</strong> The V1 chunker emitted only {@code BODY} and
     * {@code CONTROL_SECTION}, distinguished purely by "does this chunk have a heading" — which says
     * nothing about whether the text carries an obligation. Mapping those to GUIDANCE would assert
     * something the V1 index never recorded: that the content is non-normative. A 199-chunk
     * CONTROL_SECTION population almost certainly contains real requirements.
     *
     * <p>So legacy chunks keep their own type. Retrieval treats them as UNCLASSIFIED — eligible, not
     * ranked as though their normative status were known — and the honest signal is that they need
     * reprocessing rather than reinterpretation.
     */
    UNCLASSIFIED_LEGACY;

    /** The V1 chunk type values, which predate this taxonomy. */
    private static final java.util.Set<String> LEGACY_VALUES =
            java.util.Set.of("BODY", "CONTROL_SECTION");

    /**
     * True when this type can carry a normative obligation.
     *
     * <p>Only {@link #REQUIREMENT} and {@link #EXCEPTION} — an exception because it modifies an
     * obligation's reach and therefore changes whether a finding is a gap.
     */
    public boolean isNormative() {
        return this == REQUIREMENT || this == EXCEPTION;
    }

    /**
     * True when this type records a determination rather than the absence of one.
     *
     * <p>{@link #UNCLASSIFIED_LEGACY} is the only false case. Callers that rank by normative status
     * should treat an unclassified chunk as "unknown", not as "non-normative".
     */
    public boolean isClassified() {
        return this != UNCLASSIFIED_LEGACY;
    }

    /**
     * Parses a value read back from a vector payload or an older index.
     *
     * <p><strong>Legacy values are preserved as {@link #UNCLASSIFIED_LEGACY}, not reinterpreted.</strong>
     * {@code BODY} and {@code CONTROL_SECTION} predate the taxonomy and carry no normative
     * determination; mapping them to GUIDANCE would silently assert that 212 already-indexed chunks are
     * non-normative when nothing ever established that. The distinction also tells an operator which
     * documents still need reprocessing — information that collapsing to GUIDANCE destroys.
     *
     * @return the matching type; {@link #UNCLASSIFIED_LEGACY} for a known V1 value; {@link #GUIDANCE}
     *         for null, blank, or an unrecognised string, since an unparseable type must not be read as
     *         carrying an obligation
     */
    public static ChunkType parse(String value) {
        if (value == null || value.isBlank()) {
            return GUIDANCE;
        }
        String normalised = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (LEGACY_VALUES.contains(normalised)) {
            return UNCLASSIFIED_LEGACY;
        }
        try {
            return valueOf(normalised);
        } catch (IllegalArgumentException e) {
            return GUIDANCE;
        }
    }
}

package com.app.docreview.contracts.document;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Assigns a {@link ChunkType} from a chunk's heading and text. Architecture §11.4, §12.8.
 *
 * <p><strong>Heuristic by design, and deliberately conservative.</strong> The type is a retrieval
 * ranking hint, never an authorization decision, so the cost of a wrong answer is a worse-ranked chunk
 * rather than a hidden requirement. Where the signals conflict this returns the type that claims LESS:
 * a chunk that looks like both guidance and an obligation is typed {@link ChunkType#REQUIREMENT} only
 * when the obligation cue is unambiguous, because under-typing loses ranking quality while over-typing
 * invents obligations.
 *
 * <p><strong>Heading first, then body.</strong> Standards are written with section headings that name
 * the content type — "3. Definitions", "Appendix B: Exceptions" — and that signal is far more reliable
 * than prose pattern-matching. Body text only decides when the heading says nothing useful.
 *
 * <p>Lives in contracts rather than either service because chunking happens in document-processing
 * while retrieval and extraction interpret the result in retrieval-comparison, and §6 forbids one
 * service depending on another's jar. A second copy would drift, and the two services would disagree
 * about what a chunk is.
 */
public final class ChunkTypeClassifier {

    /**
     * Bump on ANY change to these rules.
     *
     * <p>Chunk types change retrieval ranking, so a chunk classified under different rules is not
     * comparable to one classified under these. §19.2 needs the version to tell them apart, and the
     * chunking strategy version alone is not enough — the same chunk boundaries can carry different
     * types.
     */
    public static final String TAXONOMY_VERSION = "CHUNKTAX-V1";

    private ChunkTypeClassifier() {
    }

    // ---- Heading cues. Matched against the joined section path, which is the strongest signal. ----

    private static final List<Pattern> HEADING_DEFINITION = compile(
            "\\bdefinitions?\\b", "\\bglossary\\b", "\\bterms?\\s+and\\s+definitions?\\b",
            "\\bacronyms?\\b", "\\babbreviations?\\b");

    private static final List<Pattern> HEADING_SCOPE = compile(
            "\\bscope\\b", "\\bpurpose\\b", "\\bobjectives?\\b", "\\boverview\\b",
            "\\bintroduction\\b", "\\bbackground\\b");

    private static final List<Pattern> HEADING_APPLICABILITY = compile(
            "\\bapplicab\\w*\\b", "\\bapplies\\s+to\\b", "\\baudience\\b",
            "\\beligibility\\b", "\\bcovered\\s+(parties|systems|entities)\\b");

    private static final List<Pattern> HEADING_EXCEPTION = compile(
            "\\bexceptions?\\b", "\\bexemptions?\\b", "\\bwaivers?\\b",
            "\\bderogations?\\b", "\\bdeviations?\\b");

    private static final List<Pattern> HEADING_EVIDENCE = compile(
            "\\bevidence\\b", "\\brecords?\\s+(retention|keeping|management)\\b",
            "\\bdocumentation\\s+requirements?\\b", "\\bartifacts?\\b", "\\baudit\\s+trail\\b");

    private static final List<Pattern> HEADING_GUIDANCE = compile(
            "\\bguidance\\b", "\\bbest\\s+practices?\\b", "\\brecommendations?\\b",
            "\\bexamples?\\b", "\\bappendix\\b", "\\bfaq\\b", "\\breferences?\\b");

    private static final List<Pattern> HEADING_PROFILE = compile(
            "\\bdocument\\s+(control|information|history)\\b", "\\bversion\\s+history\\b",
            "\\brevision\\s+history\\b", "\\bapprovals?\\b", "\\bownership\\b",
            "\\btable\\s+of\\s+contents\\b");

    private static final List<Pattern> HEADING_REQUIREMENT = compile(
            "\\brequirements?\\b", "\\bcontrols?\\b", "\\bpolicy\\s+statements?\\b",
            "\\bstandards?\\b", "\\bresponsibilit\\w+\\b", "\\bprocedures?\\b", "\\brules?\\b");

    // ---- Body cues. Only consulted when the heading is uninformative. ----

    /**
     * Unambiguous obligation cues.
     *
     * <p>"must not" and "shall not" are absent deliberately: they are prohibitions, which are still
     * requirements for typing purposes. Normative STRENGTH is determined during extraction, not here.
     */
    private static final List<Pattern> BODY_OBLIGATION = compile(
            "\\bmust\\b", "\\bshall\\b", "\\bis\\s+required\\s+to\\b",
            "\\bare\\s+required\\s+to\\b", "\\bis\\s+prohibited\\b", "\\bare\\s+prohibited\\b",
            "\\bmay\\s+not\\b", "\\bis\\s+responsible\\s+for\\b");

    /** Advisory cues. Weaker than obligation, so obligation is tested first. */
    private static final List<Pattern> BODY_ADVISORY = compile(
            "\\bshould\\s+consider\\b", "\\bmay\\s+wish\\s+to\\b", "\\bfor\\s+example\\b",
            "\\bbest\\s+practice\\b", "\\bit\\s+is\\s+recommended\\b", "\\btypically\\b");

    /** Definition phrasing. Highly distinctive, so it beats an incidental "must" in the same chunk. */
    private static final List<Pattern> BODY_DEFINITION = compile(
            "\\bmeans\\b", "\\bis\\s+defined\\s+as\\b", "\\brefers\\s+to\\b",
            "\\bfor\\s+the\\s+purposes\\s+of\\s+this\\b");

    private static final List<Pattern> BODY_EXCEPTION = compile(
            "\\bexcept\\s+(where|when|as|for)\\b", "\\bunless\\b", "\\bdoes\\s+not\\s+apply\\s+to\\b",
            "\\bis\\s+exempt\\b", "\\bare\\s+exempt\\b", "\\bwaiver\\b");

    private static final List<Pattern> BODY_EVIDENCE = compile(
            "\\bretain\\w*\\s+for\\b", "\\bevidence\\s+of\\b", "\\bmust\\s+be\\s+documented\\b",
            "\\baudit\\s+log\\b", "\\bapproval\\s+record\\b");

    private static final List<Pattern> BODY_APPLICABILITY = compile(
            "\\bapplies\\s+to\\b", "\\bis\\s+applicable\\s+to\\b",
            "\\ball\\s+employees\\b", "\\bthird\\s+part\\w+\\s+(who|that)\\b");

    /**
     * Classifies one chunk.
     *
     * @param sectionPath the heading trail, outermost first. May be null or empty.
     * @param text        the chunk's text. May be null.
     * @return never null; {@link ChunkType#GUIDANCE} when nothing matches, because an unclassified
     *         chunk must not be treated as normative
     */
    public static ChunkType classify(List<String> sectionPath, String text) {
        String heading = sectionPath == null || sectionPath.isEmpty()
                ? ""
                : String.join(" ", sectionPath).toLowerCase(Locale.ROOT);
        String body = text == null ? "" : text.toLowerCase(Locale.ROOT);

        // Heading wins when it names a content type. Ordered most-specific first: DEFINITIONS and
        // EXCEPTIONS are unambiguous section names, while "requirements" is generic enough that a
        // "Definitions" subsection beneath a "Requirements" heading should still read as definitions.
        if (matchesAny(heading, HEADING_DEFINITION)) {
            return ChunkType.DEFINITION;
        }
        if (matchesAny(heading, HEADING_EXCEPTION)) {
            return ChunkType.EXCEPTION;
        }
        if (matchesAny(heading, HEADING_APPLICABILITY)) {
            return ChunkType.APPLICABILITY;
        }
        if (matchesAny(heading, HEADING_EVIDENCE)) {
            return ChunkType.EVIDENCE_EXPECTATION;
        }
        if (matchesAny(heading, HEADING_PROFILE)) {
            return ChunkType.DOCUMENT_PROFILE;
        }
        if (matchesAny(heading, HEADING_SCOPE)) {
            return ChunkType.SCOPE;
        }
        if (matchesAny(heading, HEADING_GUIDANCE)) {
            // Guidance headings lose to a clear obligation in the body: an "Appendix A" that states
            // "systems must be patched within 30 days" is normative wherever it sits, and typing it
            // GUIDANCE would rank a real requirement below advisory prose.
            return matchesAny(body, BODY_OBLIGATION) ? ChunkType.REQUIREMENT : ChunkType.GUIDANCE;
        }
        if (matchesAny(heading, HEADING_REQUIREMENT)) {
            // A requirements heading is a strong prior, but a definition body under it is still a
            // definition (§12.8 rule 9).
            return matchesAny(body, BODY_DEFINITION) && !matchesAny(body, BODY_OBLIGATION)
                    ? ChunkType.DEFINITION
                    : ChunkType.REQUIREMENT;
        }

        // No informative heading: fall back to body cues, most distinctive first.
        if (matchesAny(body, BODY_DEFINITION) && !matchesAny(body, BODY_OBLIGATION)) {
            return ChunkType.DEFINITION;
        }
        if (matchesAny(body, BODY_EXCEPTION) && !matchesAny(body, BODY_OBLIGATION)) {
            return ChunkType.EXCEPTION;
        }
        if (matchesAny(body, BODY_EVIDENCE)) {
            return ChunkType.EVIDENCE_EXPECTATION;
        }
        if (matchesAny(body, BODY_OBLIGATION)) {
            return ChunkType.REQUIREMENT;
        }
        if (matchesAny(body, BODY_APPLICABILITY)) {
            return ChunkType.APPLICABILITY;
        }
        if (matchesAny(body, BODY_ADVISORY)) {
            return ChunkType.GUIDANCE;
        }

        // Nothing matched. GUIDANCE rather than REQUIREMENT: an unclassifiable chunk must not be
        // presented as carrying an obligation.
        return ChunkType.GUIDANCE;
    }

    private static boolean matchesAny(String value, List<Pattern> patterns) {
        if (value.isEmpty()) {
            return false;
        }
        return patterns.stream().anyMatch(p -> p.matcher(value).find());
    }

    private static List<Pattern> compile(String... regexes) {
        return java.util.Arrays.stream(regexes)
                .map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE))
                .toList();
    }
}

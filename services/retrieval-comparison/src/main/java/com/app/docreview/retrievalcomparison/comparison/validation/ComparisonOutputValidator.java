package com.app.docreview.retrievalcomparison.comparison.validation;

import com.app.docreview.contracts.comparison.ComparisonModelOutput;
import com.app.docreview.contracts.enums.ComparisonStatus;
import com.app.docreview.contracts.enums.GapType;
import com.app.docreview.contracts.retrieval.SearchCoverage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates comparison output. Architecture §18.1, §18.2, §18.3.
 *
 * <p>Three layers, in order, because each is meaningless without the one before it:
 *
 * <ol>
 *   <li><strong>Schema</strong> (§18.1) — required fields, enum values, confidence range.</li>
 *   <li><strong>Evidence ownership</strong> (§18.2) — every cited span was supplied and belongs to
 *       the right document version. This is the anti-fabrication check: the model can emit any
 *       string, and one that was not offered simply fails.</li>
 *   <li><strong>Status/evidence consistency</strong> (§18.3) — the verdict must be supported by the
 *       evidence it cites. This is the layer that catches the most dangerous output: a confident
 *       ALIGNED with no process evidence, or a MISSING asserted over an inadequately searched
 *       document.</li>
 * </ol>
 *
 * <p>A failure here is not an exception. §18.4 requires one retry with the errors, then
 * COMPARISON_FAILED for that requirement while the others continue — one bad response must not
 * discard a hundred good findings.
 */
@Component
@Slf4j
public class ComparisonOutputValidator {

    /**
     * The promoted schema. Architecture §18.1, §19.2.
     *
     * <p>V2 is the shape {@link ComparisonModelOutput} defines today. A withdrawn revision added a
     * model-reported extraction limitation and was rolled back: the branch was reachable on every
     * requirement, and the model used it for ordinary comparison difficulty rather than for the rare
     * case it was built for. Those fields are gone from the output record, so nothing remains to
     * validate — and whether a requirement was extracted completely is now decided deterministically at
     * ingestion, before any model call.
     */
    public static final String SCHEMA_VERSION = "COMPARE-SCHEMA-V2";

    /**
     * @param allowedStandardSpanIds spans supplied as standard evidence
     * @param allowedProcessSpanIds  spans supplied as process evidence
     * @param requirementSourceSpanIds the authoritative statement of the requirement (§12.5)
     */
    public List<String> validate(ComparisonModelOutput output,
                                 String expectedRequirementId,
                                 Set<String> allowedStandardSpanIds,
                                 Set<String> allowedProcessSpanIds,
                                 Set<String> requirementSourceSpanIds,
                                 SearchCoverage coverage,
                                 Set<String> declaredClauseIds) {

        List<String> problems = new ArrayList<>();

        // ---- §18.1 schema ----
        if (output == null) {
            return List.of("no output produced");
        }
        if (output.status() == null) {
            problems.add("status is missing");
        }
        if (output.confidence() < 0.0 || output.confidence() > 1.0) {
            problems.add("confidence " + output.confidence() + " outside 0.0-1.0");
        }
        if (output.reason() == null || output.reason().isBlank()) {
            // A verdict without a reason cannot be reviewed, only obeyed.
            problems.add("reason is empty; the verdict would be unreviewable");
        }

        // The model must be answering about the requirement it was asked about. A mismatch means
        // either a provider mix-up or a successful injection; either way the result is unusable.
        //
        // Compared after NORMALISATION, because an exact match rejected correct answers. Models
        // routinely echo the id with the label they were shown — "REQUIREMENT 1-1" for "1-1" — and a
        // strict equals() threw those away, costing a real evaluation per run and reporting the
        // control as unevaluated. That is a false alarm about the very thing §18.4 exists to make
        // trustworthy: a reviewer cannot tell a genuine mix-up from a formatting quirk.
        //
        // The check still holds. Normalisation strips only decoration — case, surrounding
        // whitespace/punctuation, and a leading "requirement"/"control"/"clause" label — so an answer
        // about a DIFFERENT requirement still fails, which is the case this guard is for.
        if (output.requirementId() != null
                && !normalizeRequirementId(output.requirementId())
                        .equals(normalizeRequirementId(expectedRequirementId))) {
            problems.add("requirementId mismatch: asked about %s, answered about %s"
                    .formatted(expectedRequirementId, output.requirementId()));
        }

        // gapType belongs to PARTIAL only (§17.2). Elsewhere it is noise that misleads a reviewer
        // into thinking a MISSING requirement is merely weaker.
        //
        // NOT a validation problem, though. §17.2 says gap types "may include" those values for
        // PARTIAL; it does not forbid one appearing elsewhere, and a stray descriptive field does not
        // make the STATUS wrong. Rejecting the whole comparison over it discarded correct MISSING
        // verdicts twice in one run — the model answered well and the requirement went unevaluated,
        // which §18.4 counts as a failure and the reviewer reads as an unchecked control.
        //
        // See sanitize(): the field is dropped instead, so the verdict survives and the noise does
        // not reach the UI.
        if (output.gapType() != null && output.status() != ComparisonStatus.PARTIAL) {
            log.debug("Dropping gapType {} on {} status for {}",
                    output.gapType(), output.status(), output.requirementId());
        }

        // ---- §18.2 evidence ownership ----
        problems.addAll(checkSpans(output.standardSpanIds(), allowedStandardSpanIds, "standard"));
        problems.addAll(checkSpans(output.processSpanIds(), allowedProcessSpanIds, "process"));

        // §18.2: standard evidence must include the authoritative source requirement. Without this a
        // finding can cite a neighbouring clause while claiming to apply this one.
        if (output.status() != null && output.status() != ComparisonStatus.INSUFFICIENT_EVIDENCE
                && !requirementSourceSpanIds.isEmpty()
                && (output.standardSpanIds() == null
                    || output.standardSpanIds().stream().noneMatch(requirementSourceSpanIds::contains))) {
            problems.add("standard evidence omits the requirement's own source span");
        }

        // Clause references must exist. A satisfied clause the requirement does not contain means
        // the model invented an element, and §18.5 aggregation would then compute over fiction.
        problems.addAll(checkClauses(output.satisfiedClauseIds(), declaredClauseIds, "satisfied"));
        problems.addAll(checkClauses(output.unsatisfiedClauseIds(), declaredClauseIds, "unsatisfied"));

        // ---- §18.3 status/evidence consistency ----
        problems.addAll(checkStatusConsistency(output, coverage));

        return problems;
    }

    private List<String> checkSpans(List<String> cited, Set<String> allowed, String side) {
        if (cited == null) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        for (String spanId : cited) {
            if (!allowed.contains(spanId)) {
                // THE anti-fabrication check. An ID that was never supplied cannot be displayed as
                // evidence, so an invented citation cannot reach a reviewer.
                problems.add("%s span %s was not supplied as evidence".formatted(side, spanId));
            }
        }
        return problems;
    }

    /**
     * Normalises a model-returned clause reference to its bare ID.
     *
     * <p>Models routinely return the clause ID with its description appended — {@code "CHG-04-A:
     * who=independent reviewer"} rather than {@code "CHG-04-A"} — because the prompt shows clauses in
     * that annotated form. The reference is CORRECT; only its formatting is not.
     *
     * <p>Rejecting those outright failed real comparisons: one run lost a requirement to four
     * "clause who=… is not part of this requirement" errors, where the model had identified the right
     * clause and merely echoed the label with it. §18.4 then counted the requirement as unevaluated,
     * which a reviewer reads as an unchecked control.
     *
     * <p>Splitting on the first colon is safe because clause IDs are generated from control IDs and
     * ordinals (§12.7) and never contain one.
     */
    private static String bareClauseId(String cited) {
        if (cited == null) {
            return "";
        }
        int colon = cited.indexOf(':');
        return (colon >= 0 ? cited.substring(0, colon) : cited).strip();
    }

    private List<String> checkClauses(List<String> cited, Set<String> declared, String label) {
        if (cited == null) {
            return List.of();
        }
        return cited.stream()
                // Compared on the bare ID: a fabricated clause is still caught, but an annotated
                // reference to a real one is not treated as invention.
                .filter(id -> resolveClauseId(id, declared) == null)
                .map(id -> "%s clause %s is not part of this requirement".formatted(label, id))
                .toList();
    }

    /**
     * Resolves a model-returned clause reference to a declared clause ID, or null if it cannot be.
     *
     * <p><strong>Why prefix matching rather than splitting on a separator.</strong> Real clause IDs
     * are derived from control numbering (§12.7) and routinely contain dots — 197 of 224 in one
     * indexed corpus look like {@code 3.3-a} or {@code 4.1-c1}. So the separator a model appends
     * ({@code 4-2.who}) is indistinguishable by character class from the ID's own punctuation, and
     * splitting on it would corrupt legitimate references.
     *
     * <p>Matching a declared ID as a PREFIX is safe in the direction that matters: it accepts
     * {@code 4-2.who} for declared {@code 4-2}, while a genuinely invented {@code 9-9} still resolves
     * to nothing. Longest match wins so {@code 3.3-ab} prefers declared {@code 3.3-ab} over
     * {@code 3.3-a}.
     *
     * <p>This exists because a formatting mismatch was costing whole requirements. One run reported
     * four "clause 4-2.who is not part of this requirement" problems for a requirement declaring a
     * single clause {@code 4-2} — the model had decomposed it into who/does/to/when and prefixed each
     * with the real ID. §18.4 then counted the requirement as unevaluated, which a reviewer reads as
     * an unchecked control rather than as a model formatting quirk.
     */
    private static String resolveClauseId(String cited, Set<String> declared) {
        String bare = bareClauseId(cited);
        if (bare.isEmpty()) {
            return null;
        }
        if (declared.contains(bare)) {
            return bare;
        }
        // Longest declared ID that the reference starts with, so a more specific clause is not
        // shadowed by a shorter one that happens to share its opening characters.
        return declared.stream()
                .filter(d -> d != null && !d.isBlank() && bare.startsWith(d))
                .max(java.util.Comparator.comparingInt(String::length))
                .orElse(null);
    }

    /**
     * §18.3. Each rule prevents a specific kind of unsupported verdict.
     */
    private List<String> checkStatusConsistency(ComparisonModelOutput output, SearchCoverage coverage) {
        List<String> problems = new ArrayList<>();
        if (output.status() == null) {
            return problems;
        }
        boolean hasProcessEvidence = output.processSpanIds() != null
                && !output.processSpanIds().isEmpty();

        switch (output.status()) {
            case ALIGNED -> {
                // The worst possible unsupported verdict: "compliant" with nothing to point at.
                if (!hasProcessEvidence) {
                    problems.add("ALIGNED requires supporting process evidence");
                }
            }
            case CONTRADICTED -> {
                // A contradiction is a claim about what the document SAYS, so it must quote it.
                if (!hasProcessEvidence) {
                    problems.add("CONTRADICTED requires explicit conflicting process evidence");
                }
            }
            case PARTIAL -> {
                if (!hasProcessEvidence) {
                    problems.add("PARTIAL requires related process evidence");
                }
                if (output.unsatisfiedClauseIds() == null || output.unsatisfiedClauseIds().isEmpty()) {
                    // Otherwise "partial" is unactionable: the reviewer cannot tell what to fix.
                    problems.add("PARTIAL requires at least one unsatisfied clause");
                }
            }
            case MISSING -> {
                // Absence may legitimately have no evidence — but only if the document was actually
                // searched. This is the check that stops a search failure being reported as a
                // compliance gap (§17.6).
                if (coverage != null && !coverage.supportsAbsenceVerdict()) {
                    problems.add("MISSING is not supportable: coverage is incomplete "
                            + "(parsed=" + coverage.parseComplete()
                            + ", passages=" + coverage.chunksSearched()
                            + ", unresolved refs=" + coverage.unresolvedExternalReferences().size()
                            + "); use INSUFFICIENT_EVIDENCE");
                }
            }
            case INSUFFICIENT_EVIDENCE -> {
                if (output.reason() == null || output.reason().isBlank()) {
                    problems.add("INSUFFICIENT_EVIDENCE requires an uncertainty reason");
                }
            }
        }
        return problems;
    }

    /**
     * An id reduced to what identifies it, for comparison only. Architecture §12.8.
     *
     * <p>Strips a leading {@code requirement}/{@code control}/{@code clause} label, surrounding
     * punctuation and whitespace, and case. So {@code "REQUIREMENT 1-1"}, {@code "Requirement: 1-1"},
     * and {@code "1-1"} all reduce to {@code "1-1"} — while {@code "1-2"} does not, which is what
     * keeps the mismatch guard meaningful.
     *
     * <p>Used ONLY for the equality check. The value stored on the finding is the id the system asked
     * about, never the model's rendering of it, so §19.2 reproducibility is unaffected.
     */
    private static String normalizeRequirementId(String id) {
        if (id == null) {
            return "";
        }
        return id.trim()
                .replaceFirst("(?i)^\\s*(requirement|control|clause)\\b[\\s:.#-]*", "")
                .replaceAll("[\\s\\p{Punct}]+$", "")
                .replaceAll("^[\\s\\p{Punct}]+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }
}

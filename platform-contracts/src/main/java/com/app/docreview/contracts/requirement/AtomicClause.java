package com.app.docreview.contracts.requirement;

/**
 * One independently testable element of a requirement. Architecture §12.5, §12.6.
 *
 * <p><strong>This is the unit of comparison</strong> (§16.1). A compound source sentence such as
 *
 * <pre>
 *   "Changes must be independently approved, include a documented rollback plan, and receive
 *    retrospective review within two business days when processed as an emergency."
 * </pre>
 *
 * becomes three clauses, each evaluated separately. Evaluating the sentence as a whole lets a real
 * failure hide inside an aligned verdict: a process with approval but no rollback plan would score
 * "mostly satisfied" against one blended requirement.
 *
 * <p>The fields are extracted semantically by the model, not by pattern matching. That distinction
 * matters — a regex over "The rollback plan must identify the steps" yields
 * {@code subject = "rollback plan"}, which is a grammatical subject, not an accountable actor. A
 * plan cannot bear an obligation.
 *
 * <p>{@code negated} is separate from {@code action} rather than folded into it, because a
 * prohibition and its corresponding obligation are the same action with opposite force. Losing the
 * flag inverts the verdict.
 *
 * @param clauseId  stable within the parent requirement, e.g. {@code CHG-07-A}
 * @param subject   the actor bearing the obligation
 * @param action    what must (or must not) be done
 * @param object    what the action applies to
 * @param timing    when it must occur — "before deployment", "within five business days"
 * @param condition the circumstance that makes it apply; null when unconditional
 * @param negated   true for prohibitions
 */
public record AtomicClause(
        String clauseId,
        String subject,
        String action,
        String object,
        String timing,
        String condition,
        boolean negated
) {

    /**
     * A readable one-line rendering of this clause, for display beside its verdict.
     *
     * <p><strong>Why this is needed.</strong> The clause is stored as structured fields with no
     * sentence form, so a UI listing verdicts could only show the clause ID. Two sibling clauses of
     * one requirement — {@code 5.1-a} "publish" and {@code 5.1-b} "observe" — then appeared as two
     * identical rows differing only by an opaque ID suffix, and a reviewer could not tell which
     * obligation had failed. The whole point of clause-level results is answering "WHICH element?"
     * (§18.5), which an ID alone does not.
     *
     * <p>Derived deterministically from the stored fields — never model-generated. It is a
     * presentation of data the extractor already committed to, so it cannot introduce a claim the
     * requirement does not make.
     *
     * <p>{@code condition} is omitted when it merely repeats {@code timing}: the extractor frequently
     * populates both with the same phrase, and rendering it twice ("during close (during close)")
     * reads as a defect.
     */
    public String displayText() {
        StringBuilder text = new StringBuilder();
        if (subject != null && !subject.isBlank()) {
            text.append(subject.strip()).append(' ');
        }
        if (negated) {
            text.append("must not ");
        } else if (!text.isEmpty()) {
            text.append("must ");
        }
        if (action != null && !action.isBlank()) {
            text.append(action.strip());
        }
        // Skipped when the object simply restates the subject ("change freeze periods … publish
        // change freeze periods"), which the extractor does for intransitive obligations.
        if (object != null && !object.isBlank()
                && !object.strip().equalsIgnoreCase(subject == null ? "" : subject.strip())) {
            text.append(' ').append(object.strip());
        }
        if (timing != null && !timing.isBlank()) {
            text.append(' ').append(timing.strip());
        }
        if (condition != null && !condition.isBlank()
                && !condition.strip().equalsIgnoreCase(timing == null ? "" : timing.strip())) {
            text.append(" (").append(condition.strip()).append(')');
        }
        return text.toString().replaceAll("\\s+", " ").strip();
    }
}

package com.app.docreview.retrievalcomparison.llm;

import java.util.List;

/**
 * Infers what an uploaded process document is, for ranking candidate standards. Architecture §15.1.
 *
 * <p><strong>Why this exists.</strong> {@code AssessmentContext} ranks candidates by comparing the
 * document's department, family, and document type against each standard's. Nothing populated those
 * fields, so every comparison used a neutral weight of 1.0 and the ranking machinery was inert unless a
 * reviewer typed the values by hand.
 *
 * <p><strong>The values must come from a closed vocabulary.</strong> This is the whole design.
 * {@code AssessmentContext.delta} compares labels for EXACT equality (case- and whitespace-insensitive)
 * and treats a mismatch as a PENALTY. So a model that answers "IT Security" when the corpus says
 * "Information Security" does not merely fail to help — it actively demotes the standard that governs.
 * Free-text inference here would be worse than no inference at all.
 *
 * <p>The caller therefore passes the labels that actually exist on the candidate standards, and the
 * model must choose from them or abstain. Abstaining is a first-class answer: a null field scores 0.0
 * in {@code delta}, which is exactly the neutral behaviour that existed before inference.
 *
 * <p>Contrast with {@link StandardProfileModelGateway}, which deliberately emits FREE TEXT because its
 * output is embedded into a vector where near-synonyms are harmless. These two gateways look similar
 * and have opposite constraints: embedded text tolerates paraphrase, compared labels do not.
 *
 * <p>An interface so domain code never sees Spring AI (§10.3).
 */
public interface DocumentContextModelGateway {

    /**
     * Reads the opening of an uploaded document and proposes what it is.
     *
     * @param openingText    the document's first passages. NOT the whole document: what is wanted is
     *                       what the document IS, and forty pages of procedure steps dilute that.
     * @param vocabulary     the labels present on candidate standards. The model must pick from these
     *                       or abstain; a value outside them is discarded by the caller.
     * @return the inference, or {@link Result#unavailable()} when the model could not be reached,
     *         returned nothing usable, or the vocabulary was empty. Never throws: a comparison must
     *         still run with a neutral weight rather than fail over an optional ranking signal.
     */
    Result infer(String openingText, Vocabulary vocabulary);

    /**
     * The labels available to choose from, harvested from candidate standards.
     *
     * <p>Empty lists are permitted and mean "do not ask about this dimension" — with no corpus values
     * to match against, any answer would be a guaranteed mismatch penalty.
     */
    record Vocabulary(
            List<String> departments,
            List<String> standardFamilies,
            List<String> documentTypes) {

        public Vocabulary {
            departments = clean(departments);
            standardFamilies = clean(standardFamilies);
            documentTypes = clean(documentTypes);
        }

        /**
         * Distinct, non-blank, original casing preserved.
         *
         * <p>Casing is preserved rather than normalised because these strings are shown to a reviewer
         * in the UI. {@code delta} compares case-insensitively, so preserving it costs no match
         * accuracy. Bounded at 40 per dimension: the list goes into a prompt, and a corpus with
         * hundreds of departments would crowd out the document text itself.
         */
        private static List<String> clean(List<String> values) {
            if (values == null) {
                return List.of();
            }
            var seen = new java.util.LinkedHashMap<String, String>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    // Keyed case-insensitively so "Finance" and "finance" do not both reach the prompt
                    // as if they were different choices.
                    seen.putIfAbsent(value.trim().toLowerCase(java.util.Locale.ROOT), value.trim());
                }
            }
            return seen.values().stream().limit(40).toList();
        }

        public boolean isEmpty() {
            return departments.isEmpty() && standardFamilies.isEmpty() && documentTypes.isEmpty();
        }

        /**
         * The permitted spelling of a model-proposed department, or null if it is not permitted.
         *
         * <p>Enforcement lives HERE rather than in the gateway so it is directly testable and sits next
         * to the list it guards. The prompt asks the model to copy a value verbatim; this makes that a
         * guarantee instead of a request.
         */
        public String permittedDepartment(String proposed) {
            return permitted(proposed, departments);
        }

        public String permittedStandardFamily(String proposed) {
            return permitted(proposed, standardFamilies);
        }

        public String permittedDocumentType(String proposed) {
            return permitted(proposed, documentTypes);
        }

        /**
         * Returns the VOCABULARY's casing, not the caller's.
         *
         * <p>So the value compares equal to the standard's label and displays consistently beside it.
         * Matching is case- and whitespace-insensitive because that is how {@code AssessmentContext}
         * compares — a stricter check here would discard values that would in fact have matched.
         */
        private static String permitted(String proposed, List<String> allowed) {
            if (proposed == null || proposed.isBlank()) {
                return null;
            }
            String needle = proposed.trim();
            for (String candidate : allowed) {
                if (candidate.equalsIgnoreCase(needle)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * @param department   chosen from the vocabulary, or null when the model abstained
     * @param confidence   how sure the model is, 0.0–1.0. Drives whether the UI presents the values as
     *                     settled or as a question — NOT whether they are applied, because
     *                     {@code AssessmentContext} already bounds their influence to ±18%/−14%
     *                     regardless. Confidence changes what the reviewer is told, not the maths.
     * @param modelAlias   which model produced this, recorded for §19.2
     * @param available    false when the call failed or the vocabulary was empty; the caller then
     *                     ranks with a neutral weight, exactly as before inference existed
     */
    record Result(
            String department,
            String standardFamily,
            String documentType,
            double confidence,
            String modelAlias,
            boolean available) {

        public static Result unavailable() {
            return new Result(null, null, null, 0.0, null, false);
        }

        /** True when nothing was inferred, even though the call itself succeeded. */
        public boolean isEmpty() {
            return blank(department) && blank(standardFamily) && blank(documentType);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}

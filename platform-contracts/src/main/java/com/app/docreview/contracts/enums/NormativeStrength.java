package com.app.docreview.contracts.enums;

/**
 * Binding force of a requirement. Architecture §12.2.
 *
 * <p>Determines whether a requirement is EVALUATED at all. Only mandatory requirements,
 * prohibitions, and applicable conditional requirements are compared by default — reporting a
 * recommendation as a compliance gap is noise that trains reviewers to ignore findings.
 *
 * <p>§12.2 requires the interpretation of {@code should} to be administrator-configurable per
 * standards family, so this classification is an input to that policy rather than the policy
 * itself.
 */
public enum NormativeStrength {
    MANDATORY,
    PROHIBITION,
    CONDITIONAL,
    ADVISORY,
    DEFINITION,
    EXAMPLE,
    RATIONALE
}

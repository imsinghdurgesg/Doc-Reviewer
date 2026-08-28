package com.app.docreview.contracts.enums;

/**
 * Business impact of a gap. Architecture §17.7.
 *
 * <p>Derived from standard metadata and administrator-configured rules — never from the model. A
 * model may explain impact in its reason text, but an authoritative severity it invented would make
 * risk ranking unauditable.
 */
public enum Severity {
    HIGH,
    MEDIUM,
    LOW,
    INFORMATIONAL
}

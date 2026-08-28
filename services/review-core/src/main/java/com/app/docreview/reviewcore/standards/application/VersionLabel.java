package com.app.docreview.reviewcore.standards.application;

import java.util.Locale;

/**
 * Version-label identity. Architecture §19.2.
 *
 * <p><strong>Why labels need normalising for comparison.</strong> The uniqueness check was an exact
 * string match, so {@code "2.0"} and {@code "2"} were accepted as different versions of the same
 * standard. They are not different to a reader: an administrator who uploads {@code 2} while
 * {@code 2.0} exists believes they replaced it, and a finding citing "version 2" becomes ambiguous —
 * which is precisely what the label is supposed to prevent.
 *
 * <p>Labels are stored VERBATIM and only compared normalised. Rewriting a stored label would change
 * what historical comparisons reference, and an administrator who published "v2.4" should see
 * "v2.4".
 */
public final class VersionLabel {

    private VersionLabel() {
    }

    /**
     * Comparison key for a version label.
     *
     * <p>Case- and whitespace-insensitive, with a leading {@code v} and trailing zero segments
     * removed, so all of {@code "2"}, {@code "2.0"}, {@code "2.0.0"}, {@code "V2"}, and {@code " 2 "}
     * share one key.
     *
     * <p>Non-numeric labels ({@code "draft-a"}, {@code "2024-Q1"}) are only lowercased and trimmed.
     * Stripping segments from those would be guesswork, and two labels that differ in any character
     * are more likely to be genuinely different than accidentally equal.
     */
    public static String key(String label) {
        if (label == null) {
            return "";
        }
        String trimmed = label.strip().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return "";
        }
        // "v2.4" and "2.4" are the same version; the prefix is presentation.
        String withoutPrefix = trimmed.startsWith("v") ? trimmed.substring(1).strip() : trimmed;

        // Only dotted numerics get segment normalisation. Anything else is compared as written.
        if (!withoutPrefix.matches("\\d+(\\.\\d+)*")) {
            return trimmed;
        }

        String[] segments = withoutPrefix.split("\\.");
        int last = segments.length - 1;
        // Drop trailing zero segments so 2 == 2.0 == 2.0.0. Never drop the first: "0" is a real
        // version and must not normalise to the empty string.
        while (last > 0 && Integer.parseInt(segments[last]) == 0) {
            last--;
        }

        StringBuilder key = new StringBuilder();
        for (int i = 0; i <= last; i++) {
            if (i > 0) {
                key.append('.');
            }
            // Parsed and re-rendered so "02" and "2" agree.
            key.append(Integer.parseInt(segments[i]));
        }
        return key.toString();
    }

    /** Whether two labels identify the same version. */
    public static boolean sameVersion(String a, String b) {
        return key(a).equals(key(b));
    }
}

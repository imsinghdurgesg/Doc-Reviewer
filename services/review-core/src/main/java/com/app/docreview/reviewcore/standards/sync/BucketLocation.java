package com.app.docreview.reviewcore.standards.sync;

import java.util.Locale;

/**
 * A bucket object name, read as a standard identity. Architecture §8.1, §41.
 *
 * <p>The layout is {@code <prefix>/<standard-key>/<version>/<filename>}:
 *
 * <pre>
 *   standards/access-control/2.0/access-control-standard.pdf
 *             └ key ───────┘ └v┘ └ filename ──────────────┘
 * </pre>
 *
 * <p><strong>The path carries identity a filename cannot.</strong> A bare object name says nothing
 * about which standard it revises or which version it is. Inferring a version from the filename
 * produces a library where every document is version 1.0 of itself, and inferring the standard from
 * the filename means a renamed file becomes a second standard rather than a new version of the
 * first.
 *
 * <p>A shallower path is accepted rather than rejected — {@code standards/access-control/policy.pdf}
 * yields version {@code 1.0} — because refusing it would mean an administrator's first upload
 * silently does nothing. Anything shallower still has no key and IS rejected: there is no honest
 * default for "which standard is this".
 */
record BucketLocation(String standardKey, String version, String filename) {

    /** Used when the path names a standard but no version folder. */
    private static final String IMPLIED_VERSION = "1.0";

    static BucketLocation parse(String objectName, String prefix) {
        String relative = objectName.startsWith(prefix)
                ? objectName.substring(prefix.length())
                : objectName;

        // Traversal and control characters are stripped before the path is split. An object name is
        // attacker-influenceable in a bucket several people can write to, and these segments end up
        // in storage keys and Mongo values.
        String[] parts = java.util.Arrays.stream(relative.split("/"))
                .map(BucketLocation::sanitise)
                .filter(part -> !part.isBlank())
                .toArray(String[]::new);

        if (parts.length < 2) {
            // Just a filename under the prefix: no standard to attribute it to. Guessing one from
            // the filename would quietly create a standard per file, including for a stray README.
            throw new IllegalArgumentException(
                    "Object is not under a standard folder; expected <standard-key>/<version>/<file>");
        }

        String key = normaliseKey(parts[0]);
        if (parts.length == 2) {
            return new BucketLocation(key, IMPLIED_VERSION, parts[1]);
        }
        // Deeper nesting keeps the LAST segment as the filename and the second as the version, so an
        // intermediate folder does not shift the version onto a directory name.
        return new BucketLocation(key, parts[1], parts[parts.length - 1]);
    }

    private static String sanitise(String segment) {
        String cleaned = segment.replace("\\", "/").replaceAll("[\\u0000-\\u001f\\u007f]", "");
        return cleaned.equals(".") || cleaned.equals("..") ? "" : cleaned;
    }

    /**
     * Lowercases and dash-separates, so the same folder always maps to the same standard.
     *
     * <p>{@code Access Control} and {@code access-control} are the same policy to an administrator,
     * and treating them as two standards produces a library where half the versions of a document
     * are attached to a key nobody is looking at.
     */
    private static String normaliseKey(String segment) {
        return segment.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_]+", "-")
                .replaceAll("[^a-z0-9.-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }

    /** A human-readable title for a standard created from this path. */
    String titleFromKey() {
        String spaced = standardKey.replace('-', ' ').trim();
        if (spaced.isEmpty()) {
            return standardKey;
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}

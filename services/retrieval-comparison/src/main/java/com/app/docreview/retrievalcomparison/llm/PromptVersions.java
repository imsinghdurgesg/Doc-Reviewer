package com.app.docreview.retrievalcomparison.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Builds the prompt version recorded on every result. Architecture §19.2, §10.4.
 *
 * <p><strong>The problem this solves.</strong> A comparison stores {@code promptVersion} so that a
 * finding can be explained months later — §19.2 requires reopening a result to resolve what was
 * actually measured. But the version described only the prompt text in CODE, while an administrator
 * can also append guidance stored in the database. Two comparisons could therefore both record
 * {@code COMPARE-V1} having been given materially different instructions, which makes the field
 * decorative exactly when someone needs to rely on it.
 *
 * <p>So the recorded version covers both halves: the code version, plus a short digest of the
 * guidance that was actually in force.
 *
 * <pre>
 *   no guidance   -> "COMPARE-V1"
 *   guidance set  -> "COMPARE-V1+a3f9c2e1"
 * </pre>
 *
 * <p><strong>Unchanged when there is no guidance</strong>, deliberately: every comparison already
 * stored reads {@code COMPARE-V1}, and those records were produced with no guidance, so they remain
 * accurate rather than becoming ambiguous.
 *
 * <p>A digest identifies; it does not explain. The guidance TEXT is stored alongside on the
 * comparison, because knowing that instructions differed is not the same as knowing what they said —
 * and only the latter lets someone defend a finding.
 */
public final class PromptVersions {

    /**
     * Hex characters of digest retained.
     *
     * <p>Eight is 32 bits. This is an identity label, not a security boundary — nobody gains anything
     * by finding two guidance texts with the same suffix — and a full 64-character hash appended to
     * every stored version would make the field unreadable in a UI and a log.
     */
    private static final int DIGEST_CHARS = 8;

    private PromptVersions() {
    }

    /**
     * The version to record for a call made with this guidance.
     *
     * @param baseVersion the prompt's compiled-in version, e.g. {@code COMPARE-V1}
     * @param guidance    administrator text in force for this call, or null/blank when none
     */
    public static String forGuidance(String baseVersion, String guidance) {
        if (guidance == null || guidance.isBlank()) {
            return baseVersion;
        }
        return baseVersion + "+" + digest(guidance);
    }

    /**
     * Short digest of the guidance, after normalising whitespace.
     *
     * <p>Normalised so that reformatting alone does not read as a change of instructions: an
     * administrator who rewraps a paragraph has not altered what the model is told, and a version
     * that moved for that reason would train people to ignore it.
     */
    private static String digest(String guidance) {
        String normalised = guidance.strip().replaceAll("\\s+", " ");
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, DIGEST_CHARS);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM, so this cannot happen. Rethrown rather than silently
            // returning the base version, which would misreport the run as having no guidance.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

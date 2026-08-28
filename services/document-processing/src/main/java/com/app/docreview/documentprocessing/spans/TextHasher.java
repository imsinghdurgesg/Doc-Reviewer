package com.app.docreview.documentprocessing.spans;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing for evidence integrity. Architecture §11.3.
 *
 * <p>Hashes detect ACCIDENTAL mutation of extracted evidence — a re-parse with a different library
 * version, a normalization change, a corrupted write. They are not a security control; anyone able
 * to rewrite a span can rewrite its hash.
 *
 * <p>The value is that a stale citation becomes detectable. Without it, a re-parse that shifts text
 * silently invalidates every stored offset, and old comparisons keep displaying evidence that no
 * longer says what they claim.
 */
public final class TextHasher {

    private static final String PREFIX = "sha256:";

    private TextHasher() {
    }

    /** Hashes the EXACT text, with no normalization — the point is byte-level identity. */
    public static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Mandated by the JDK; unreachable outside a broken runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean matches(String text, String expectedHash) {
        return expectedHash != null && expectedHash.equals(hash(text));
    }

    /**
     * Hashes raw bytes — used for a rendition, whose identity is its exact file content.
     *
     * <p>Separate from {@link #hash(String)} rather than decoding to a string first: a PDF is not
     * text, and round-tripping it through a charset would change the bytes being identified.
     */
    public static String hashBytes(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

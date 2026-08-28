package com.app.docreview.documentprocessing.spans;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Evidence integrity hashing. Architecture §11.3. */
class TextHasherTest {

    @Test
    @DisplayName("identical text hashes identically, so a re-parse keeps stable evidence")
    void deterministic() {
        String text = "An independent reviewer must approve the change before deployment.";
        assertThat(TextHasher.hash(text)).isEqualTo(TextHasher.hash(text));
    }

    @Test
    @DisplayName("whitespace changes the hash — the point is byte identity, not similarity")
    void whitespaceSensitive() {
        // Deliberate: a normalization change upstream MUST invalidate stored hashes, because it
        // shifts character offsets and silently breaks every stored citation.
        assertThat(TextHasher.hash("a b")).isNotEqualTo(TextHasher.hash("a  b"));
    }

    @Test
    @DisplayName("matches() detects mutated evidence")
    void detectsMutation() {
        String original = "Approval evidence is retained for seven years.";
        String hash = TextHasher.hash(original);
        assertThat(TextHasher.matches(original, hash)).isTrue();
        assertThat(TextHasher.matches("Approval evidence is retained for five years.", hash))
                .as("a changed retention period must not validate against the old hash")
                .isFalse();
    }

    @Test
    @DisplayName("hashes are prefixed so the algorithm is self-describing")
    void carriesAlgorithmPrefix() {
        assertThat(TextHasher.hash("x")).startsWith("sha256:");
    }
}

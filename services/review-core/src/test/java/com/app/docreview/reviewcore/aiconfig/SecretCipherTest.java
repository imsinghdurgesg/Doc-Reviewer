package com.app.docreview.reviewcore.aiconfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Encryption of the stored provider key. Architecture §30, §35.
 *
 * <p>The failures here are quiet ones. A plaintext fallback puts a live API key in the database in
 * exactly the deployment that forgot to configure protection; a reused IV breaks AES-GCM completely
 * while still producing output that looks encrypted.
 */
class SecretCipherTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private static SecretCipher cipher() {
        return new SecretCipher(KEY);
    }

    @Test
    @DisplayName("a value survives a round trip")
    void roundTrips() {
        var subject = cipher();
        String secret = "sk-abcdef1234567890";

        assertThat(subject.decrypt(subject.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    @DisplayName("the ciphertext does not contain the plaintext")
    void ciphertextHidesTheKey() {
        // Sounds trivial, but an encoding bug that stored base64(plaintext) would pass a round-trip
        // test and fail this one.
        var subject = cipher();
        String secret = "sk-abcdef1234567890";

        assertThat(subject.encrypt(secret)).doesNotContain(secret);
    }

    @Test
    @DisplayName("encrypting the same value twice gives different ciphertext")
    void usesAFreshIvEachTime() {
        // A fixed IV under one key is the single mistake that breaks GCM's confidentiality outright,
        // and identical output for identical input is how it would be visible.
        var subject = cipher();

        assertThat(subject.encrypt("sk-same")).isNotEqualTo(subject.encrypt("sk-same"));
    }

    @Test
    @DisplayName("a tampered ciphertext fails rather than decrypting to garbage")
    void rejectsTamperedCiphertext() {
        var subject = cipher();
        String encrypted = subject.encrypt("sk-abcdef1234567890");

        // Flip a character in the middle of the payload. GCM authenticates, so this must fail
        // outright — CBC would happily return corrupted bytes that then get sent as a credential.
        char[] chars = encrypted.toCharArray();
        int middle = chars.length / 2;
        chars[middle] = chars[middle] == 'A' ? 'B' : 'A';

        assertThatThrownBy(() -> subject.decrypt(new String(chars)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a value encrypted under a different key cannot be read")
    void rejectsAKeyRotation() {
        // Rotating DOCREVIEW_SECRET_KEY must invalidate stored keys rather than silently produce a
        // wrong one, and the message must say what to do about it.
        String encrypted = cipher().encrypt("sk-abcdef1234567890");
        var other = new SecretCipher(
                Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes()));

        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("re-enter the key in Admin");
    }

    @Test
    @DisplayName("without a configured key, storing refuses instead of falling back to plaintext")
    void refusesToStoreWithoutAKey() {
        var subject = new SecretCipher("");

        assertThat(subject.isConfigured()).isFalse();
        assertThatThrownBy(() -> subject.encrypt("sk-abcdef"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DOCREVIEW_SECRET_KEY");
    }

    @Test
    @DisplayName("a malformed or short key fails at construction")
    void rejectsABadKey() {
        // At startup, where an operator can act on it — not at the first save.
        assertThatThrownBy(() -> new SecretCipher("not-base64!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");

        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString("short".getBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16, 24, or 32 bytes");
    }
}

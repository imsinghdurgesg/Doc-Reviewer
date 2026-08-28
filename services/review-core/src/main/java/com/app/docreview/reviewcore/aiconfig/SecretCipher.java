package com.app.docreview.reviewcore.aiconfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts the provider API key at rest. Architecture §30, §35.
 *
 * <p><strong>This moves the secret rather than removing it.</strong> The encryption key lives in
 * environment configuration, so a deployment still has exactly one credential to protect — what is
 * gained is that the PROVIDER key survives a restart and can be rotated from the Admin screen without
 * a redeploy, and that a Mongo backup or an accidental collection dump does not hand over a working
 * API key.
 *
 * <p>AES-GCM, not AES-CBC. GCM authenticates the ciphertext, so a tampered or truncated value fails
 * to decrypt instead of yielding plausible-looking garbage that is then sent to a provider as a
 * credential. A fresh 12-byte IV is generated per encryption and prefixed to the output — reusing an
 * IV under the same key is the one mistake that breaks GCM completely.
 *
 * <p><strong>No plaintext fallback.</strong> When the key is unset, {@link #encrypt} throws rather
 * than storing the value as-is. A silent fallback would put a live API key in the database in exactly
 * the deployment that forgot to configure protection for it.
 */
@Component
public class SecretCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    /**
     * @param base64Key 32 bytes, base64-encoded — generate with
     *                  {@code openssl rand -base64 32}. Blank disables storage entirely.
     */
    public SecretCipher(@Value("${docreview.security.secret-key:}") String base64Key) {
        this.key = parseKey(base64Key);
    }

    private static SecretKeySpec parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "DOCREVIEW_SECRET_KEY is not valid base64. Generate one with "
                            + "`openssl rand -base64 32`.");
        }
        if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
            // Named explicitly: a short key is accepted by no AES implementation, and the JCE error
            // for it ("Invalid AES key length") does not say which property was wrong.
            throw new IllegalStateException(
                    "DOCREVIEW_SECRET_KEY must decode to 16, 24, or 32 bytes; got " + decoded.length
                            + ". Generate one with `openssl rand -base64 32`.");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    /** Whether a key was configured, and therefore whether secrets can be stored at all. */
    public boolean isConfigured() {
        return key != null;
    }

    /** @return base64 of {@code iv || ciphertext || tag} */
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            var cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Never include the exception message: a JCE failure can echo key material length and
            // other details that belong in neither a log nor an API response (§35).
            throw new IllegalStateException("Failed to encrypt the provider key.");
        }
    }

    public String decrypt(String stored) {
        requireKey();
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);

            var cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Authentication failure lands here too, which is the point of GCM: a rotated
            // DOCREVIEW_SECRET_KEY makes old values undecryptable rather than silently wrong.
            throw new IllegalStateException(
                    "Failed to decrypt the stored provider key. It was encrypted with a different "
                            + "DOCREVIEW_SECRET_KEY; re-enter the key in Admin.");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "DOCREVIEW_SECRET_KEY is not set, so provider keys cannot be stored. Generate "
                            + "one with `openssl rand -base64 32` and restart.");
        }
    }
}

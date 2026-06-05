package com.wallet.core.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption for the vault, ported from the React Native vault's
 * AES-GCM design. A passphrase is stretched into a 256-bit key with PBKDF2, and
 * each item is sealed with AES-256-GCM under a fresh 96-bit nonce. GCM's tag
 * authenticates the ciphertext, so any tampering (or a wrong key) fails the open.
 *
 * <p>Pure JDK ({@code javax.crypto}) — it runs unchanged on the desktop JVM (so
 * it's unit-tested with no device) and on Android.
 */
public final class VaultCrypto {

    public static final int IV_BYTES = 12;          // 96-bit GCM nonce (recommended)
    public static final int TAG_BITS = 128;
    public static final int SALT_BYTES = 16;
    public static final int KEY_BITS = 256;
    public static final int DEFAULT_ITERATIONS = 210_000;   // OWASP PBKDF2-HMAC-SHA256 floor

    private static final byte VERSION = 1;
    private static final SecureRandom RNG = new SecureRandom();

    private VaultCrypto() {}

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] newSalt() {
        return randomBytes(SALT_BYTES);
    }

    /** Stretch a passphrase into an AES-256 key. */
    public static SecretKey deriveKey(char[] passphrase, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] key = f.generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Seal plaintext. Output layout: {@code [version:1][iv:12][ciphertext||tag]}.
     * A fresh random nonce is used every call — never reuse a nonce under one key.
     */
    public static byte[] seal(SecretKey key, byte[] plaintext) {
        try {
            byte[] iv = randomBytes(IV_BYTES);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext);

            byte[] out = new byte[1 + IV_BYTES + ct.length];
            out[0] = VERSION;
            System.arraycopy(iv, 0, out, 1, IV_BYTES);
            System.arraycopy(ct, 0, out, 1 + IV_BYTES, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("seal failed", e);
        }
    }

    /** Open a blob produced by {@link #seal}. Throws if tampered or wrong key. */
    public static byte[] open(SecretKey key, byte[] blob) throws GeneralSecurityException {
        if (blob == null || blob.length < 1 + IV_BYTES + TAG_BITS / 8 || blob[0] != VERSION) {
            throw new GeneralSecurityException("unrecognised or truncated vault blob");
        }
        byte[] iv = Arrays.copyOfRange(blob, 1, 1 + IV_BYTES);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return c.doFinal(blob, 1 + IV_BYTES, blob.length - 1 - IV_BYTES);
    }
}

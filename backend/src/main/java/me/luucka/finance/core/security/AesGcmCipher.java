package me.luucka.finance.core.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption (AES-256-GCM) for small secrets stored in the database,
 * such as TOTP seeds. Output format: {@code v1:} + Base64(IV || ciphertext || tag).
 * <p>
 * An optional "associated data" string binds a ciphertext to its owner (e.g. the user id),
 * so a ciphertext copied to another row fails to decrypt.
 */
public final class AesGcmCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random;

    /**
     * @param key32 exactly 32 bytes of key material
     */
    public AesGcmCipher(byte[] key32, SecureRandom random) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes");
        }
        this.key = new SecretKeySpec(key32.clone(), "AES");
        this.random = random;
    }

    /** Creates a cipher from a Base64-encoded 32-byte key. */
    public static AesGcmCipher fromBase64Key(String base64Key, SecureRandom random) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Encryption key is not valid Base64", e);
        }
        return new AesGcmCipher(decoded, random);
    }

    public String encrypt(String plaintext, String associatedData) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * @throws IllegalArgumentException when the value is malformed, was tampered with,
     *                                  or was encrypted with a different key or associated data
     */
    public String decrypt(String encrypted, String associatedData) {
        if (encrypted == null || !encrypted.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Unsupported ciphertext format");
        }
        byte[] data = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
        if (data.length <= IV_BYTES) {
            throw new IllegalArgumentException("Ciphertext too short");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Decryption failed", e);
        }
    }
}

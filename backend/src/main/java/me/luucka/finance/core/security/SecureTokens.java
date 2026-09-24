package me.luucka.finance.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Random tokens for recovery codes and generated passwords.
 */
public final class SecureTokens {

    /** Unambiguous characters (no 0/O, 1/I/L) so codes are easy to type from paper. */
    private static final char[] RECOVERY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_.!".toCharArray();

    private SecureTokens() {
    }

    /** A recovery code formatted as {@code XXXXX-XXXXX} (~49 bits of entropy). */
    public static String recoveryCode(SecureRandom random) {
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** A random password of the given length. */
    public static String password(SecureRandom random, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** Normalizes user input of a recovery code (case, spaces, dashes). */
    public static String normalizeRecoveryCode(String input) {
        String compact = input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (compact.length() != 10) {
            return compact;
        }
        return compact.substring(0, 5) + "-" + compact.substring(5);
    }

    /**
     * SHA-256 hex digest. Adequate for high-entropy random values such as recovery codes;
     * never use this for user-chosen passwords.
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

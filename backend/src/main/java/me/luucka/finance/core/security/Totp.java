package me.luucka.finance.core.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238): HMAC-SHA1, 6 digits, 30 second steps.
 * These are the defaults every mainstream authenticator app supports.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    /** Accepted clock drift, in steps, on each side of the current step. */
    public static final int WINDOW = 1;
    private static final int SECRET_BYTES = 20;
    private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};

    private Totp() {
    }

    /** Generates a new random 160-bit secret, Base32 encoded. */
    public static String generateSecret(SecureRandom random) {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base32.encode(bytes);
    }

    /** Builds the {@code otpauth://} URI that authenticator apps read from a QR code. */
    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    public static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    /** Computes the code for a given time step. */
    public static String codeAt(String base32Secret, long step) {
        byte[] key = Base32.decode(base32Secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % POW10[DIGITS];
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 not available", e);
        }
    }

    /**
     * Verifies a code against the current time, allowing {@link #WINDOW} steps of drift.
     * Comparison is constant-time.
     *
     * @return the matching time step (store it to reject replays), or empty when invalid
     */
    public static OptionalLong verify(String base32Secret, String code, Instant now) {
        if (code == null) {
            return OptionalLong.empty();
        }
        String normalized = code.replace(" ", "");
        if (normalized.length() != DIGITS || !normalized.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        long current = stepAt(now);
        byte[] provided = normalized.getBytes(StandardCharsets.US_ASCII);
        long matched = Long.MIN_VALUE;
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            byte[] expected = codeAt(base32Secret, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, provided)) {
                matched = step;
            }
        }
        return matched == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

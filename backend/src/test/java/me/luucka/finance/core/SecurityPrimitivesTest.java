package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import me.luucka.finance.core.security.AesGcmCipher;
import me.luucka.finance.core.security.Base32;
import me.luucka.finance.core.security.PasswordPolicy;
import me.luucka.finance.core.security.SecureTokens;
import me.luucka.finance.core.security.Totp;
import org.junit.jupiter.api.Test;

class SecurityPrimitivesTest {

    /** RFC 6238 appendix B test secret ("12345678901234567890"). */
    private static final String RFC_SECRET =
            Base32.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void base32RoundTrip() {
        byte[] data = new byte[37];
        new SecureRandom().nextBytes(data);
        assertArrayEquals(data, Base32.decode(Base32.encode(data)));
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void totpMatchesRfc6238Vectors() {
        // RFC 6238 SHA1 vectors, truncated to 6 digits
        assertEquals("287082", Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(59))));
        assertEquals("081804", Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111109))));
        assertEquals("050471", Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111111))));
        assertEquals("005924", Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1234567890))));
        assertEquals("279037", Totp.codeAt(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(2000000000))));
    }

    @Test
    void totpVerifyAcceptsSmallDriftOnly() {
        Instant now = Instant.ofEpochSecond(1234567890);
        long step = Totp.stepAt(now);
        assertEquals(step, Totp.verify(RFC_SECRET, "005924", now).orElseThrow());
        String previous = Totp.codeAt(RFC_SECRET, step - 1);
        assertEquals(step - 1, Totp.verify(RFC_SECRET, previous, now).orElseThrow());
        String old = Totp.codeAt(RFC_SECRET, step - 3);
        assertTrue(Totp.verify(RFC_SECRET, old, now).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, "abc123", now).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, null, now).isEmpty());
    }

    @Test
    void otpauthUriIsEncoded() {
        String uri = Totp.otpauthUri("Finance Tracker", "luca", "ABC");
        assertEquals("otpauth://totp/Finance%20Tracker:luca?secret=ABC&issuer=Finance%20Tracker"
                + "&algorithm=SHA1&digits=6&period=30", uri);
    }

    @Test
    void aesGcmRoundTripAndTamperDetection() {
        byte[] key = new byte[32];
        SecureRandom random = new SecureRandom();
        random.nextBytes(key);
        AesGcmCipher cipher = AesGcmCipher.fromBase64Key(Base64.getEncoder().encodeToString(key), random);

        String encrypted = cipher.encrypt("JBSWY3DPEHPK3PXP", "user:1");
        assertTrue(encrypted.startsWith("v1:"));
        assertNotEquals(encrypted, cipher.encrypt("JBSWY3DPEHPK3PXP", "user:1"));
        assertEquals("JBSWY3DPEHPK3PXP", cipher.decrypt(encrypted, "user:1"));

        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(encrypted, "user:2"));
        byte[] raw = Base64.getDecoder().decode(encrypted.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(tampered, "user:1"));
        assertThrows(IllegalArgumentException.class, () -> new AesGcmCipher(new byte[16], random));
    }

    @Test
    void recoveryCodes() {
        String code = SecureTokens.recoveryCode(new SecureRandom());
        assertTrue(code.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"), code);
        assertEquals(code, SecureTokens.normalizeRecoveryCode(code.toLowerCase().replace("-", " ")));
        assertEquals(64, SecureTokens.sha256Hex(code).length());
    }

    @Test
    void passwordPolicy() {
        assertTrue(PasswordPolicy.validate("correct horse battery", "luca").isEmpty());
        assertEquals(1, PasswordPolicy.validate("short", "luca").size());
        assertTrue(PasswordPolicy.validate("password1234", "x").contains("Password is too common"));
        // Entries from the bundled leak list, compared case-insensitively
        assertTrue(PasswordPolicy.validate("ILoveYouForever", "x").contains("Password is too common"));
        assertTrue(PasswordPolicy.validate("qwertyuiop123", "x").contains("Password is too common"));
        assertTrue(PasswordPolicy.validate("luca-is-the-best-1", "luca")
                .contains("Password must not contain the username"));
        assertTrue(PasswordPolicy.validate("aaaaaaaaaaaaaaab", "x").contains("Password is too repetitive"));
    }
}

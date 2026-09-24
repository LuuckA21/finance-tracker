package me.luucka.finance.auth;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Session attribute set after a correct password when the account has 2FA enabled.
 * The session is <em>not</em> authenticated until the second factor is verified.
 * Immutable: every change is written back with {@code setAttribute} so Spring Session persists it.
 */
record MfaPending(long userId, String username, Instant expiresAt, int failedAttempts) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String SESSION_ATTRIBUTE = MfaPending.class.getName();
    static final int MAX_ATTEMPTS = 5;

    boolean isExpired(Instant now) {
        return now.isAfter(expiresAt) || failedAttempts >= MAX_ATTEMPTS;
    }

    MfaPending withFailure() {
        return new MfaPending(userId, username, expiresAt, failedAttempts + 1);
    }
}

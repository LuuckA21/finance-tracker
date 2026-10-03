package me.luucka.finance.passkey;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * Session attribute holding the random challenge of a passkey ceremony in progress: one for adding
 * a passkey (with the user it is for), one for signing in. Used once, valid a few minutes.
 */
record PasskeyChallenge(byte[] value, Long userId, Instant expiresAt) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String REGISTRATION = PasskeyChallenge.class.getName() + ".registration";
    static final String SIGN_IN = PasskeyChallenge.class.getName() + ".signIn";

    boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}

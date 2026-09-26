package me.luucka.finance.auth;

import java.time.Clock;

import me.luucka.finance.common.ApiException;
import me.luucka.finance.config.AppProperties;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brute-force protection for actions that re-check the password or a second factor inside an
 * existing session (change password, enable/disable 2FA, new recovery codes).
 * <p>
 * Failures count towards the same lockout as the login: with a stolen session cookie an attacker
 * could otherwise guess the password or a TOTP code without limit. When the account locks, every
 * session of the user is revoked, the attacker's included.
 */
@Component
public class ReauthGuard {

    private static final Logger log = LoggerFactory.getLogger(ReauthGuard.class);

    private final AppUserRepository users;
    private final SessionRevoker sessionRevoker;
    private final AppProperties.Login settings;
    private final Clock clock;

    public ReauthGuard(AppUserRepository users, SessionRevoker sessionRevoker, AppProperties properties, Clock clock) {
        this.users = users;
        this.sessionRevoker = sessionRevoker;
        this.settings = properties.login();
        this.clock = clock;
    }

    /** Refuses the check while the account is locked, before any password or code is evaluated. */
    public void ensureNotLocked(AppUser user) {
        if (user.isLocked(clock.instant())) {
            throw tooManyAttempts();
        }
    }

    /**
     * Counts a failed re-check in its own transaction, so it is kept even though the request
     * fails and the caller's transaction rolls back.
     *
     * @return the error to throw: {@code error} itself, or 429 once the account is locked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApiException failure(long userId, ApiException error) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        user.registerFailedLogin(settings.maxFailedAttempts(), settings.lockDuration(), clock.instant());
        users.save(user);
        if (user.isLocked(clock.instant())) {
            log.warn("Account '{}' locked after repeated failed re-authentication; sessions revoked",
                    user.getUsername());
            sessionRevoker.revokeAll(user.getUsername());
            return tooManyAttempts();
        }
        return error;
    }

    /** A correct check clears the counter, as a successful login does. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(long userId) {
        users.findById(userId).ifPresent(user -> {
            if (user.getFailedLoginAttempts() > 0) {
                user.resetFailedLogins();
                users.save(user);
            }
        });
    }

    private static ApiException tooManyAttempts() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                "Too many failed attempts, try again later");
    }
}

package me.luucka.finance.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.config.AppProperties;
import me.luucka.finance.passkey.PasskeyService;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import me.luucka.finance.user.LoginEvent;
import me.luucka.finance.user.LoginEvent.Reason;
import me.luucka.finance.user.LoginEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Two-step login (password, then optional TOTP) with brute-force protection.
 * <p>
 * Not transactional on purpose: failed-attempt counters and audit events must be persisted
 * even though the request ends with an error. Counters are updated with {@link #updateUser}, so
 * simultaneous attempts on one account are all counted instead of failing on the version check.
 * <p>
 * Every failure returns the same generic error so the API does not reveal whether a
 * username exists, is locked or is disabled. Unknown usernames still pay the cost of a
 * password hash to keep response times uniform.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Concurrent updates of one account are retried this many times before giving up. */
    private static final int MAX_UPDATE_ATTEMPTS = 10;

    public enum Outcome {
        AUTHENTICATED,
        MFA_REQUIRED
    }

    private final AppUserRepository users;
    private final LoginEventRepository loginEvents;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;
    private final LoginRateLimiter rateLimiter;
    private final AuthSession authSession;
    private final PasskeyService passkeys;
    private final AppProperties.Login settings;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(AppUserRepository users, LoginEventRepository loginEvents, PasswordEncoder passwordEncoder,
                       MfaService mfaService, LoginRateLimiter rateLimiter, AuthSession authSession,
                       PasskeyService passkeys, AppProperties properties, Clock clock) {
        this.users = users;
        this.loginEvents = loginEvents;
        this.passwordEncoder = passwordEncoder;
        this.mfaService = mfaService;
        this.rateLimiter = rateLimiter;
        this.authSession = authSession;
        this.passkeys = passkeys;
        this.settings = properties.login();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("timing-equalisation-dummy-password");
    }

    public Outcome login(String rawUsername, String password, HttpServletRequest request, HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        String username = normalize(rawUsername);
        Instant now = clock.instant();

        if (rateLimiter.isBlocked(ip)) {
            audit(null, username, request, false, Reason.RATE_LIMITED);
            throw tooManyAttempts();
        }

        Optional<AppUser> maybeUser = users.findByUsernameIgnoreCase(username);
        if (maybeUser.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            throw fail(null, username, request, Reason.UNKNOWN_USER);
        }
        AppUser user = maybeUser.get();

        if (user.isLocked(now)) {
            passwordEncoder.matches(password, dummyHash);
            throw fail(user.getId(), username, request, Reason.LOCKED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            ApiException failure = fail(user.getId(), username, request, Reason.BAD_CREDENTIALS);
            updateUser(user.getId(), u -> u.registerFailedLogin(settings.maxFailedAttempts(), settings.lockDuration(), now));
            throw failure;
        }
        if (!user.isEnabled()) {
            throw fail(user.getId(), username, request, Reason.DISABLED);
        }

        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            String upgraded = passwordEncoder.encode(password);
            user = updateUser(user.getId(), u -> u.setPasswordHash(upgraded));
        }

        if (user.isTotpEnabled()) {
            // Failed-attempt counter is reset only once the second factor succeeds
            authSession.destroy(request);
            HttpSession session = request.getSession(true);
            session.setAttribute(MfaPending.SESSION_ATTRIBUTE,
                    new MfaPending(user.getId(), user.getUsername(), now.plus(settings.mfaTimeout()), 0));
            audit(user.getId(), username, request, false, Reason.MFA_REQUIRED);
            return Outcome.MFA_REQUIRED;
        }

        String name = user.getUsername();
        complete(user.getId(), request, response, Reason.SUCCESS,
                refused -> fail(refused.getId(), name, request, refused.isEnabled() ? Reason.LOCKED : Reason.DISABLED));
        return Outcome.AUTHENTICATED;
    }

    public void verifyMfa(String code, HttpServletRequest request, HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        Instant now = clock.instant();
        if (rateLimiter.isBlocked(ip)) {
            throw tooManyAttempts();
        }
        HttpSession session = request.getSession(false);
        MfaPending pending = session == null ? null
                : (MfaPending) session.getAttribute(MfaPending.SESSION_ATTRIBUTE);
        if (pending == null || pending.isExpired(now)) {
            if (session != null) {
                session.removeAttribute(MfaPending.SESSION_ATTRIBUTE);
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "mfa_expired", "Login expired, please sign in again");
        }
        // Check the account before the code: a recovery code must not be used up on an account
        // that was disabled or locked in the meantime
        boolean usable = users.findById(pending.userId())
                .filter(AppUser::isEnabled)
                .filter(u -> !u.isLocked(now))
                .isPresent();
        if (!usable) {
            session.removeAttribute(MfaPending.SESSION_ATTRIBUTE);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "mfa_expired", "Login expired, please sign in again");
        }

        MfaService.Verification result = mfaService.verifySecondFactor(pending.userId(), code);
        if (result == MfaService.Verification.INVALID) {
            MfaPending updated = pending.withFailure();
            session.setAttribute(MfaPending.SESSION_ATTRIBUTE, updated);
            if (users.existsById(pending.userId())) {
                updateUser(pending.userId(),
                        u -> u.registerFailedLogin(settings.maxFailedAttempts(), settings.lockDuration(), now));
            }
            rateLimiter.recordFailure(ip);
            audit(pending.userId(), pending.username(), request, false, Reason.BAD_MFA_CODE);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_mfa_code", "Invalid code");
        }

        session.removeAttribute(MfaPending.SESSION_ATTRIBUTE);
        complete(pending.userId(), request, response,
                result == MfaService.Verification.RECOVERY_CODE ? Reason.RECOVERY_CODE_USED : Reason.SUCCESS,
                refused -> new ApiException(HttpStatus.UNAUTHORIZED, "mfa_expired", "Login expired, please sign in again"));
    }

    /**
     * Signs in with a passkey: the device verified its user, so it stands for both factors and
     * no 2FA code follows. Failures count towards the per-IP limit, like wrong passwords.
     */
    public void loginWithPasskey(String credentialJson, HttpServletRequest request, HttpServletResponse response) {
        if (rateLimiter.isBlocked(request.getRemoteAddr())) {
            audit(null, "", request, false, Reason.RATE_LIMITED);
            throw tooManyAttempts();
        }
        long userId;
        try {
            userId = passkeys.verifySignIn(credentialJson, request.getSession(false));
        } catch (PasskeyService.Rejected e) {
            rateLimiter.recordFailure(request.getRemoteAddr());
            String name = e.userId() == null ? "" : users.findById(e.userId()).map(AppUser::getUsername).orElse("");
            audit(e.userId(), name, request, false, Reason.BAD_PASSKEY);
            log.warn("Rejected passkey sign-in from {}: {}", request.getRemoteAddr(), e.getMessage());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "passkey_rejected", "The passkey was not accepted");
        }
        complete(userId, request, response, Reason.PASSKEY, refused -> {
            rateLimiter.recordFailure(request.getRemoteAddr());
            audit(refused.getId(), refused.getUsername(), request, false,
                    refused.isEnabled() ? Reason.LOCKED : Reason.DISABLED);
            return new ApiException(HttpStatus.UNAUTHORIZED, "passkey_rejected", "The passkey was not accepted");
        });
    }

    public void logout(HttpServletRequest request) {
        authSession.destroy(request);
    }

    /**
     * Records the successful login and opens the session; {@code refusal} builds the error for an
     * account that was disabled or locked in the meantime.
     */
    private void complete(long userId, HttpServletRequest request, HttpServletResponse response, Reason reason,
                          Function<AppUser, ApiException> refusal) {
        Instant now = clock.instant();
        AppUser saved = updateUser(userId, user -> {
            // Checked again on the fresh copy: the account may have been disabled or locked meanwhile
            if (!user.isEnabled() || user.isLocked(now)) {
                throw refusal.apply(user);
            }
            user.resetFailedLogins();
            user.setLastLoginAt(now);
        });
        authSession.establish(saved, request, response);
        rateLimiter.reset(request.getRemoteAddr());
        audit(saved.getId(), saved.getUsername(), request, true, reason);
        log.info("User '{}' logged in from {}", saved.getUsername(), request.getRemoteAddr());
    }

    /**
     * Applies {@code change} to the stored account and saves it. On a concurrent update (another
     * login of the same account at the same moment) the account is read again and the change
     * re-applied, so no attempt is lost and no login fails with a version conflict.
     */
    private AppUser updateUser(long userId, Consumer<AppUser> change) {
        for (int attempt = 1; ; attempt++) {
            AppUser user = users.findById(userId).orElseThrow(() ->
                    new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid username or password"));
            change.accept(user);
            try {
                return users.save(user);
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= MAX_UPDATE_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    /** Records a failed attempt and returns the generic error to throw. */
    private ApiException fail(Long userId, String username, HttpServletRequest request, Reason reason) {
        rateLimiter.recordFailure(request.getRemoteAddr());
        audit(userId, username, request, false, reason);
        log.warn("Failed login for '{}' from {}: {}", username, request.getRemoteAddr(), reason);
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid username or password");
    }

    private void audit(Long userId, String username, HttpServletRequest request, boolean success, Reason reason) {
        loginEvents.save(new LoginEvent(userId, username, request.getRemoteAddr(),
                request.getHeader("User-Agent"), success, reason));
    }

    private static ApiException tooManyAttempts() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                "Too many failed attempts, try again later");
    }

    static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}

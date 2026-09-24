package me.luucka.finance.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.config.AppProperties;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import me.luucka.finance.user.LoginEvent;
import me.luucka.finance.user.LoginEvent.Reason;
import me.luucka.finance.user.LoginEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Two-step login (password, then optional TOTP) with brute-force protection.
 * <p>
 * Not transactional on purpose: failed-attempt counters and audit events must be persisted
 * even though the request ends with an error.
 * <p>
 * Every failure returns the same generic error so the API does not reveal whether a
 * username exists, is locked or is disabled. Unknown usernames still pay the cost of a
 * password hash to keep response times uniform.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

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
    private final AppProperties.Login settings;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(AppUserRepository users, LoginEventRepository loginEvents, PasswordEncoder passwordEncoder,
                       MfaService mfaService, LoginRateLimiter rateLimiter, AuthSession authSession,
                       AppProperties properties, Clock clock) {
        this.users = users;
        this.loginEvents = loginEvents;
        this.passwordEncoder = passwordEncoder;
        this.mfaService = mfaService;
        this.rateLimiter = rateLimiter;
        this.authSession = authSession;
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
            user.registerFailedLogin(settings.maxFailedAttempts(), settings.lockDuration(), now);
            users.save(user);
            throw fail(user.getId(), username, request, Reason.BAD_CREDENTIALS);
        }
        if (!user.isEnabled()) {
            throw fail(user.getId(), username, request, Reason.DISABLED);
        }

        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(password));
        }

        if (user.isTotpEnabled()) {
            // Failed-attempt counter is reset only once the second factor succeeds
            user = users.save(user);
            authSession.destroy(request);
            HttpSession session = request.getSession(true);
            session.setAttribute(MfaPending.SESSION_ATTRIBUTE,
                    new MfaPending(user.getId(), user.getUsername(), now.plus(settings.mfaTimeout()), 0));
            audit(user.getId(), username, request, false, Reason.MFA_REQUIRED);
            return Outcome.MFA_REQUIRED;
        }

        user.resetFailedLogins();
        complete(user, request, response, Reason.SUCCESS);
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

        MfaService.Verification result = mfaService.verifySecondFactor(pending.userId(), code);
        if (result == MfaService.Verification.INVALID) {
            MfaPending updated = pending.withFailure();
            session.setAttribute(MfaPending.SESSION_ATTRIBUTE, updated);
            users.findById(pending.userId()).ifPresent(user -> {
                user.registerFailedLogin(settings.maxFailedAttempts(), settings.lockDuration(), now);
                users.save(user);
            });
            rateLimiter.recordFailure(ip);
            audit(pending.userId(), pending.username(), request, false, Reason.BAD_MFA_CODE);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_mfa_code", "Invalid code");
        }

        AppUser user = users.findById(pending.userId())
                .filter(AppUser::isEnabled)
                .filter(u -> !u.isLocked(now))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "mfa_expired",
                        "Login expired, please sign in again"));
        session.removeAttribute(MfaPending.SESSION_ATTRIBUTE);
        user.resetFailedLogins();
        complete(user, request, response,
                result == MfaService.Verification.RECOVERY_CODE ? Reason.RECOVERY_CODE_USED : Reason.SUCCESS);
    }

    public void logout(HttpServletRequest request) {
        authSession.destroy(request);
    }

    private void complete(AppUser user, HttpServletRequest request, HttpServletResponse response, Reason reason) {
        user.setLastLoginAt(clock.instant());
        AppUser saved = users.save(user);
        authSession.establish(saved, request, response);
        rateLimiter.reset(request.getRemoteAddr());
        audit(saved.getId(), saved.getUsername(), request, true, reason);
        log.info("User '{}' logged in from {}", saved.getUsername(), request.getRemoteAddr());
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

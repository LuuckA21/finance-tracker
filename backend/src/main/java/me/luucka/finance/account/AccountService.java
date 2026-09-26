package me.luucka.finance.account;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import me.luucka.finance.auth.AuthSession;
import me.luucka.finance.auth.MfaService;
import me.luucka.finance.auth.ReauthGuard;
import me.luucka.finance.auth.SessionRevoker;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.Currencies;
import me.luucka.finance.core.security.PasswordPolicy;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import me.luucka.finance.user.Language;
import me.luucka.finance.user.LoginEvent;
import me.luucka.finance.user.LoginEventRepository;
import me.luucka.finance.user.Theme;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    public record LoginEventResponse(java.time.Instant at, String ipAddress, String userAgent, boolean success,
                                     String reason) {
        static LoginEventResponse of(LoginEvent e) {
            return new LoginEventResponse(e.getCreatedAt(), e.getIpAddress(), e.getUserAgent(), e.isSuccess(),
                    e.getReason());
        }
    }

    private final AppUserRepository users;
    private final LoginEventRepository loginEvents;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;
    private final SessionRevoker sessionRevoker;
    private final AuthSession authSession;
    private final ReauthGuard reauthGuard;

    public AccountService(AppUserRepository users, LoginEventRepository loginEvents, PasswordEncoder passwordEncoder,
                          MfaService mfaService, SessionRevoker sessionRevoker, AuthSession authSession,
                          ReauthGuard reauthGuard) {
        this.users = users;
        this.loginEvents = loginEvents;
        this.passwordEncoder = passwordEncoder;
        this.mfaService = mfaService;
        this.sessionRevoker = sessionRevoker;
        this.authSession = authSession;
        this.reauthGuard = reauthGuard;
    }

    @Transactional(readOnly = true)
    public MeResponse me(long userId) {
        AppUser user = load(userId);
        return new MeResponse(user.getId(), user.getUsername(), user.getRole(), user.getBaseCurrency(),
                user.getLanguage(), user.getTheme(),
                user.isTotpEnabled(), user.isTotpEnabled() ? mfaService.remainingRecoveryCodes(userId) : 0,
                user.isPasswordChangeRequired());
    }

    /**
     * Changes the password after verifying the current one, then revokes every other
     * session of the user and refreshes the current one.
     */
    public MeResponse changePassword(long userId, String currentPassword, String newPassword,
                                     HttpServletRequest request, HttpServletResponse response) {
        AppUser user = load(userId);
        reauthGuard.ensureNotLocked(user);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw reauthGuard.failure(userId,
                    ApiException.badRequest("invalid_current_password", "Current password is wrong"));
        }
        List<String> violations = PasswordPolicy.validate(newPassword, user.getUsername());
        if (!violations.isEmpty()) {
            throw ApiException.badRequest("weak_password", String.join("; ", violations));
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("password_reused", "New password must differ from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangeRequired(false);
        user.resetFailedLogins();
        AppUser saved = users.save(user);

        HttpSession session = request.getSession(false);
        sessionRevoker.revokeAllExcept(saved.getUsername(), session == null ? null : session.getId());
        authSession.refresh(saved, request, response);
        return me(userId);
    }

    /** Updates the preferences that are present; {@code null} leaves a preference unchanged. */
    @Transactional
    public MeResponse updateSettings(long userId, String baseCurrency, Language language, Theme theme) {
        AppUser user = load(userId);
        if (baseCurrency != null) {
            user.setBaseCurrency(Currencies.normalize(baseCurrency));
        }
        if (language != null) {
            user.setLanguage(language);
        }
        if (theme != null) {
            user.setTheme(theme);
        }
        return me(userId);
    }

    @Transactional(readOnly = true)
    public List<LoginEventResponse> loginHistory(long userId) {
        return loginEvents.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(LoginEventResponse::of)
                .toList();
    }

    /** Disables 2FA; requires both the password and a valid second factor. */
    public void disableMfa(long userId, String password, String code) {
        AppUser user = load(userId);
        if (!user.isTotpEnabled()) {
            throw ApiException.badRequest("mfa_not_enabled", "Two-factor authentication is not enabled");
        }
        reauthGuard.ensureNotLocked(user);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw reauthGuard.failure(userId, ApiException.badRequest("invalid_current_password", "Password is wrong"));
        }
        if (mfaService.verifySecondFactor(userId, code) == MfaService.Verification.INVALID) {
            throw reauthGuard.failure(userId, ApiException.badRequest("invalid_mfa_code", "Invalid code"));
        }
        mfaService.disable(userId);
    }

    private AppUser load(long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }
}

package me.luucka.finance.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import me.luucka.finance.common.ApiException;
import me.luucka.finance.config.AppProperties;
import me.luucka.finance.core.security.AesGcmCipher;
import me.luucka.finance.core.security.SecureTokens;
import me.luucka.finance.core.security.Totp;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import me.luucka.finance.user.RecoveryCode;
import me.luucka.finance.user.RecoveryCodeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TOTP two-factor authentication: enrolment, verification and recovery codes.
 */
@Service
public class MfaService {

    public static final int RECOVERY_CODE_COUNT = 10;

    /** Returned when starting enrolment; the secret is shown once for manual entry. */
    public record SetupResponse(String secret, String otpauthUri) {
    }

    /** Outcome of a second-factor check during login. */
    public enum Verification {
        TOTP,
        RECOVERY_CODE,
        INVALID
    }

    private final AppUserRepository users;
    private final RecoveryCodeRepository recoveryCodes;
    private final AesGcmCipher cipher;
    private final SecureRandom random;
    private final Clock clock;
    private final String issuer;
    private final PasswordEncoder passwordEncoder;
    private final ReauthGuard reauthGuard;

    public MfaService(AppUserRepository users, RecoveryCodeRepository recoveryCodes, AesGcmCipher cipher,
                      SecureRandom random, Clock clock, AppProperties properties, PasswordEncoder passwordEncoder,
                      ReauthGuard reauthGuard) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.cipher = cipher;
        this.random = random;
        this.clock = clock;
        this.issuer = properties.totpIssuer();
        this.passwordEncoder = passwordEncoder;
        this.reauthGuard = reauthGuard;
    }

    /** Generates a new (not yet active) secret. Replaces any previous pending secret. */
    @Transactional
    public SetupResponse beginSetup(long userId) {
        AppUser user = load(userId);
        if (user.isTotpEnabled()) {
            throw ApiException.conflict("mfa_already_enabled", "Two-factor authentication is already enabled");
        }
        String secret = Totp.generateSecret(random);
        user.setTotpSecretEncrypted(cipher.encrypt(secret, aad(user)));
        user.setTotpEnabled(false);
        user.setTotpLastStep(null);
        return new SetupResponse(secret, Totp.otpauthUri(issuer, user.getUsername(), secret));
    }

    /**
     * Activates 2FA after the user proves the authenticator works. The current password is
     * required too: otherwise a stolen session could enrol the attacker's authenticator and lock
     * the owner out of the account.
     *
     * @return freshly generated recovery codes (shown once)
     */
    @Transactional
    public List<String> confirmSetup(long userId, String password, String code) {
        AppUser user = load(userId);
        if (user.isTotpEnabled()) {
            throw ApiException.conflict("mfa_already_enabled", "Two-factor authentication is already enabled");
        }
        if (user.getTotpSecretEncrypted() == null) {
            throw ApiException.badRequest("mfa_setup_not_started", "Start the setup first");
        }
        reauthGuard.ensureNotLocked(user);
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw reauthGuard.failure(userId,
                    ApiException.badRequest("invalid_current_password", "Current password is wrong"));
        }
        OptionalLong step = Totp.verify(secretOf(user), code, clock.instant());
        if (step.isEmpty()) {
            throw reauthGuard.failure(userId, ApiException.badRequest("invalid_mfa_code", "Invalid code"));
        }
        // Reset on the managed entity: a separate transaction would clash with its version
        user.resetFailedLogins();
        user.setTotpEnabled(true);
        user.setTotpLastStep(step.getAsLong());
        return regenerateRecoveryCodes(user);
    }

    /** Replaces all recovery codes after verifying a current TOTP code. */
    @Transactional
    public List<String> newRecoveryCodes(long userId, String code) {
        AppUser user = load(userId);
        if (!user.isTotpEnabled()) {
            throw ApiException.badRequest("mfa_not_enabled", "Two-factor authentication is not enabled");
        }
        reauthGuard.ensureNotLocked(user);
        if (!verifyTotp(user, code)) {
            throw reauthGuard.failure(userId, ApiException.badRequest("invalid_mfa_code", "Invalid code"));
        }
        user.resetFailedLogins();
        return regenerateRecoveryCodes(user);
    }

    @Transactional
    public void disable(long userId) {
        AppUser user = load(userId);
        user.clearTotp();
        user.resetFailedLogins();
        recoveryCodes.deleteAllForUser(userId);
    }

    public long remainingRecoveryCodes(long userId) {
        return recoveryCodes.countByUserIdAndUsedAtIsNull(userId);
    }

    /**
     * Checks a TOTP code or, failing that, a single-use recovery code.
     * Updates replay protection / marks the recovery code as used.
     */
    @Transactional
    public Verification verifySecondFactor(long userId, String code) {
        AppUser user = load(userId);
        if (!user.isTotpEnabled() || code == null || code.isBlank()) {
            return Verification.INVALID;
        }
        String compact = code.replace(" ", "");
        if (compact.length() == Totp.DIGITS && compact.chars().allMatch(Character::isDigit)) {
            return verifyTotp(user, compact) ? Verification.TOTP : Verification.INVALID;
        }
        String hash = SecureTokens.sha256Hex(SecureTokens.normalizeRecoveryCode(code));
        Optional<RecoveryCode> match = recoveryCodes.findFirstByUserIdAndCodeHashAndUsedAtIsNull(userId, hash);
        // Conditional update: two concurrent logins cannot both consume the same code
        if (match.isPresent() && recoveryCodes.markUsed(match.get().getId(), clock.instant()) == 1) {
            return Verification.RECOVERY_CODE;
        }
        return Verification.INVALID;
    }

    /** Verifies a TOTP code and rejects codes from an already used (or older) time step. */
    boolean verifyTotp(AppUser user, String code) {
        OptionalLong step = Totp.verify(secretOf(user), code, clock.instant());
        if (step.isEmpty()) {
            return false;
        }
        Long last = user.getTotpLastStep();
        if (last != null && step.getAsLong() <= last) {
            return false;
        }
        user.setTotpLastStep(step.getAsLong());
        return true;
    }

    private List<String> regenerateRecoveryCodes(AppUser user) {
        recoveryCodes.deleteAllForUser(user.getId());
        List<String> plain = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = SecureTokens.recoveryCode(random);
            plain.add(code);
            recoveryCodes.save(new RecoveryCode(user.getId(), SecureTokens.sha256Hex(code)));
        }
        return plain;
    }

    private String secretOf(AppUser user) {
        try {
            return cipher.decrypt(user.getTotpSecretEncrypted(), aad(user));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "mfa_secret_unreadable",
                    "Stored 2FA secret cannot be decrypted (was the encryption key changed?)");
        }
    }

    private AppUser load(long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    private static String aad(AppUser user) {
        return "totp:" + user.getId();
    }
}

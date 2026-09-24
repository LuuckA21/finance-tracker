package me.luucka.finance.admin;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import me.luucka.finance.auth.SessionRevoker;
import me.luucka.finance.category.CategoryService;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.security.PasswordPolicy;
import me.luucka.finance.core.security.SecureTokens;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import me.luucka.finance.user.RecoveryCodeRepository;
import me.luucka.finance.user.Role;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User management for administrators. There is no public sign-up: every account is created here.
 * Administrators manage accounts only; they cannot read other users' financial data.
 */
@Service
public class UserAdminService {

    public static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
    private static final int TEMP_PASSWORD_LENGTH = 16;

    public record UserResponse(long id, String username, Role role, boolean enabled, boolean mfaEnabled,
                               boolean locked, boolean passwordChangeRequired, Instant lastLoginAt,
                               Instant createdAt) {
        static UserResponse of(AppUser u, Instant now) {
            return new UserResponse(u.getId(), u.getUsername(), u.getRole(), u.isEnabled(), u.isTotpEnabled(),
                    u.isLocked(now), u.isPasswordChangeRequired(), u.getLastLoginAt(), u.getCreatedAt());
        }
    }

    /** A user together with a one-time temporary password to hand over out of band. */
    public record UserWithPassword(UserResponse user, String temporaryPassword) {
    }

    private final AppUserRepository users;
    private final RecoveryCodeRepository recoveryCodes;
    private final CategoryService categoryService;
    private final PasswordEncoder passwordEncoder;
    private final SessionRevoker sessionRevoker;
    private final SecureRandom random;
    private final Clock clock;

    public UserAdminService(AppUserRepository users, RecoveryCodeRepository recoveryCodes,
                            CategoryService categoryService, PasswordEncoder passwordEncoder,
                            SessionRevoker sessionRevoker, SecureRandom random, Clock clock) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.categoryService = categoryService;
        this.passwordEncoder = passwordEncoder;
        this.sessionRevoker = sessionRevoker;
        this.random = random;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        Instant now = clock.instant();
        return users.findAllByOrderByUsernameAsc().stream().map(u -> UserResponse.of(u, now)).toList();
    }

    /**
     * Creates a user with default categories. When {@code password} is null a random one is
     * generated. The user must change it at first login either way.
     */
    @Transactional
    public UserWithPassword create(String rawUsername, Role role, String password) {
        String username = rawUsername.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw ApiException.badRequest("invalid_username",
                    "Username must be 3-64 characters: lowercase letters, digits, '.', '_' or '-'");
        }
        if (users.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("username_taken", "Username already exists");
        }
        String initial = password != null ? password : SecureTokens.password(random, TEMP_PASSWORD_LENGTH);
        List<String> violations = PasswordPolicy.validate(initial, username);
        if (!violations.isEmpty()) {
            throw ApiException.badRequest("weak_password", String.join("; ", violations));
        }
        AppUser user = new AppUser(username, passwordEncoder.encode(initial), role);
        user.setPasswordChangeRequired(true);
        user = users.save(user);
        categoryService.createDefaults(user.getId());
        return new UserWithPassword(UserResponse.of(user, clock.instant()), password == null ? initial : null);
    }

    @Transactional
    public UserResponse update(long actingUserId, long id, Role role, Boolean enabled) {
        AppUser user = load(id);
        if (id == actingUserId && ((role != null && role != Role.ADMIN) || Boolean.FALSE.equals(enabled))) {
            throw ApiException.badRequest("cannot_modify_self", "You cannot demote or disable your own account");
        }
        if (role != null) {
            user.setRole(role);
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        ensureAnAdminRemains();
        if (id != actingUserId) {
            // Role and status are part of the session principal: force a fresh login
            sessionRevoker.revokeAll(user.getUsername());
        }
        return UserResponse.of(user, clock.instant());
    }

    @Transactional
    public UserWithPassword resetPassword(long id) {
        AppUser user = load(id);
        String temporary = SecureTokens.password(random, TEMP_PASSWORD_LENGTH);
        user.setPasswordHash(passwordEncoder.encode(temporary));
        user.setPasswordChangeRequired(true);
        user.resetFailedLogins();
        sessionRevoker.revokeAll(user.getUsername());
        return new UserWithPassword(UserResponse.of(user, clock.instant()), temporary);
    }

    @Transactional
    public UserResponse unlock(long id) {
        AppUser user = load(id);
        user.resetFailedLogins();
        return UserResponse.of(user, clock.instant());
    }

    @Transactional
    public UserResponse resetMfa(long id) {
        AppUser user = load(id);
        user.clearTotp();
        recoveryCodes.deleteAllForUser(id);
        sessionRevoker.revokeAll(user.getUsername());
        return UserResponse.of(user, clock.instant());
    }

    /** Deletes the user and, by cascade, all of their data. */
    @Transactional
    public void delete(long actingUserId, long id) {
        if (id == actingUserId) {
            throw ApiException.badRequest("cannot_modify_self", "You cannot delete your own account");
        }
        AppUser user = load(id);
        sessionRevoker.revokeAll(user.getUsername());
        users.delete(user);
    }

    private void ensureAnAdminRemains() {
        users.flush();
        if (users.countByRoleAndEnabledTrue(Role.ADMIN) == 0) {
            throw ApiException.badRequest("last_admin", "At least one enabled administrator is required");
        }
    }

    private AppUser load(long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }
}

package me.luucka.finance.account;

import me.luucka.finance.user.Role;

/**
 * The logged-in user's own profile.
 */
public record MeResponse(
        long id,
        String username,
        Role role,
        String baseCurrency,
        boolean mfaEnabled,
        long recoveryCodesRemaining,
        boolean passwordChangeRequired) {
}

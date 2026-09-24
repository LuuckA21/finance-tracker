package me.luucka.finance.account;

import me.luucka.finance.user.Language;
import me.luucka.finance.user.Role;
import me.luucka.finance.user.Theme;

/**
 * The logged-in user's own profile.
 */
public record MeResponse(
        long id,
        String username,
        Role role,
        String baseCurrency,
        Language language,
        Theme theme,
        boolean mfaEnabled,
        long recoveryCodesRemaining,
        boolean passwordChangeRequired) {
}

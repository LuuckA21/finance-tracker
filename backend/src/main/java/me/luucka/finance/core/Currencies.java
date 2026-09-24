package me.luucka.finance.core;

import java.util.Currency;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validation and normalisation of ISO 4217 currency codes.
 */
public final class Currencies {

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    private Currencies() {
    }

    /** Upper-cases and trims a code; returns {@code null} for {@code null}. */
    public static String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    /** True when {@code code} is a known ISO 4217 code (after normalisation). */
    public static boolean isValid(String code) {
        String normalized = normalize(code);
        if (normalized == null || !CODE.matcher(normalized).matches()) {
            return false;
        }
        try {
            Currency.getInstance(normalized);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

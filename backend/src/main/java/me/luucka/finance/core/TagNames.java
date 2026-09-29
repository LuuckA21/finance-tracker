package me.luucka.finance.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Rules for tag names, shared by the entry API and the CSV import: trimmed, inner spaces collapsed,
 * 1 to {@value #MAX_LENGTH} characters, no comma (the separator in CSV files), no control
 * characters. Names are compared regardless of case.
 */
public final class TagNames {

    public static final int MAX_LENGTH = 40;
    public static final int MAX_PER_ENTRY = 10;

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern FORBIDDEN = Pattern.compile("[,\\p{Cc}\\p{Cf}]");

    private TagNames() {
    }

    /** The name as stored, or empty when it breaks the rules. */
    public static Optional<String> normalize(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String cleaned = SPACES.matcher(name.strip()).replaceAll(" ");
        if (cleaned.isEmpty() || cleaned.length() > MAX_LENGTH || FORBIDDEN.matcher(cleaned).find()) {
            return Optional.empty();
        }
        return Optional.of(cleaned);
    }

    /** Key for comparing names: "Vacanze" and "vacanze" are the same tag. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Normalized names without duplicates (first spelling kept), or empty when one is invalid or
     * there are more than {@value #MAX_PER_ENTRY}.
     */
    public static Optional<List<String>> normalizeAll(List<String> names) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String name : names) {
            Optional<String> normalized = normalize(name);
            if (normalized.isEmpty()) {
                return Optional.empty();
            }
            unique.putIfAbsent(key(normalized.get()), normalized.get());
        }
        return unique.size() > MAX_PER_ENTRY ? Optional.empty() : Optional.of(new ArrayList<>(unique.values()));
    }

    /** Tags written in one CSV cell, separated by commas; blank parts are skipped. */
    public static Optional<List<String>> parseCell(String cell) {
        List<String> parts = new ArrayList<>();
        for (String part : cell.split(",")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return normalizeAll(parts);
    }
}

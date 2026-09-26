package me.luucka.finance.core.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Password rules following NIST SP 800-63B: favour length over composition rules,
 * reject very common passwords and passwords containing the username.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    /** Argon2 input is bounded to keep hashing cost predictable. */
    public static final int MAX_LENGTH = 128;

    /**
     * Leaked passwords long enough to pass the length rule (lower case), taken from the
     * SecLists xato-net 1M and NCSC 100k lists.
     */
    private static final Set<String> COMMON = load("/security/common-passwords.txt");

    private PasswordPolicy() {
    }

    /**
     * @return human readable violations; empty when the password is acceptable
     */
    public static List<String> validate(String password, String username) {
        List<String> errors = new ArrayList<>();
        if (password == null || password.isBlank()) {
            errors.add("Password is required");
            return errors;
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            errors.add("Password must be at least " + MIN_LENGTH + " characters long");
        }
        if (length > MAX_LENGTH) {
            errors.add("Password must be at most " + MAX_LENGTH + " characters long");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) {
            errors.add("Password is too common");
        }
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase(Locale.ROOT))) {
            errors.add("Password must not contain the username");
        }
        if (password.chars().distinct().count() < 5) {
            errors.add("Password is too repetitive");
        }
        return errors;
    }

    private static Set<String> load(String resource) {
        try (InputStream in = PasswordPolicy.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return reader.lines().filter(line -> !line.isEmpty()).collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

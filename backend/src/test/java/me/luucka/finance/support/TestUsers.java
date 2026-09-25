package me.luucka.finance.support;

import java.util.concurrent.atomic.AtomicInteger;

import me.luucka.finance.category.CategoryService;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import me.luucka.finance.user.Role;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates users directly in the database so tests do not depend on each other
 * (the application context and database are shared between test classes).
 */
@TestComponent
public class TestUsers {

    public static final String PASSWORD = "Correct-Horse-Battery-9";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CategoryService categoryService;

    public TestUsers(AppUserRepository users, PasswordEncoder passwordEncoder, CategoryService categoryService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.categoryService = categoryService;
    }

    public AppUser create(String prefix, Role role) {
        return create(prefix, role, false);
    }

    public AppUser create(String prefix, Role role, boolean passwordChangeRequired) {
        String username = prefix + "-" + System.nanoTime() % 1_000_000 + "-" + COUNTER.incrementAndGet();
        AppUser user = new AppUser(username, passwordEncoder.encode(PASSWORD), role);
        user.setPasswordChangeRequired(passwordChangeRequired);
        user = users.save(user);
        categoryService.createDefaults(user.getId(), user.getLanguage());
        return user;
    }
}

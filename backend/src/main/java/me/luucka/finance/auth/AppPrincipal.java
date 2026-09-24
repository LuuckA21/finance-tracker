package me.luucka.finance.auth;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.List;

import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authenticated user stored in the (persisted) HTTP session.
 * <p>
 * Implements {@link Principal} so that {@code Authentication#getName()} returns the username,
 * which Spring Session indexes to allow revoking all sessions of a user.
 */
public record AppPrincipal(long id, String username, Role role, boolean passwordChangeRequired)
        implements Principal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static AppPrincipal of(AppUser user) {
        return new AppPrincipal(user.getId(), user.getUsername(), user.getRole(), user.isPasswordChangeRequired());
    }

    @Override
    public String getName() {
        return username;
    }

    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}

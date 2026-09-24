package me.luucka.finance.auth;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * While a user must change a temporary password, only the endpoints needed to do so are
 * reachable; everything else answers 403 with code {@code password_change_required}.
 * <p>
 * Not a Spring bean on purpose: it is registered only inside the security filter chain.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED = Set.of(
            "/api/auth/me",
            "/api/auth/csrf",
            "/api/auth/logout",
            "/api/account/password");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.getPrincipal() instanceof AppPrincipal principal
                && principal.passwordChangeRequired()
                && !ALLOWED.contains(request.getRequestURI())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"status":403,"title":"Forbidden","detail":"Password change required",\
                    "code":"password_change_required"}""");
            return;
        }
        chain.doFilter(request, response);
    }
}

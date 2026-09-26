package me.luucka.finance.auth;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a login session a fixed time after the login, however active it is (the servlet
 * session timeout only covers inactivity). An expired session is invalidated and the request
 * continues as anonymous, so protected endpoints answer 401 and the SPA shows the login page.
 * <p>
 * Not a Spring bean on purpose: it is registered only inside the security filter chain,
 * before the anonymous filter.
 */
public class SessionLifetimeFilter extends OncePerRequestFilter {

    /** Session attribute: epoch millis of the completed login, set by {@link AuthSession}. */
    public static final String AUTHENTICATED_AT = "ft.authenticatedAt";

    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final Clock clock;
    private final Duration maxLifetime;

    public SessionLifetimeFilter(Clock clock, Duration maxLifetime) {
        this.clock = clock;
        this.maxLifetime = maxLifetime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication auth = holder.getContext().getAuthentication();
        if (session != null && auth != null && auth.getPrincipal() instanceof AppPrincipal) {
            Instant now = clock.instant();
            if (!(session.getAttribute(AUTHENTICATED_AT) instanceof Long loggedInAt)) {
                // Session opened before this check existed: its lifetime starts now
                session.setAttribute(AUTHENTICATED_AT, now.toEpochMilli());
            } else if (now.isAfter(Instant.ofEpochMilli(loggedInAt).plus(maxLifetime))) {
                session.invalidate();
                holder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}

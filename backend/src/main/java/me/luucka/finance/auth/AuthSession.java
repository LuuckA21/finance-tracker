package me.luucka.finance.auth;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import me.luucka.finance.user.AppUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Establishes, refreshes and destroys the authenticated HTTP session.
 */
@Component
public class AuthSession {

    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final Clock clock;

    public AuthSession(SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokenRepository,
                       Clock clock) {
        this.contextRepository = contextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.clock = clock;
    }

    /**
     * Logs the user in: new session id (prevents session fixation), security context saved
     * in the session, CSRF token rotated (the client must fetch a new one), login time recorded
     * for the maximum session lifetime.
     */
    public void establish(AppUser user, HttpServletRequest request, HttpServletResponse response) {
        request.getSession(true);
        request.changeSessionId();
        request.getSession().setAttribute(SessionLifetimeFilter.AUTHENTICATED_AT, clock.millis());
        storePrincipal(AppPrincipal.of(user), request, response);
        csrfTokenRepository.saveToken(null, request, response);
    }

    /** Replaces the principal of the current session, e.g. after a password change. */
    public void refresh(AppUser user, HttpServletRequest request, HttpServletResponse response) {
        storePrincipal(AppPrincipal.of(user), request, response);
    }

    /** Invalidates the current session and clears the security context. */
    public void destroy(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holder.clearContext();
    }

    /** Changes the session id while keeping its content (used between login steps). */
    public void rotateId(HttpServletRequest request) {
        request.getSession(true);
        request.changeSessionId();
    }

    private void storePrincipal(AppPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities());
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}

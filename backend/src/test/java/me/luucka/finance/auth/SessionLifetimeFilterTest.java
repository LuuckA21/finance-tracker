package me.luucka.finance.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import me.luucka.finance.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionLifetimeFilterTest {

    private static final Instant LOGIN = Instant.parse("2026-09-01T08:00:00Z");
    private static final Duration LIFETIME = Duration.ofDays(7);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpSession authenticatedSession(Long loggedInAt) {
        var principal = new AppPrincipal(1, "luca", Role.USER, false);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities()));
        var session = new MockHttpSession();
        if (loggedInAt != null) {
            session.setAttribute(SessionLifetimeFilter.AUTHENTICATED_AT, loggedInAt);
        }
        return session;
    }

    private static MockFilterChain run(Instant now, MockHttpSession session) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setSession(session);
        var chain = new MockFilterChain();
        new SessionLifetimeFilter(Clock.fixed(now, ZoneOffset.UTC), LIFETIME)
                .doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test
    void sessionWithinLifetimeIsKept() throws Exception {
        MockHttpSession session = authenticatedSession(LOGIN.toEpochMilli());
        MockFilterChain chain = run(LOGIN.plus(LIFETIME), session);

        assertFalse(session.isInvalid());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
        assertNotNull(chain.getRequest());
    }

    @Test
    void sessionPastLifetimeIsInvalidatedAndRequestContinuesAnonymous() throws Exception {
        MockHttpSession session = authenticatedSession(LOGIN.toEpochMilli());
        MockFilterChain chain = run(LOGIN.plus(LIFETIME).plusSeconds(1), session);

        assertTrue(session.isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        // The chain still runs: authorization decides (401 on protected endpoints)
        assertNotNull(chain.getRequest());
    }

    @Test
    void sessionWithoutLoginTimeStartsItsLifetimeNow() throws Exception {
        MockHttpSession session = authenticatedSession(null);
        Instant now = LOGIN.plus(Duration.ofDays(30));
        run(now, session);

        assertFalse(session.isInvalid());
        assertEquals(now.toEpochMilli(), session.getAttribute(SessionLifetimeFilter.AUTHENTICATED_AT));
    }

    @Test
    void anonymousSessionIsIgnored() throws Exception {
        var session = new MockHttpSession();
        run(LOGIN, session);

        assertFalse(session.isInvalid());
        assertNull(session.getAttribute(SessionLifetimeFilter.AUTHENTICATED_AT));
    }
}

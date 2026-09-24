package me.luucka.finance.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Deletes persisted sessions of a user, e.g. after a password reset or when an account
 * is disabled, so that stolen or stale sessions stop working immediately.
 */
@Component
public class SessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /** Revokes every session of {@code username}. */
    public void revokeAll(String username) {
        revokeAllExcept(username, null);
    }

    /** Revokes every session of {@code username} except {@code keepSessionId} (may be null). */
    public void revokeAllExcept(String username, String keepSessionId) {
        var ids = sessions.findByPrincipalName(username).keySet();
        int revoked = 0;
        for (String id : ids) {
            if (!id.equals(keepSessionId)) {
                sessions.deleteById(id);
                revoked++;
            }
        }
        if (revoked > 0) {
            log.info("Revoked {} session(s) of user '{}'", revoked, username);
        }
    }
}

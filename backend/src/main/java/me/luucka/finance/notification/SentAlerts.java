package me.luucka.finance.notification;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Alerts already handled per user ({@code notification_sent}), so that none is sent twice. */
@Repository
public class SentAlerts {

    private final JdbcTemplate jdbc;

    public SentAlerts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The keys among {@code keys} already handled for the user. */
    public Set<String> handled(long userId, Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        List<String> found = jdbc.queryForList(
                "select alert_key from notification_sent where user_id = ? and alert_key = any (?)", String.class,
                userId, keys.toArray(String[]::new));
        return new HashSet<>(found);
    }

    public void markHandled(long userId, Collection<String> keys) {
        for (String key : keys) {
            jdbc.update("insert into notification_sent (user_id, alert_key) values (?, ?) on conflict do nothing",
                    userId, key);
        }
    }
}

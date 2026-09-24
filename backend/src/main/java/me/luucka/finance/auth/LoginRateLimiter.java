package me.luucka.finance.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import me.luucka.finance.config.AppProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limits failed authentication attempts per client IP with a fixed window.
 * Complements the per-account lockout, which alone cannot stop password spraying
 * across many usernames. In-memory: fine for a single instance.
 */
@Component
public class LoginRateLimiter {

    private record Window(Instant start, int failures) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration windowLength;
    private final Clock clock;

    public LoginRateLimiter(AppProperties properties, Clock clock) {
        this.maxFailures = properties.login().ipMaxAttempts();
        this.windowLength = properties.login().ipWindow();
        this.clock = clock;
    }

    public boolean isBlocked(String ip) {
        Window window = windows.get(ip);
        return window != null && !expired(window, clock.instant()) && window.failures() >= maxFailures;
    }

    public void recordFailure(String ip) {
        Instant now = clock.instant();
        windows.compute(ip, (key, window) -> window == null || expired(window, now)
                ? new Window(now, 1)
                : new Window(window.start(), window.failures() + 1));
    }

    public void reset(String ip) {
        windows.remove(ip);
    }

    @Scheduled(fixedDelay = 600_000)
    void evictExpired() {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    }

    private boolean expired(Window window, Instant now) {
        return window.start().plus(windowLength).isBefore(now);
    }
}

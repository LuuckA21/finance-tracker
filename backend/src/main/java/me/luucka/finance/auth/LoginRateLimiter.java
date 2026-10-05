package me.luucka.finance.auth;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

import me.luucka.finance.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limits failed authentication attempts per client with a fixed window.
 * Complements the per-account lockout, which alone cannot stop password spraying
 * across many usernames. In-memory: fine for a single instance.
 * <p>
 * A client is an IPv4 address or an IPv6 /64 network: one IPv6 connection usually has a whole /64
 * to pick addresses from. A successful login does not clear the count, or anyone with an account
 * of their own could sign in to it between guesses at the others.
 */
@Component
public class LoginRateLimiter {

    private record Window(Instant start, int failures) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration windowLength;
    private final Clock clock;

    @Autowired
    public LoginRateLimiter(AppProperties properties, Clock clock) {
        this(properties.login().ipMaxAttempts(), properties.login().ipWindow(), clock);
    }

    LoginRateLimiter(int maxFailures, Duration windowLength, Clock clock) {
        this.maxFailures = maxFailures;
        this.windowLength = windowLength;
        this.clock = clock;
    }

    public boolean isBlocked(String ip) {
        Window window = windows.get(client(ip));
        return window != null && !expired(window, clock.instant()) && window.failures() >= maxFailures;
    }

    public void recordFailure(String ip) {
        Instant now = clock.instant();
        windows.compute(client(ip), (key, window) -> window == null || expired(window, now)
                ? new Window(now, 1)
                : new Window(window.start(), window.failures() + 1));
    }

    @Scheduled(fixedDelay = 600_000)
    void evictExpired() {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    }

    private boolean expired(Window window, Instant now) {
        return window.start().plus(windowLength).isBefore(now);
    }

    /** The key an address is counted under: the IPv4 address, or the /64 network of an IPv6 one. */
    static String client(String ip) {
        if (ip == null || ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            // A literal only: never a name lookup
            InetAddress address = InetAddress.ofLiteral(ip);
            byte[] bytes = address.getAddress();
            // An IPv4-mapped address (::ffff:192.0.2.1) is the IPv4 client it maps
            return bytes.length == 4 ? address.getHostAddress() : HexFormat.of().formatHex(bytes, 0, 8) + "/64";
        } catch (IllegalArgumentException e) {
            return ip;
        }
    }
}

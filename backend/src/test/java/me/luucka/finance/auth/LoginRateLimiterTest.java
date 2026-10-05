package me.luucka.finance.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    private final LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(15),
            Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC));

    private void fail(String ip, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(ip);
        }
    }

    @Test
    void blocksAClientAfterTooManyFailures() {
        fail("192.0.2.10", 2);
        assertFalse(limiter.isBlocked("192.0.2.10"));
        fail("192.0.2.10", 1);
        assertTrue(limiter.isBlocked("192.0.2.10"));
        assertFalse(limiter.isBlocked("192.0.2.11"));
    }

    @Test
    void anIpv6NetworkCountsAsOneClient() {
        // Another address of the same /64 is the same client, the next /64 is not
        fail("2001:db8:1:2::1", 2);
        fail("2001:db8:1:2:ffff:abcd:1:2", 1);
        assertTrue(limiter.isBlocked("2001:db8:1:2::99"));
        assertFalse(limiter.isBlocked("2001:db8:1:3::1"));
    }

    @Test
    void clientKeys() {
        assertEquals("192.0.2.1", LoginRateLimiter.client("192.0.2.1"));
        assertEquals("192.0.2.1", LoginRateLimiter.client("::ffff:192.0.2.1"));
        assertEquals("20010db800010002/64", LoginRateLimiter.client("2001:db8:1:2::1"));
        assertEquals("20010db800010002/64", LoginRateLimiter.client("2001:0DB8:0001:0002:aaaa:bbbb:cccc:dddd"));
        // Not an address literal: counted as it is, never looked up
        assertEquals("not:an:address", LoginRateLimiter.client("not:an:address"));
    }
}

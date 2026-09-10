package ee.smit.aiagent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);
        service = new RateLimitService(true, 10, clock);
    }

    @Test
    void eleventhRequestSameKeyIsRejected() {
        String key = "127.0.0.1|test-unique";
        for (int i = 0; i < 10; i++) {
            assertTrue(service.tryAcquire(key), "request " + (i + 1) + " should pass");
        }
        assertFalse(service.tryAcquire(key), "11th request must be rate-limited");
    }

    @Test
    void differentKeysAreIndependent() {
        for (int i = 0; i < 10; i++) {
            assertTrue(service.tryAcquire("ip-a"));
        }
        assertFalse(service.tryAcquire("ip-a"));
        assertTrue(service.tryAcquire("ip-b"));
    }

    @Test
    void disabledAlwaysAllows() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);
        RateLimitService disabled = new RateLimitService(false, 1, clock);
        for (int i = 0; i < 20; i++) {
            assertTrue(disabled.tryAcquire("same"));
        }
    }
}

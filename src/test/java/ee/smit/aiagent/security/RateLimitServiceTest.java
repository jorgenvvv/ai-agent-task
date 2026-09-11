package ee.smit.aiagent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService(true, 10, CLOCK);
    }

    @Test
    void eleventhRequestSameIpIsRejected() {
        String ip = "127.0.0.1";
        for (int i = 0; i < 10; i++) {
            assertTrue(service.tryAcquire(ip), "request " + (i + 1) + " should pass");
        }
        assertFalse(service.tryAcquire(ip), "11th request must be rate-limited");
    }

    @Test
    void differentIpsAreIndependent() {
        for (int i = 0; i < 10; i++) {
            assertTrue(service.tryAcquire("ip-a"));
        }
        assertFalse(service.tryAcquire("ip-a"));
        assertTrue(service.tryAcquire("ip-b"));
    }

    @Test
    void disabledAlwaysAllows() {
        RateLimitService disabled = new RateLimitService(false, 1, CLOCK);
        for (int i = 0; i < 20; i++) {
            assertTrue(disabled.tryAcquire("same"));
        }
    }

    @Test
    void blankIpUsesUnknownBucket() {
        for (int i = 0; i < 10; i++) {
            assertTrue(service.tryAcquire(" "));
        }
        assertFalse(service.tryAcquire(null));
    }
}

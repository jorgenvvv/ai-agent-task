package ee.smit.aiagent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {

    private final boolean enabled;
    private final int requestsPerMinute;
    private final Clock clock;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public RateLimitService(
            @Value("${app.agent.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.agent.rate-limit.requests-per-minute:10}") int requestsPerMinute,
            Clock clock) {
        this.enabled = enabled;
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
        this.clock = clock;
    }

    public boolean tryAcquire(String key) {
        if (!enabled) {
            return true;
        }
        if (key == null || key.isBlank()) {
            key = "unknown";
        }

        long now = clock.millis();
        long windowStart = now - 60_000L;
        Deque<Long> timestamps = windows.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= requestsPerMinute) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    void reset() {
        windows.clear();
    }
}

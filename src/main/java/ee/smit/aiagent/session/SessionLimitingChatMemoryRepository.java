package ee.smit.aiagent.session;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionLimitingChatMemoryRepository implements ChatMemoryRepository {

    private final ChatMemoryRepository delegate;
    private final int maxSessions;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Instant> lastAccess = new ConcurrentHashMap<>();

    public SessionLimitingChatMemoryRepository(
            ChatMemoryRepository delegate,
            int maxSessions,
            Duration ttl,
            Clock clock) {
        Assert.notNull(delegate, "delegate cannot be null");
        Assert.isTrue(maxSessions > 0, "maxSessions must be greater than 0");
        Assert.notNull(ttl, "ttl cannot be null");
        Assert.isTrue(!ttl.isNegative() && !ttl.isZero(), "ttl must be positive");
        Assert.notNull(clock, "clock cannot be null");
        this.delegate = delegate;
        this.maxSessions = maxSessions;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public List<String> findConversationIds() {
        evictExpired();
        return delegate.findConversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        evictExpired();
        if (isExpired(conversationId)) {
            deleteByConversationId(conversationId);
            return List.of();
        }
        List<Message> messages = delegate.findByConversationId(conversationId);
        if (!messages.isEmpty() || lastAccess.containsKey(conversationId)) {
            touch(conversationId);
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        evictExpired();
        delegate.saveAll(conversationId, messages);
        touch(conversationId);
        evictOverflow();
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        delegate.deleteByConversationId(conversationId);
        lastAccess.remove(conversationId);
    }

    public void evictExpired() {
        Instant cutoff = clock.instant().minus(ttl);
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : lastAccess.entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                expired.add(entry.getKey());
            }
        }
        for (String conversationId : expired) {
            deleteByConversationId(conversationId);
        }
        for (String conversationId : new ArrayList<>(delegate.findConversationIds())) {
            if (!lastAccess.containsKey(conversationId)) {
                deleteByConversationId(conversationId);
            }
        }
    }

    private void evictOverflow() {
        while (lastAccess.size() > maxSessions) {
            String oldest = lastAccess.entrySet().stream()
                    .min(Comparator.comparing(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                break;
            }
            deleteByConversationId(oldest);
        }
    }

    private boolean isExpired(String conversationId) {
        Instant accessed = lastAccess.get(conversationId);
        if (accessed == null) {
            return false;
        }
        return accessed.isBefore(clock.instant().minus(ttl));
    }

    private void touch(String conversationId) {
        lastAccess.put(conversationId, clock.instant());
    }
}

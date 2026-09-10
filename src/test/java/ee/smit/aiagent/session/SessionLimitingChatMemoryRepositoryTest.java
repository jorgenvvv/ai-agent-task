package ee.smit.aiagent.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLimitingChatMemoryRepositoryTest {

    private AtomicReference<Instant> now;
    private SessionLimitingChatMemoryRepository repository;

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        repository = new SessionLimitingChatMemoryRepository(
                new InMemoryChatMemoryRepository(),
                2,
                Duration.ofMinutes(30),
                clock);
    }

    @Test
    void maxSessionsEvictsLeastRecentlyUsed() {
        repository.saveAll("s1", List.of(new UserMessage("one")));
        now.set(now.get().plusSeconds(1));
        repository.saveAll("s2", List.of(new UserMessage("two")));
        now.set(now.get().plusSeconds(1));
        repository.saveAll("s3", List.of(new UserMessage("three")));

        List<String> ids = repository.findConversationIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("s2"));
        assertTrue(ids.contains("s3"));
        assertTrue(repository.findByConversationId("s1").isEmpty());
    }

    @Test
    void idleTtlDeletesConversation() {
        repository.saveAll("s1", List.of(new UserMessage("hello"), new AssistantMessage("hi")));
        assertEquals(2, repository.findByConversationId("s1").size());

        now.set(now.get().plus(Duration.ofMinutes(31)));

        List<Message> after = repository.findByConversationId("s1");
        assertTrue(after.isEmpty());
        assertTrue(repository.findConversationIds().isEmpty());
    }

    @Test
    void accessRefreshesTtl() {
        repository.saveAll("s1", List.of(new UserMessage("hello")));
        now.set(now.get().plus(Duration.ofMinutes(20)));
        repository.findByConversationId("s1");
        now.set(now.get().plus(Duration.ofMinutes(20)));

        assertEquals(1, repository.findByConversationId("s1").size());
    }
}

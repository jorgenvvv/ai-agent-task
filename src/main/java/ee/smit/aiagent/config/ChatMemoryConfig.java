package ee.smit.aiagent.config;

import ee.smit.aiagent.session.SessionLimitingChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ChatMemoryConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ChatMemoryRepository chatMemoryRepository(
            Clock clock,
            @Value("${app.agent.session.max-sessions:1000}") int maxSessions,
            @Value("${app.agent.session.ttl:45m}") Duration ttl) {
        return new SessionLimitingChatMemoryRepository(
                new InMemoryChatMemoryRepository(),
                maxSessions,
                ttl,
                clock);
    }

    @Bean
    ChatMemory chatMemory(
            ChatMemoryRepository chatMemoryRepository,
            @Value("${app.agent.session.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }
}

package ee.smit.aiagent.session;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageWindowChatMemoryTest {

    @Test
    void maxMessagesDropsOldestNonSystemMessages() {
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(4)
                .build();

        String id = "window-1";
        memory.add(id, List.of(new UserMessage("q1"), new AssistantMessage("a1")));
        memory.add(id, List.of(new UserMessage("q2"), new AssistantMessage("a2")));
        memory.add(id, List.of(new UserMessage("q3"), new AssistantMessage("a3")));

        List<Message> kept = memory.get(id);
        assertEquals(4, kept.size());
        String joined = kept.stream().map(Message::getText).reduce("", (a, b) -> a + "|" + b);
        assertFalse(joined.contains("q1"));
        assertFalse(joined.contains("a1"));
        assertTrue(joined.contains("q2") || joined.contains("q3"));
        assertTrue(joined.contains("a3"));
    }
}

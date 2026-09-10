package ee.smit.aiagent.agent;

import ee.smit.aiagent.knowledge.ToolSourcesBuffer;
import ee.smit.aiagent.model.AskRequest;
import ee.smit.aiagent.model.AskResponse;
import ee.smit.aiagent.model.SourceDto;
import ee.smit.aiagent.security.InputGuardService;
import ee.smit.aiagent.security.SensitiveDataRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServiceSessionTest {

    private ChatMemoryRepository repository;
    private ChatMemory chatMemory;
    private ToolSourcesBuffer sourcesBuffer;
    private AtomicInteger callCount;
    private AgentService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryChatMemoryRepository();
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
        sourcesBuffer = new ToolSourcesBuffer();
        callCount = new AtomicInteger();

        ChatModel chatModel = prompt -> {
            callCount.incrementAndGet();
            sourcesBuffer.add(new SourceDto(
                    "gitlab-access.md",
                    "GitLab",
                    "SLA 1-2 toopaev a"));
            String json = """
                    {"answer":"Vastus turn %d","refused":false,"refusalReason":null,"confidence":"high"}
                    """.formatted(callCount.get());
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(json))))
                    .build();
        };

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        service = new AgentService(
                chatClient,
                chatMemory,
                sourcesBuffer,
                new InputGuardService(),
                new SensitiveDataRedactor(),
                "test-key",
                true);
    }

    @Test
    void withoutSessionIdDoesNotWriteMemory() {
        AskResponse response = service.ask(new AskRequest("Kuidas GitLab?", null));
        assertFalse(response.refused());
        assertTrue(repository.findConversationIds().isEmpty());
        assertEquals(1, callCount.get());
    }

    @Test
    void blankSessionIdIsStateless() {
        service.ask(new AskRequest("Kysimus", "   "));
        assertTrue(repository.findConversationIds().isEmpty());
    }

    @Test
    void sameSessionIdKeepsHistoryAcrossTurns() {
        String sid = "demo-session-1";
        service.ask(new AskRequest("Kuidas taotleda ligipaasu GitLabile?", sid));
        List<Message> afterFirst = chatMemory.get(sid);
        assertFalse(afterFirst.isEmpty());
        assertTrue(afterFirst.stream().anyMatch(m -> m.getMessageType() == MessageType.USER));

        service.ask(new AskRequest("Kui kaua see votab aega?", sid));
        List<Message> afterSecond = chatMemory.get(sid);
        assertTrue(afterSecond.size() >= afterFirst.size());
        long userCount = afterSecond.stream().filter(m -> m.getMessageType() == MessageType.USER).count();
        assertEquals(2, userCount);
        assertTrue(afterSecond.stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .anyMatch(m -> m.getText().contains("Kui kaua")));
    }

    @Test
    void differentSessionIdsDoNotLeak() {
        service.ask(new AskRequest("GitLab kysimus", "session-a"));
        service.ask(new AskRequest("CI kysimus", "session-b"));

        List<Message> a = chatMemory.get("session-a");
        List<Message> b = chatMemory.get("session-b");
        assertTrue(a.stream().anyMatch(m -> m.getText().contains("GitLab")));
        assertTrue(b.stream().anyMatch(m -> m.getText().contains("CI")));
        assertTrue(a.stream().noneMatch(m -> m.getText().contains("CI kysimus")));
        assertTrue(b.stream().noneMatch(m -> m.getText().contains("GitLab kysimus")));
    }

    @Test
    void invalidCharsetReturns400() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.ask(new AskRequest("q", "bad session!")));
        assertEquals(400, ex.getStatusCode().value());
        assertTrue(repository.findConversationIds().isEmpty());
    }

    @Test
    void resolveSessionKey_disabledIgnoresId() {
        AgentService disabled = new AgentService(
                ChatClient.builder((Prompt prompt) -> ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(
                                "{\"answer\":\"x\",\"refused\":true,\"refusalReason\":\"r\",\"confidence\":\"low\"}"
                        ))))
                        .build()).build(),
                chatMemory,
                sourcesBuffer,
                new InputGuardService(),
                new SensitiveDataRedactor(),
                "test-key",
                false);
        assertNull(disabled.resolveSessionKey("valid-id"));
    }

    @Test
    void sourcesBufferClearedBetweenTurns() {
        String sid = "src-session";
        AskResponse first = service.ask(new AskRequest("q1", sid));
        assertEquals(1, first.sources().size());

        sourcesBuffer.add(new SourceDto("stale.md", "Stale", "should not appear"));
        AskResponse second = service.ask(new AskRequest("q2", sid));
        assertEquals(1, second.sources().size());
        assertEquals("gitlab-access.md", second.sources().getFirst().file());
        assertTrue(second.sources().stream().noneMatch(s -> "stale.md".equals(s.file())));
    }
}

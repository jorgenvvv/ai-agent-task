package ee.smit.aiagent.agent;

import ee.smit.aiagent.knowledge.SessionSourcesCache;
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
                    "Taotle ligipääsu teenuste portaalis. SLA 1-2 tööpäeva."));
            String json = """
                    {"answer":"Taotle ligipääsu teenuste portaalis. Turn %d.","refused":false,"refusalReason":null,"confidence":"high"}
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
                new SessionSourcesCache(),
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
                new SessionSourcesCache(),
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

    @Test
    void emptySourcesRefusesOnStatelessTurnWhenModelSkipsTools() {
        AtomicInteger localCalls = new AtomicInteger();
        ChatModel modelWithoutTools = prompt -> {
            localCalls.incrementAndGet();
            String json = """
                    {"answer":"Taotle ligipääsu teenuste portaalis.","refused":false,"refusalReason":null,"confidence":"high"}
                    """;
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(json))))
                    .build();
        };

        AgentService withoutToolSources = new AgentService(
                ChatClient.builder(modelWithoutTools).build(),
                chatMemory,
                new ToolSourcesBuffer(),
                new SessionSourcesCache(),
                new InputGuardService(),
                new SensitiveDataRedactor(),
                "test-key",
                true);

        AskResponse response = withoutToolSources.ask(new AskRequest("Kuidas saab gitlabi", null));
        assertTrue(response.refused(), response.toString());
        assertTrue(response.sources().isEmpty(), response.toString());
        assertEquals("low", response.confidence());
        assertEquals(1, localCalls.get());
    }

    @Test
    void emptySourcesAllowedOnSessionTurnWhenModelSkipsTools() {
        AtomicInteger localCalls = new AtomicInteger();
        ChatModel modelWithoutTools = prompt -> {
            localCalls.incrementAndGet();
            String json = """
                    {"answer":"Taotle ligipääsu teenuste portaalis.","refused":false,"refusalReason":null,"confidence":"high"}
                    """;
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(json))))
                    .build();
        };

        AgentService withoutToolSources = new AgentService(
                ChatClient.builder(modelWithoutTools).build(),
                chatMemory,
                new ToolSourcesBuffer(),
                new SessionSourcesCache(),
                new InputGuardService(),
                new SensitiveDataRedactor(),
                "test-key",
                true);

        AskResponse response = withoutToolSources.ask(new AskRequest("Kuidas saab gitlabi", "repeat-1"));
        assertFalse(response.refused(), response.toString());
        assertTrue(response.sources().isEmpty(), response.toString());
        assertEquals("low", response.confidence());
        assertTrue(response.answer().contains("Taotle ligipääsu"), response.answer());
        assertEquals(1, localCalls.get());
    }

    @Test
    void sessionRepeatReusesCachedSourcesWhenModelSkipsToolsButAnswerGrounded() {
        AtomicInteger localCalls = new AtomicInteger();
        ToolSourcesBuffer buffer = new ToolSourcesBuffer();
        SessionSourcesCache cache = new SessionSourcesCache();

        ChatModel model = prompt -> {
            int n = localCalls.incrementAndGet();
            if (n == 1) {
                buffer.add(new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis. Juhi kinnitus: tavaliselt 1–2 tööpäeva."));
            }
            String json = """
                    {"answer":"Taotle ligipääsu teenuste portaalis. Juhi kinnitus võtab tavaliselt 1–2 tööpäeva.","refused":false,"refusalReason":null,"confidence":"high"}
                    """;
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(json))))
                    .build();
        };

        AgentService svc = new AgentService(
                ChatClient.builder(model).build(),
                chatMemory,
                buffer,
                cache,
                new InputGuardService(),
                new SensitiveDataRedactor(),
                "test-key",
                true);

        String sid = "cache-reuse-1";
        AskResponse first = svc.ask(new AskRequest("gitlab ligipääs", sid));
        assertFalse(first.refused(), first.toString());
        assertEquals(1, first.sources().size());
        assertEquals("gitlab-access.md", first.sources().getFirst().file());
        assertEquals("high", first.confidence());

        AskResponse second = svc.ask(new AskRequest("gitlab ligipääs", sid));
        assertFalse(second.refused(), second.toString());
        assertEquals(1, second.sources().size(), second.toString());
        assertEquals("gitlab-access.md", second.sources().getFirst().file());
        assertTrue(second.answer().contains("[allikas: gitlab-access.md]"), second.answer());
        assertEquals("high", second.confidence());
        assertEquals(2, localCalls.get());
    }
}

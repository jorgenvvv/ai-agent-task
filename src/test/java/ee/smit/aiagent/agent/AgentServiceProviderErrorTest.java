package ee.smit.aiagent.agent;

import ee.smit.aiagent.knowledge.ToolSourcesBuffer;
import ee.smit.aiagent.model.AskRequest;
import ee.smit.aiagent.security.InputGuardService;
import ee.smit.aiagent.security.SensitiveDataRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServiceProviderErrorTest {

    @Test
    void providerExceptionIsSanitizedToGenericBadGateway() {
        ChatModel failingModel = (Prompt prompt) -> {
            throw new RuntimeException("400: AUDIT_PROVIDER_INTERNAL_DETAIL_782");
        };
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        AgentService service = new AgentService(
                ChatClient.builder(failingModel).build(),
                chatMemory,
                new ToolSourcesBuffer(),
                new InputGuardService(),
                new SensitiveDataRedactor(),
                "test-key",
                true);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.ask(new AskRequest("Kuidas taotleda GitLabi ligipääsu?", null)));

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertEquals(AgentService.PROVIDER_FAILURE_MESSAGE, ex.getReason());
        assertFalse(ex.getReason().contains("AUDIT_PROVIDER_INTERNAL_DETAIL_782"));
        assertTrue(ex.getCause().getMessage().contains("AUDIT_PROVIDER_INTERNAL_DETAIL_782"));
    }
}

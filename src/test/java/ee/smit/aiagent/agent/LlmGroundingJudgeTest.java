package ee.smit.aiagent.agent;

import ee.smit.aiagent.model.SourceDto;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmGroundingJudgeTest {

    @Test
    void returnsTrueWhenModelSaysGrounded() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            String json = "{\"grounded\":true,\"reason\":\"ok\"}";
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(json))))
                    .build();
        };
        LlmGroundingJudge judge = new LlmGroundingJudge(ChatClient.builder(model).build(), 6000, 4000);
        boolean ok = judge.isGrounded(
                "Taotle ligipääsu teenuste portaalis.",
                List.of(new SourceDto("vpn.md", "VPN", "Taotle ligipääsu teenuste portaalis.")));
        assertTrue(ok);
        assertEquals(1, calls.get());
    }

    @Test
    void failClosedOnProviderError() {
        ChatModel model = prompt -> {
            throw new RuntimeException("boom");
        };
        LlmGroundingJudge judge = new LlmGroundingJudge(ChatClient.builder(model).build(), 6000, 4000);
        assertFalse(judge.isGrounded(
                "Taotle ligipääsu.",
                List.of(new SourceDto("vpn.md", "VPN", "Taotle ligipääsu."))));
    }

    @Test
    void emptySourcesRejectedWithoutCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = prompt -> {
            calls.incrementAndGet();
            throw new IllegalStateException("should not call");
        };
        LlmGroundingJudge judge = new LlmGroundingJudge(ChatClient.builder(model).build(), 6000, 4000);
        assertFalse(judge.isGrounded("vastus", List.of()));
        assertEquals(0, calls.get());
    }

    @Test
    void returnsFalseWhenModelSaysNotGrounded() {
        ChatModel model = prompt -> {
            String json = "{\"grounded\":false,\"reason\":\"extra fact\"}";
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(json))))
                    .build();
        };
        LlmGroundingJudge judge = new LlmGroundingJudge(ChatClient.builder(model).build(), 6000, 4000);
        assertFalse(judge.isGrounded(
                "Taotle ligipääsu teenuste portaalis.",
                List.of(new SourceDto("vpn.md", "VPN", "Taotle ligipääsu teenuste portaalis."))));
    }
}

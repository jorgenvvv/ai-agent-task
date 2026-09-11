package ee.smit.aiagent.controller;

import ee.smit.aiagent.agent.AgentService;
import ee.smit.aiagent.model.AskResponse;
import ee.smit.aiagent.model.SourceDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AgentController.class)
@Import(GlobalExceptionHandler.class)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentService agentService;

    @Test
    void emptyQuestionReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(agentService, never()).ask(any());
    }

    @Test
    void blankQuestionReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(agentService, never()).ask(any());
    }

    @Test
    void missingQuestionReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(agentService, never()).ask(any());
    }

    @Test
    void invalidJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":"))
                .andExpect(status().isBadRequest());

        verify(agentService, never()).ask(any());
    }

    @Test
    void questionOverMaxLengthReturns400BeforeService() throws Exception {
        String longQuestion = "a".repeat(3001);
        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + longQuestion + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(agentService, never()).ask(any());
    }

    @Test
    void sessionIdOverMaxLengthReturns400BeforeService() throws Exception {
        String longSessionId = "s".repeat(101);
        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"ok\",\"sessionId\":\"" + longSessionId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(agentService, never()).ask(any());
    }

    @Test
    void happyPathReturnsSpecJsonShape() throws Exception {
        AskResponse body = new AskResponse(
                "Taotle ligipääsu teenuste portaalis. [allikas: gitlab-access.md]",
                List.of(new SourceDto(
                        "gitlab-access.md",
                        "GitLab ligipääs",
                        "Taotle ligipääsu teenuste portaalis.")),
                "high",
                false,
                null);
        when(agentService.ask(any())).thenReturn(body);

        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Kuidas taotleda ligipääsu GitLabile?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(body.answer()))
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.sources[0].file").value("gitlab-access.md"))
                .andExpect(jsonPath("$.sources[0].excerpt").value("Taotle ligipääsu teenuste portaalis."))
                .andExpect(jsonPath("$.confidence").value("high"))
                .andExpect(jsonPath("$.refused").value(false))
                .andExpect(jsonPath("$.refusalReason").doesNotExist());
    }

    @Test
    void providerErrorDoesNotLeakInternalDetail() throws Exception {
        when(agentService.ask(any())).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "AI provider request failed: AUDIT_PROVIDER_INTERNAL_DETAIL_782"));

        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Milline on GitLabi ligipääs?\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.message").value("AI provider request failed"))
                .andExpect(jsonPath("$.correlationId").isString())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.junit.jupiter.api.Assertions.assertFalse(
                            body.contains("AUDIT_PROVIDER_INTERNAL_DETAIL_782"),
                            "response must not leak provider detail: " + body);
                });
    }

    @Test
    void providerErrorMessageIsStableGeneric() throws Exception {
        when(agentService.ask(any())).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "AI provider request failed"));

        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Milline on GitLabi ligipääs?\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("AI provider request failed"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void emptyQuestionKeepsSpecificValidationMessage() throws Exception {
        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("question must not be blank"))
                .andExpect(jsonPath("$.correlationId").doesNotExist());

        verify(agentService, never()).ask(any());
    }

}

package ee.smit.aiagent.controller;

import ee.smit.aiagent.agent.AgentService;
import ee.smit.aiagent.model.AskResponse;
import ee.smit.aiagent.model.SourceDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
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
}

package ee.smit.aiagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ee.smit.aiagent.agent.AgentService;
import ee.smit.aiagent.model.AskResponse;
import ee.smit.aiagent.security.ClientIpResolver;
import ee.smit.aiagent.security.RateLimitFilter;
import ee.smit.aiagent.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AgentService agentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RateLimitService rateLimitService = new RateLimitService(true, 10, CLOCK);
        ClientIpResolver ipResolver = new ClientIpResolver(false);
        RateLimitFilter filter = new RateLimitFilter(rateLimitService, ipResolver, new ObjectMapper());

        mockMvc = MockMvcBuilders.standaloneSetup(new AgentController(agentService))
                .addFilters(filter)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void eleventhAskReturns429() throws Exception {
        when(agentService.ask(any())).thenReturn(
                new AskResponse("ok", List.of(), "high", false, null));

        String body = "{\"question\":\"Kuidas GitLab?\",\"sessionId\":\"rl-test-unique-1\"}";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(req -> {
                                req.setRemoteAddr("203.0.113.50");
                                return req;
                            }))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(req -> {
                            req.setRemoteAddr("203.0.113.50");
                            return req;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"));
    }

    @Test
    void ipLimitAcrossSessions() throws Exception {
        when(agentService.ask(any())).thenReturn(
                new AskResponse("ok", List.of(), "high", false, null));

        String ip = "203.0.113.77";
        for (int i = 0; i < 10; i++) {
            String body = "{\"question\":\"Kuidas GitLab?\",\"sessionId\":\"sess-" + i + "\"}";
            mockMvc.perform(post("/api/v1/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(req -> {
                                req.setRemoteAddr(ip);
                                return req;
                            }))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Kuidas GitLab?\",\"sessionId\":\"sess-overflow\"}")
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        }))
                .andExpect(status().isTooManyRequests());

        verify(agentService, times(10)).ask(any());
    }

    @Test
    void semicolonPathIsRateLimited() throws Exception {
        when(agentService.ask(any())).thenReturn(
                new AskResponse("ok", List.of(), "high", false, null));

        String body = "{\"question\":\"Kuidas GitLab?\"}";
        String ip = "203.0.113.88";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(req -> {
                                req.setRemoteAddr(ip);
                                return req;
                            }))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/agent/ask;audit=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            req.setRequestURI("/api/v1/agent/ask;audit=1");
                            return req;
                        }))
                .andExpect(status().isTooManyRequests());

        verify(agentService, atMost(10)).ask(any());
    }

    @Test
    void encodedPathIsRateLimited() throws Exception {
        when(agentService.ask(any())).thenReturn(
                new AskResponse("ok", List.of(), "high", false, null));

        String body = "{\"question\":\"Kuidas GitLab?\"}";
        String ip = "203.0.113.89";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/agent/ask")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(req -> {
                                req.setRemoteAddr(ip);
                                return req;
                            }))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/v1/agent/%61sk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            req.setRequestURI("/api/v1/agent/%61sk");
                            return req;
                        }))
                .andExpect(status().isTooManyRequests());
    }
}

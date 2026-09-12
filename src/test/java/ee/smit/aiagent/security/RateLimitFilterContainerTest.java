package ee.smit.aiagent.security;

import ee.smit.aiagent.agent.AgentService;
import ee.smit.aiagent.model.AskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitFilterContainerTest {

    private static final int RPM = 3;

    @LocalServerPort
    private int port;

    @Autowired
    private RateLimitService rateLimitService;

    @MockitoBean
    private AgentService agentService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        Path knowledge = Path.of("knowledge").toAbsolutePath().normalize();
        registry.add("app.knowledge.path", knowledge::toString);
        registry.add("app.agent.rate-limit.enabled", () -> "true");
        registry.add("app.agent.rate-limit.requests-per-minute", () -> String.valueOf(RPM));
        registry.add("spring.ai.openai.api-key", () -> "test-key-not-used");
    }

    @BeforeEach
    void setUp() {
        rateLimitService.reset();
        when(agentService.ask(any())).thenReturn(
                new AskResponse("ok", List.of(), "high", false, null));
    }

    @Test
    void matrixParamPathIsRateLimited_afterCanonicalExhaustion() throws Exception {
        String body = jsonBody("container-semi");
        for (int i = 0; i < RPM; i++) {
            assertEquals(200, post("/api/v1/agent/ask", body).statusCode(), "fill " + i);
        }
        HttpResponse<String> bypass = post("/api/v1/agent/ask;audit=1", body);
        assertEquals(429, bypass.statusCode(), "matrix param must not bypass rate limit: " + bypass.body());
        assertTrue(bypass.body().contains("Too Many Requests") || bypass.body().contains("429"));
    }

    @Test
    void encodedPathIsRateLimited_afterCanonicalExhaustion() throws Exception {
        String body = jsonBody("container-enc");
        for (int i = 0; i < RPM; i++) {
            assertEquals(200, post("/api/v1/agent/ask", body).statusCode(), "fill " + i);
        }
        HttpResponse<String> bypass = post("/api/v1/agent/%61sk", body);
        assertEquals(429, bypass.statusCode(), "percent-encoded path must not bypass: " + bypass.body());
    }

    @Test
    void ipLimitAcrossSessions_onRealServer() throws Exception {
        for (int i = 0; i < RPM; i++) {
            assertEquals(200, post("/api/v1/agent/ask", jsonBody("c-sess-" + i)).statusCode());
        }
        HttpResponse<String> overflow = post("/api/v1/agent/ask", jsonBody("c-sess-overflow"));
        assertEquals(429, overflow.statusCode(), overflow.body());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        URI uri;
        if (path.indexOf(';') >= 0) {
            uri = new URI("http", null, "127.0.0.1", port, path, null, null);
        } else {
            uri = URI.create("http://127.0.0.1:" + port + path);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonBody(String sessionId) {
        return "{\"question\":\"Kuidas GitLab?\",\"sessionId\":\"" + sessionId + "\"}";
    }
}

package ee.smit.aiagent.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ee.smit.aiagent.model.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class RateLimitFilter extends OncePerRequestFilter {

    private static final String ASK_PATH = "/api/v1/agent/ask";

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver,
            ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isAskPost(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request);
        String ip = clientIpResolver.resolve(wrapped);
        String sessionId = extractSessionId(wrapped.getCachedBody());
        String key = buildKey(ip, sessionId);

        if (!rateLimitService.tryAcquire(key)) {
            writeTooManyRequests(response);
            return;
        }

        filterChain.doFilter(wrapped, response);
    }

    private static boolean isAskPost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        String context = request.getContextPath();
        if (StringUtils.hasText(context) && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return ASK_PATH.equals(path);
    }

    private String extractSessionId(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode sid = root.get("sessionId");
            if (sid != null && sid.isTextual() && StringUtils.hasText(sid.asText())) {
                return sid.asText().trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String buildKey(String ip, String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            return ip + "|s:" + sessionId;
        }
        return ip;
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ErrorResponse body = new ErrorResponse(
                429,
                "Too Many Requests",
                "Rate limit exceeded. Try again later.");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
        }

        byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return inputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}

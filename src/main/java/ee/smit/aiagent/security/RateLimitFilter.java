package ee.smit.aiagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import ee.smit.aiagent.model.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class RateLimitFilter extends OncePerRequestFilter {

    static final String ASK_PATH = "/api/v1/agent/ask";

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

        String ip = clientIpResolver.resolve(request);
        if (!rateLimitService.tryAcquire(ip)) {
            writeTooManyRequests(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    static boolean isAskPost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = requestPath(request);
        if (path == null) {
            return false;
        }
        return ASK_PATH.equals(normalizePath(path));
    }

    private static String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            String servletPath = request.getServletPath();
            String pathInfo = request.getPathInfo();
            if (servletPath == null && pathInfo == null) {
                return null;
            }
            path = (servletPath != null ? servletPath : "") + (pathInfo != null ? pathInfo : "");
        }
        String context = request.getContextPath();
        if (StringUtils.hasText(context) && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (path.isEmpty()) {
            path = "/";
        }
        return path;
    }

    static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return rawPath;
        }

        String path = stripMatrixParams(rawPath);
        path = decodePath(path);
        path = collapseDuplicateSlashes(path);

        if (path.isEmpty()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    static String stripMatrixParams(String path) {
        StringBuilder out = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == ';') {
                while (i < path.length() && path.charAt(i) != '/') {
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    static String decodePath(String path) {
        String current = path;
        for (int round = 0; round < 3; round++) {
            if (!current.contains("%")) {
                break;
            }
            try {
                String decoded = URLDecoder.decode(current, StandardCharsets.UTF_8);
                if (decoded.equals(current)) {
                    break;
                }
                current = decoded;
            } catch (IllegalArgumentException ex) {
                break;
            }
        }
        return current;
    }

    static String collapseDuplicateSlashes(String path) {
        if (path.indexOf("//") < 0) {
            return path;
        }
        StringBuilder out = new StringBuilder(path.length());
        boolean prevSlash = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if (prevSlash) {
                    continue;
                }
                prevSlash = true;
            } else {
                prevSlash = false;
            }
            out.append(c);
        }
        return out.toString();
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
}

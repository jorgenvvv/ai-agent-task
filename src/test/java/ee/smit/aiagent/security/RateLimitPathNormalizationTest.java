package ee.smit.aiagent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitPathNormalizationTest {

    @Test
    void normalizeSemicolon_f04() {
        assertEquals(
                RateLimitFilter.ASK_PATH,
                RateLimitFilter.normalizePath("/api/v1/agent/ask;audit=1"));
        assertEquals(
                RateLimitFilter.ASK_PATH,
                RateLimitFilter.normalizePath("/api/v1/agent/ask;jsessionid=ABC"));
    }

    @Test
    void normalizeEncoded_f04() {
        assertEquals(
                RateLimitFilter.ASK_PATH,
                RateLimitFilter.normalizePath("/api/v1/agent/%61sk"));
        assertEquals(
                RateLimitFilter.ASK_PATH,
                RateLimitFilter.normalizePath("/api/v1/agent/%2561sk"));
    }

    @Test
    void normalizeDoubleSlash_f04() {
        assertEquals(
                RateLimitFilter.ASK_PATH,
                RateLimitFilter.normalizePath("/api/v1/agent//ask"));
        assertEquals(
                RateLimitFilter.ASK_PATH,
                RateLimitFilter.normalizePath("/api//v1//agent///ask"));
    }

    @Test
    void normalizeCombinedBypassAttempts() {
        assertEquals(
                RateLimitFilter.ASK_PATH,
                RateLimitFilter.normalizePath("/api/v1/agent/%61sk;audit=1"));
        assertEquals(
                RateLimitFilter.ASK_PATH,
                RateLimitFilter.normalizePath("/api/v1/agent//%61sk;x=1"));
    }

    @Test
    void nonAskPathsStayDifferent() {
        assertFalse(RateLimitFilter.ASK_PATH.equals(
                RateLimitFilter.normalizePath("/api/v1/agent/other")));
        assertFalse(RateLimitFilter.ASK_PATH.equals(
                RateLimitFilter.normalizePath("/api/v1/agent/ask/extra")));
    }

    @Test
    void stripMatrixParamsOnly() {
        assertEquals("/a/b", RateLimitFilter.stripMatrixParams("/a;x=1/b;y=2"));
    }
}

package ee.smit.aiagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    @Test
    void usesRemoteAddrWhenForwardedNotTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        assertEquals("10.0.0.5", resolver.resolve(request));
    }

    @Test
    void usesFirstXffWhenTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        assertEquals("1.2.3.4", resolver.resolve(request));
    }
}

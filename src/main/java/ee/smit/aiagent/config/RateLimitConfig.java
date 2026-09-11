package ee.smit.aiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import ee.smit.aiagent.security.ClientIpResolver;
import ee.smit.aiagent.security.RateLimitFilter;
import ee.smit.aiagent.security.RateLimitService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RateLimitConfig {

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitService rateLimitService,
            ClientIpResolver clientIpResolver) {
        ObjectMapper objectMapper = new ObjectMapper();
        RateLimitFilter filter = new RateLimitFilter(rateLimitService, clientIpResolver, objectMapper);
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/api/v1/agent/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("rateLimitFilter");
        return registration;
    }
}

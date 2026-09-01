package com.antflow.auth;

import com.antflow.audit.AuditService;
import com.antflow.audit.TrustedProxyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class LoginRateLimitFilterTest {
    @Test
    void trustedProxyClientsHaveSeparateRateLimitBuckets() throws Exception {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setTrustedProxies(List.of("172.16.0.0/12"));
        AuditService auditService = new AuditService(Mockito.mock(JdbcTemplate.class),
            new ObjectMapper(), properties);
        LoginRateLimitFilter filter = new LoginRateLimitFilter(auditService, 1, 30);
        FilterChain chain = Mockito.mock(FilterChain.class);

        MockHttpServletResponse first = filter(filter, chain, "192.168.9.20");
        MockHttpServletResponse second = filter(filter, chain, "192.168.9.21");
        MockHttpServletResponse repeated = filter(filter, chain, "192.168.9.20");

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(repeated.getStatus()).isEqualTo(429);
        verify(chain, times(2)).doFilter(Mockito.any(), Mockito.any());
    }

    private MockHttpServletResponse filter(LoginRateLimitFilter filter, FilterChain chain,
                                           String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("172.20.0.3");
        request.addHeader("X-Forwarded-For", forwardedFor);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}

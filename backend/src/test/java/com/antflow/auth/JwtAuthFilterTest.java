package com.antflow.auth;

import com.antflow.authz.AuthorizationService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {
    @AfterEach
    void clearContext() {
        PrincipalHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeSessionAuthenticatesRequestAndCleansThreadContext() throws Exception {
        JwtService jwtService = Mockito.mock(JwtService.class);
        AuthSessionService sessionService = Mockito.mock(AuthSessionService.class);
        AuthorizationService authorizationService = Mockito.mock(AuthorizationService.class);
        Claims claims = Mockito.mock(Claims.class);
        UUID sessionId = UUID.randomUUID();
        when(jwtService.parse("token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("7");
        when(claims.get("sid", String.class)).thenReturn(sessionId.toString());
        when(claims.get("username", String.class)).thenReturn("admin");
        when(claims.get("roles", List.class)).thenReturn(List.of("admin"));
        when(sessionService.isActive(7L, sessionId)).thenReturn(true);
        when(authorizationService.principalForRequest(7L, sessionId)).thenReturn(Optional.of(
            new PrincipalHolder.Principal(7L, "database-user", "Database User",
                Set.of("user"), Set.of("workflow.instance.read"), 9L, 3L, sessionId)));
        MockHttpServletRequest request = bearerRequest();
        boolean[] authenticatedInChain = { false };
        FilterChain chain = (req, res) -> {
            var principal = PrincipalHolder.current().orElseThrow();
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            authenticatedInChain[0] = authentication != null
                && principal.username().equals("database-user")
                && authentication.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("workflow.instance.read"))
                && authentication.getAuthorities().stream()
                    .noneMatch(authority -> authority.getAuthority().equals("ROLE_admin"));
        };

        new JwtAuthFilter(jwtService, sessionService, authorizationService).doFilter(
            request, new MockHttpServletResponse(), chain);

        assertThat(authenticatedInChain[0]).isTrue();
        assertThat(PrincipalHolder.current()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void revokedSessionDoesNotAuthenticateRequest() throws Exception {
        JwtService jwtService = Mockito.mock(JwtService.class);
        AuthSessionService sessionService = Mockito.mock(AuthSessionService.class);
        AuthorizationService authorizationService = Mockito.mock(AuthorizationService.class);
        Claims claims = Mockito.mock(Claims.class);
        UUID sessionId = UUID.randomUUID();
        when(jwtService.parse("token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("7");
        when(claims.get("sid", String.class)).thenReturn(sessionId.toString());
        when(sessionService.isActive(7L, sessionId)).thenReturn(false);

        boolean[] authenticatedInChain = { true };
        FilterChain chain = (req, res) -> authenticatedInChain[0] =
            PrincipalHolder.current().isPresent()
                || SecurityContextHolder.getContext().getAuthentication() != null;

        new JwtAuthFilter(jwtService, sessionService, authorizationService).doFilter(
            bearerRequest(), new MockHttpServletResponse(), chain);

        assertThat(authenticatedInChain[0]).isFalse();
        assertThat(PrincipalHolder.current()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static MockHttpServletRequest bearerRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        return request;
    }
}

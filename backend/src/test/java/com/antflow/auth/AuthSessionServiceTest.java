package com.antflow.auth;

import com.antflow.org.User;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class AuthSessionServiceTest {
    private AuthSessionMapper sessionMapper;
    private AuthService authService;
    private JwtService jwtService;
    private AuthSessionService service;

    @BeforeEach
    void setUp() {
        sessionMapper = Mockito.mock(AuthSessionMapper.class);
        authService = Mockito.mock(AuthService.class);
        jwtService = Mockito.mock(JwtService.class);
        service = new AuthSessionService(sessionMapper, authService, jwtService, 3600);
    }

    @Test
    void createStoresOnlyHashesAndSetsProtectedCookies() {
        MockHttpServletRequest request = request("Mozilla/5.0 (Windows NT 10.0) Chrome/126");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtService.issue(eq(7L), eq("admin"), eq(List.of("admin")), any(UUID.class)))
            .thenReturn("session-access-token");

        AuthService.Authenticated result = service.create(authenticated(), request, response);

        ArgumentCaptor<AuthSession> captor = ArgumentCaptor.forClass(AuthSession.class);
        verify(sessionMapper).insert(captor.capture());
        AuthSession stored = captor.getValue();
        assertThat(stored.getRefreshTokenHash()).hasSize(64);
        assertThat(stored.getCsrfTokenHash()).hasSize(64);
        assertThat(stored.getDeviceName()).isEqualTo("Chrome Windows");
        assertThat(result.accessToken()).isEqualTo("session-access-token");

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).anySatisfy(cookie -> {
            assertThat(cookie).contains("antflow-refresh=");
            assertThat(cookie).contains("HttpOnly");
            assertThat(cookie).contains("SameSite=Lax");
        });
        assertThat(cookies).anySatisfy(cookie -> {
            assertThat(cookie).contains("antflow-csrf=");
            assertThat(cookie).doesNotContain("HttpOnly");
        });
    }

    @Test
    void refreshRotatesBothTokensAndMarksCurrentSessionInList() {
        String refreshToken = "refresh-token";
        String csrfToken = "csrf-token";
        AuthSession session = session(refreshToken, csrfToken, 7L);
        when(sessionMapper.selectOne(any(QueryWrapper.class))).thenReturn(session);
        when(authService.resume(7L)).thenReturn(Optional.of(authenticated()));
        when(jwtService.issue(eq(7L), eq("admin"), eq(List.of("admin")), eq(session.getId())))
            .thenReturn("rotated-access-token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthService.Authenticated result = service.refresh(
            refreshToken, csrfToken, csrfToken, request("Mozilla/5.0 iPhone Safari/17"), response);

        assertThat(result.accessToken()).isEqualTo("rotated-access-token");
        assertThat(session.getRefreshTokenHash()).isNotEqualTo(AuthSessionService.hash(refreshToken));
        assertThat(session.getCsrfTokenHash()).isNotEqualTo(AuthSessionService.hash(csrfToken));
        verify(sessionMapper).updateById(session);

        when(sessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(session));
        String rotatedRawToken = cookieValue(response, AuthSessionService.REFRESH_COOKIE);
        assertThat(service.list(7L, rotatedRawToken)).singleElement().satisfies(device -> {
            assertThat(device.id()).isEqualTo(session.getId().toString());
            assertThat(device.isCurrent()).isTrue();
        });
    }

    @Test
    void revokeRejectsSessionOwnedByAnotherUser() {
        AuthSession session = session("refresh", "csrf", 8L);
        when(sessionMapper.selectById(session.getId())).thenReturn(session);

        assertThatThrownBy(() -> service.revoke(7L, session.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(session.getRevokedAt()).isNull();
    }

    @Test
    void revokedSessionImmediatelyBecomesInactive() {
        AuthSession session = session("refresh", "csrf", 7L);
        when(sessionMapper.selectById(session.getId())).thenReturn(session);

        service.revoke(7L, session.getId());

        assertThat(service.isActive(7L, session.getId())).isFalse();
        verify(sessionMapper).updateById(session);
    }

    private String cookieValue(MockHttpServletResponse response, String name) {
        return response.getHeaders("Set-Cookie").stream()
            .filter(cookie -> cookie.startsWith(name + "="))
            .map(cookie -> cookie.substring(name.length() + 1, cookie.indexOf(';')))
            .findFirst()
            .orElseThrow();
    }

    private static AuthService.Authenticated authenticated() {
        User user = new User();
        user.setId(7L);
        user.setUsername("admin");
        user.setDisplayName("管理员");
        user.setStatus("ACTIVE");
        return new AuthService.Authenticated("unbound-token", user, List.of("admin"));
    }

    private static AuthSession session(String refreshToken, String csrfToken, long userId) {
        AuthSession session = new AuthSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setRefreshTokenHash(AuthSessionService.hash(refreshToken));
        session.setCsrfTokenHash(AuthSessionService.hash(csrfToken));
        session.setDeviceName("Chrome Windows");
        session.setPlatform("browser");
        session.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        session.setLastActiveAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        session.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        return session;
    }

    private static MockHttpServletRequest request(String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", userAgent);
        return request;
    }
}

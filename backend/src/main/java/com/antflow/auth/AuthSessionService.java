package com.antflow.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthSessionService {
    static final String REFRESH_COOKIE = "antflow-refresh";
    static final String CSRF_COOKIE = "antflow-csrf";

    private final AuthSessionMapper sessionMapper;
    private final AuthService authService;
    private final JwtService jwtService;
    private final long sessionTtlSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthSessionService(
            AuthSessionMapper sessionMapper,
            AuthService authService,
            JwtService jwtService,
            @Value("${antflow.auth.session-ttl-seconds:2592000}") long sessionTtlSeconds) {
        this.sessionMapper = sessionMapper;
        this.authService = authService;
        this.jwtService = jwtService;
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    @Transactional
    public AuthService.Authenticated create(AuthService.Authenticated authenticated, HttpServletRequest request,
                                            HttpServletResponse response) {
        OffsetDateTime now = now();
        String refreshToken = randomToken();
        String csrfToken = randomToken();
        AuthSession session = new AuthSession();
        session.setId(UUID.randomUUID());
        session.setUserId(authenticated.user().getId());
        session.setRefreshTokenHash(hash(refreshToken));
        session.setCsrfTokenHash(hash(csrfToken));
        session.setDeviceName(deviceName(request.getHeader(HttpHeaders.USER_AGENT)));
        session.setPlatform(platform(request.getHeader(HttpHeaders.USER_AGENT)));
        session.setCreatedAt(now);
        session.setLastActiveAt(now);
        session.setExpiresAt(now.plusSeconds(sessionTtlSeconds));
        sessionMapper.insert(session);
        writeCookies(response, request.isSecure(), refreshToken, csrfToken, sessionTtlSeconds);
        return bindAccessToken(authenticated, session.getId());
    }

    @Transactional
    public AuthService.Authenticated refresh(String refreshToken, String csrfCookie, String csrfHeader,
                                             HttpServletRequest request, HttpServletResponse response) {
        AuthSession session = requireActive(refreshToken);
        requireCsrf(session, csrfCookie, csrfHeader);
        AuthService.Authenticated authenticated = authService.resume(session.getUserId())
            .orElseThrow(() -> new BadCredentialsException("invalid session"));

        String nextRefreshToken = randomToken();
        String nextCsrfToken = randomToken();
        OffsetDateTime now = now();
        session.setRefreshTokenHash(hash(nextRefreshToken));
        session.setCsrfTokenHash(hash(nextCsrfToken));
        session.setLastActiveAt(now);
        session.setDeviceName(deviceName(request.getHeader(HttpHeaders.USER_AGENT)));
        session.setPlatform(platform(request.getHeader(HttpHeaders.USER_AGENT)));
        sessionMapper.updateById(session);

        long remainingSeconds = Math.max(1, Duration.between(now, session.getExpiresAt()).getSeconds());
        writeCookies(response, request.isSecure(), nextRefreshToken, nextCsrfToken, remainingSeconds);
        return bindAccessToken(authenticated, session.getId());
    }

    @Transactional
    public void logout(String refreshToken, String csrfCookie, String csrfHeader,
                       HttpServletRequest request, HttpServletResponse response) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            AuthSession session = findByRefreshToken(refreshToken);
            if (session != null && session.getRevokedAt() == null) {
                requireCsrf(session, csrfCookie, csrfHeader);
                session.setRevokedAt(now());
                sessionMapper.updateById(session);
            }
        }
        clearCookies(response, request.isSecure());
    }

    public List<DeviceSessionDto> list(long userId, String currentRefreshToken) {
        OffsetDateTime now = now();
        String currentHash = currentRefreshToken == null || currentRefreshToken.isBlank()
            ? null : hash(currentRefreshToken);
        return sessionMapper.selectList(new QueryWrapper<AuthSession>()
                .eq("user_id", userId)
                .isNull("revoked_at")
                .gt("expires_at", now)
                .orderByDesc("last_active_at"))
            .stream()
            .map(session -> new DeviceSessionDto(
                session.getId().toString(),
                session.getDeviceName(),
                session.getPlatform(),
                session.getLastActiveAt(),
                currentHash != null && MessageDigest.isEqual(
                    currentHash.getBytes(StandardCharsets.US_ASCII),
                    session.getRefreshTokenHash().getBytes(StandardCharsets.US_ASCII))))
            .toList();
    }

    @Transactional
    public void revoke(long userId, UUID sessionId) {
        AuthSession session = sessionMapper.selectById(sessionId);
        if (session == null || !Long.valueOf(userId).equals(session.getUserId())) {
            throw new AccessDeniedException("session belongs to another user");
        }
        if (session.getRevokedAt() == null) {
            session.setRevokedAt(now());
            sessionMapper.updateById(session);
        }
    }

    public boolean isActive(long userId, UUID sessionId) {
        AuthSession session = sessionMapper.selectById(sessionId);
        return session != null
            && Long.valueOf(userId).equals(session.getUserId())
            && session.getRevokedAt() == null
            && session.getExpiresAt().isAfter(now());
    }

    @Transactional
    public void revokeAll(long userId) {
        OffsetDateTime revokedAt = now();
        sessionMapper.selectList(new QueryWrapper<AuthSession>()
                .eq("user_id", userId)
                .isNull("revoked_at"))
            .forEach(session -> {
                session.setRevokedAt(revokedAt);
                sessionMapper.updateById(session);
            });
    }

    private AuthService.Authenticated bindAccessToken(AuthService.Authenticated authenticated, UUID sessionId) {
        return new AuthService.Authenticated(
            jwtService.issue(authenticated.user().getId(), authenticated.user().getUsername(),
                authenticated.roles(), sessionId),
            authenticated.user(),
            authenticated.roles(),
            sessionId);
    }

    private AuthSession requireActive(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadCredentialsException("missing session");
        }
        AuthSession session = findByRefreshToken(refreshToken);
        if (session == null || session.getRevokedAt() != null || !session.getExpiresAt().isAfter(now())) {
            throw new BadCredentialsException("invalid session");
        }
        return session;
    }

    private AuthSession findByRefreshToken(String refreshToken) {
        return sessionMapper.selectOne(new QueryWrapper<AuthSession>()
            .eq("refresh_token_hash", hash(refreshToken)));
    }

    private void requireCsrf(AuthSession session, String csrfCookie, String csrfHeader) {
        if (csrfCookie == null || csrfHeader == null
                || !MessageDigest.isEqual(csrfCookie.getBytes(StandardCharsets.UTF_8),
                    csrfHeader.getBytes(StandardCharsets.UTF_8))
                || !MessageDigest.isEqual(hash(csrfCookie).getBytes(StandardCharsets.US_ASCII),
                    session.getCsrfTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new AccessDeniedException("invalid CSRF token");
        }
    }

    private void writeCookies(HttpServletResponse response, boolean secure, String refreshToken,
                              String csrfToken, long maxAgeSeconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, refreshToken, true, secure, maxAgeSeconds));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(CSRF_COOKIE, csrfToken, false, secure, maxAgeSeconds));
    }

    private void clearCookies(HttpServletResponse response, boolean secure) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", true, secure, 0));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(CSRF_COOKIE, "", false, secure, 0));
    }

    private String cookie(String name, String value, boolean httpOnly, boolean secure, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
            .httpOnly(httpOnly)
            .secure(secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(maxAgeSeconds)
            .build()
            .toString();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String platform(String userAgent) {
        return userAgent != null && userAgent.toLowerCase(Locale.ROOT).contains("micromessenger")
            ? "wecom" : "browser";
    }

    private String deviceName(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "未知浏览器";
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String browser = ua.contains("edg/") ? "Edge" : ua.contains("chrome/") ? "Chrome"
            : ua.contains("safari/") ? "Safari" : ua.contains("firefox/") ? "Firefox" : "浏览器";
        String system = ua.contains("iphone") ? "iPhone" : ua.contains("android") ? "Android"
            : ua.contains("windows") ? "Windows" : ua.contains("macintosh") ? "macOS" : "设备";
        return browser + " " + system;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public record DeviceSessionDto(String id, String deviceName, String platform,
                                   OffsetDateTime lastActiveAt, boolean isCurrent) {
    }
}

package com.antflow.common;

import com.antflow.auth.PrincipalHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
@Order(50)
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> HEADER_NAMES = Set.of("Idempotency-Key", "X-Idempotency-Key");
    private final IdempotencyService service;

    public IdempotencyFilter(IdempotencyService service) { this.service = service; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return !METHODS.contains(req.getMethod().toUpperCase())
            || !(path.startsWith("/api/mobile/") || path.startsWith("/api/auth/login")
                || path.startsWith("/api/forms/") || path.startsWith("/api/processes/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = readKey(req);
        if (key == null) { chain.doFilter(req, res); return; }

        byte[] body = req.getInputStream().readAllBytes();
        long userId = currentUserId();
        IdempotencyService.Claim claim = service.claim(userId, req.getMethod(), req.getRequestURI(), key, body);
        if (claim.replay() != null) {
            write(res, claim.replay(), true, null);
            return;
        }
        if (claim.conflict() != null) {
            write(res, new IdempotencyService.CachedResponse(409,
                "{\"code\":\"" + claim.conflict() + "\",\"message\":\"请求幂等键状态冲突\"}"), false,
                "IDEMPOTENCY_IN_PROGRESS".equals(claim.conflict()) ? "1" : null);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
        boolean completed = false;
        try {
            chain.doFilter(new CachedBodyRequest(req, body), wrapped);
            completed = true;
        } finally {
            byte[] responseBody = wrapped.getContentAsByteArray();
            String response = new String(responseBody, StandardCharsets.UTF_8);
            if (completed && wrapped.getStatus() >= 200 && wrapped.getStatus() < 300) {
                service.succeed(claim, wrapped.getStatus(), response, body);
            } else {
                service.fail(claim, body);
            }
            wrapped.copyBodyToResponse();
        }
    }

    private static void write(HttpServletResponse res, IdempotencyService.CachedResponse response,
                              boolean replayed, String retryAfter) throws IOException {
        res.setStatus(response.status());
        res.setContentType("application/json;charset=UTF-8");
        if (replayed) res.setHeader("Idempotency-Replayed", "true");
        if (retryAfter != null) res.setHeader("Retry-After", retryAfter);
        byte[] body = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        res.setContentLength(body.length);
        res.getOutputStream().write(body);
    }

    private static String readKey(HttpServletRequest req) {
        for (String header : HEADER_NAMES) {
            String value = req.getHeader(header);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static long currentUserId() {
        var principal = PrincipalHolder.current();
        if (principal.isPresent()) return principal.get().userId();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? 0L : 0L;
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request); this.body = body;
        }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(jakarta.servlet.ReadListener listener) { }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding() == null
                ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }
}

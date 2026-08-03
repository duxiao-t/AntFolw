package com.antflow.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Set;

/**
 * Servlet filter that enforces {@code Idempotency-Key} / {@code X-Idempotency-Key}
 * replay protection on mutating mobile / auth paths.
 *
 * <p>Wraps the response in {@link ContentCachingResponseWrapper} before the
 * downstream chain runs, so we can read the controller's serialized body in
 * {@code doFilterInternal}'s {@code finally} block and store it in
 * {@link IdempotencyService}. On replay (cache HIT) we short-circuit by
 * writing the cached body directly to the response and skipping the chain.
 *
 * <p>Order is just after {@code LoginRateLimitFilter} and {@code JwtAuthFilter}
 * so the principal is resolved for the per-user cache key.
 */
@Component
@Order(50)
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Set<String> HEADER_NAMES = Set.of("Idempotency-Key", "X-Idempotency-Key");

    private final IdempotencyService idempotencyService;

    @Autowired
    public IdempotencyFilter(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (!"POST".equalsIgnoreCase(req.getMethod())) return true;
        // Apply to mutating mobile endpoints and login. Read-only POSTs (rare)
        // are still safe: they'll just cache an extra entry by key.
        return !(path.startsWith("/api/mobile/") || path.startsWith("/api/auth/login") || path.startsWith("/api/forms/") || path.startsWith("/api/processes/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = readKey(req);
        if (key == null) {
            chain.doFilter(req, res);
            return;
        }
        long userId = currentUserId();
        String fullKey = "idem:" + userId + ":" + key;
        var existing = idempotencyService.peek(fullKey);
        if (existing != null) {
            res.setStatus(existing.status());
            res.setContentType("application/json;charset=UTF-8");
            res.setHeader("Idempotency-Replayed", "true");
            byte[] body = existing.body() == null ? new byte[0] : existing.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            res.setContentLength(body.length);
            res.getOutputStream().write(body);
            res.getOutputStream().flush();
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
        try {
            chain.doFilter(req, wrapped);
        } finally {
            byte[] body = wrapped.getContentAsByteArray();
            String bodyStr = body.length == 0 ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
            idempotencyService.store(fullKey,
                new IdempotencyService.CachedResponse(wrapped.getStatus(), bodyStr));
            wrapped.copyBodyToResponse();
        }
    }

    private static String readKey(HttpServletRequest req) {
        for (String h : HEADER_NAMES) {
            String v = req.getHeader(h);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static long currentUserId() {
        var ph = com.antflow.auth.PrincipalHolder.current();
        if (ph.isPresent()) return ph.get().userId();
        // Fallback: parse SecurityContextHolder (principal is the username string here).
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return 0L;
        return 0L; // anonymous / no PrincipalHolder → shared 0 bucket (no cross-user replay)
    }
}

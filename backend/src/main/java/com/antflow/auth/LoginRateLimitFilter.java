package com.antflow.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long perMinute;
    private final long perHour;

    public LoginRateLimitFilter(@Value("${antflow.login.per-minute:5}") long perMinute,
                                @Value("${antflow.login.per-hour:30}") long perHour) {
        this.perMinute = perMinute;
        this.perHour = perHour;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(req.getMethod()) && "/api/auth/login".equals(req.getRequestURI())) {
            Bucket b = buckets.computeIfAbsent(req.getRemoteAddr(), k -> newBucket());
            if (!b.tryConsume(1)) {
                res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"too many login attempts\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private Bucket newBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(perMinute)
                .refillGreedy(perMinute, Duration.ofMinutes(1)).build())
            .addLimit(Bandwidth.builder().capacity(perHour)
                .refillGreedy(perHour, Duration.ofHours(1)).build())
            .build();
    }
}

package com.antflow.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class AuditDenialFilter extends OncePerRequestFilter {
    private final AuditService auditService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        filterChain.doFilter(request, response);
        if (response.getStatus() == 401 || response.getStatus() == 403) {
            try {
                auditService.denied(response.getStatus() == 401
                        ? "security.authentication.required" : "security.access.denied",
                    "HTTP_REQUEST", request.getRequestURI(),
                    response.getStatus() == 401 ? "UNAUTHENTICATED" : "ACCESS_DENIED",
                    Map.of("method", request.getMethod(), "path", request.getRequestURI()));
            } catch (RuntimeException ignored) {
                // Authorization responses must not be replaced by an audit storage outage.
            }
        }
    }
}

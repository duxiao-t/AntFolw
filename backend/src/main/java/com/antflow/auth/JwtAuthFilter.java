package com.antflow.auth;

import com.antflow.authz.AuthorizationService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AuthSessionService sessionService;
    private final AuthorizationService authorizationService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims c = jwtService.parse(token);
                long userId = Long.parseLong(c.getSubject());
                String sessionId = c.get("sid", String.class);
                if (sessionId != null && sessionService.isActive(userId, UUID.fromString(sessionId))) {
                    UUID parsedSessionId = UUID.fromString(sessionId);
                    authorizationService.principalForRequest(userId, parsedSessionId)
                        .ifPresent(principal -> {
                            PrincipalHolder.set(principal);
                            var authorities = new ArrayList<SimpleGrantedAuthority>();
                            principal.roles().forEach(role -> authorities.add(
                                new SimpleGrantedAuthority("ROLE_" + role)));
                            principal.permissions().forEach(permission -> authorities.add(
                                new SimpleGrantedAuthority(permission)));
                            var auth = new UsernamePasswordAuthenticationToken(
                                principal.username(), null, authorities);
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        });
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // invalid token → leave anonymous; SecurityConfig rejects with 401
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            PrincipalHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }
}

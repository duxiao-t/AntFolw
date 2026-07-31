package com.antflow.auth;

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
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AuthSessionService sessionService;

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
                    String username = c.get("username", String.class);
                    @SuppressWarnings("unchecked")
                    List<String> roles = (List<String>) c.get("roles", List.class);
                    PrincipalHolder.set(new PrincipalHolder.Principal(userId, username, roles));
                    var authorities = roles.stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList();
                    var auth = new UsernamePasswordAuthenticationToken(username, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
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

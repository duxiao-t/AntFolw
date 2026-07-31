package com.antflow.auth;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class LoginController {

    private final AuthService authService;
    private final AuthSessionService sessionService;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq body, HttpServletRequest request,
                                     HttpServletResponse response) {
        var auth = authService.authenticate(body.username(), body.password())
            .orElseThrow(() -> new BadCredentialsException("invalid credentials"));
        return payload(sessionService.create(auth, request, response));
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(
            @CookieValue(name = AuthSessionService.REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = AuthSessionService.CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfHeader,
            HttpServletRequest request, HttpServletResponse response) {
        return payload(sessionService.refresh(
            refreshToken, csrfCookie, csrfHeader, request, response));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = AuthSessionService.REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = AuthSessionService.CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfHeader,
            HttpServletRequest request, HttpServletResponse response) {
        sessionService.logout(refreshToken, csrfCookie, csrfHeader, request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public List<AuthSessionService.DeviceSessionDto> sessions(
            @CookieValue(name = AuthSessionService.REFRESH_COOKIE, required = false) String refreshToken) {
        return sessionService.list(principal().userId(), refreshToken);
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID id) {
        sessionService.revoke(principal().userId(), id);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> payload(AuthService.Authenticated auth) {
        return Map.of(
            "accessToken", auth.accessToken(),
            "user", Map.of(
                "id", auth.user().getId(),
                "username", auth.user().getUsername(),
                "displayName", auth.user().getDisplayName(),
                "email", auth.user().getEmail() == null ? "" : auth.user().getEmail(),
                "roles", auth.roles()
            )
        );
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var p = principal();
        return Map.of(
            "id", p.userId(),
            "username", p.username(),
            "roles", p.roles()
        );
    }

    private PrincipalHolder.Principal principal() {
        return PrincipalHolder.current().orElseThrow(() ->
            new org.springframework.security.access.AccessDeniedException("not authenticated"));
    }

    public record LoginReq(String username, String password) {}
}

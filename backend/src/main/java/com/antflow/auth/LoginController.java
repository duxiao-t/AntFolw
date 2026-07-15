package com.antflow.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class LoginController {

    private final AuthService authService;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq body) {
        var auth = authService.authenticate(body.username(), body.password())
            .orElseThrow(() -> new BadCredentialsException("invalid credentials"));
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
        var p = PrincipalHolder.current().orElseThrow(() ->
            new org.springframework.security.access.AccessDeniedException("not authenticated"));
        return Map.of(
            "id", p.userId(),
            "username", p.username(),
            "roles", p.roles()
        );
    }

    public record LoginReq(String username, String password) {}
}

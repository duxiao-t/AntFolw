package com.antflow.auth;

import com.antflow.authz.AuthorizationService;
import com.antflow.audit.AuditService;
import com.antflow.mobile.workflow.MobileBootstrapService;
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
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final MobileBootstrapService mobileBootstrapService;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq body,
                                     @RequestParam(defaultValue = "false") boolean includeMobileBootstrap,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        var candidate = authService.authenticate(body.username(), body.password());
        if (candidate.isEmpty()) {
            auditService.failure("auth.login", "USER", body.username(),
                AuditService.RiskLevel.HIGH, "INVALID_CREDENTIALS",
                Map.of("username", body.username() == null ? "" : body.username()));
            throw new BadCredentialsException("invalid credentials");
        }
        var authenticated = auditService.execute(
            () -> sessionService.create(candidate.get(), request, response),
            result -> auditService.successAs(auditPrincipal(result), "auth.login", "USER",
                result.user().getId(), AuditService.RiskLevel.NORMAL, Map.of(),
                Map.of("username", result.user().getUsername())));
        return payload(authenticated, includeMobileBootstrap);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(
            @CookieValue(name = AuthSessionService.REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = AuthSessionService.CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfHeader,
            @RequestParam(defaultValue = "false") boolean includeMobileBootstrap,
            HttpServletRequest request, HttpServletResponse response) {
        var authenticated = auditService.execute(
            () -> sessionService.refresh(refreshToken, csrfCookie, csrfHeader, request, response),
            result -> auditService.successAs(auditPrincipal(result), "auth.refresh", "USER",
                result.user().getId(), AuditService.RiskLevel.NORMAL, Map.of(), Map.of()));
        return payload(authenticated, includeMobileBootstrap);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = AuthSessionService.REFRESH_COOKIE, required = false) String refreshToken,
            @CookieValue(name = AuthSessionService.CSRF_COOKIE, required = false) String csrfCookie,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfHeader,
            HttpServletRequest request, HttpServletResponse response) {
        auditService.execute(
            () -> sessionService.logout(refreshToken, csrfCookie, csrfHeader, request, response),
            () -> auditService.success("auth.logout", "SESSION", null,
                AuditService.RiskLevel.NORMAL, Map.of(), Map.of()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public List<AuthSessionService.DeviceSessionDto> sessions(
            @CookieValue(name = AuthSessionService.REFRESH_COOKIE, required = false) String refreshToken) {
        return sessionService.list(principal().userId(), refreshToken);
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID id) {
        long userId = principal().userId();
        auditService.execute(() -> sessionService.revoke(userId, id),
            () -> auditService.success("auth.session.revoke", "SESSION", id,
                AuditService.RiskLevel.HIGH, Map.of(), Map.of()));
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> payload(AuthService.Authenticated auth,
                                        boolean includeMobileBootstrap) {
        var snapshot = authorizationService.snapshot(auth.user().getId());
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("accessToken", auth.accessToken());
        result.put("user", Map.of(
                "id", auth.user().getId(),
                "username", auth.user().getUsername(),
                "displayName", auth.user().getDisplayName(),
                "email", auth.user().getEmail() == null ? "" : auth.user().getEmail(),
                "roles", auth.roles(),
                "permissions", snapshot.permissions(),
                "authzVersion", auth.user().getAuthzVersion()));
        result.put("permissions", snapshot.permissions());
        result.put("authzVersion", auth.user().getAuthzVersion());
        if (includeMobileBootstrap) {
            result.put("mobileBootstrap", mobileBootstrapService.bootstrap(
                auth.user().getId(), auth.roles()));
        }
        return result;
    }

    private PrincipalHolder.Principal auditPrincipal(AuthService.Authenticated auth) {
        var snapshot = authorizationService.snapshot(auth.user().getId());
        return new PrincipalHolder.Principal(
            auth.user().getId(),
            auth.user().getUsername(),
            auth.user().getDisplayName(),
            snapshot.roleCodes(),
            snapshot.permissions(),
            auth.user().getAuthzVersion(),
            snapshot.departmentId(),
            auth.sessionId()
        );
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var p = principal();
        return Map.of(
            "id", p.userId(),
            "username", p.username(),
            "displayName", p.displayName(),
            "roles", p.roles(),
            "permissions", p.permissions(),
            "authzVersion", p.authzVersion(),
            "departmentId", p.departmentId() == null ? "" : p.departmentId()
        );
    }

    private PrincipalHolder.Principal principal() {
        return PrincipalHolder.current().orElseThrow(() ->
            new org.springframework.security.access.AccessDeniedException("not authenticated"));
    }

    public record LoginReq(String username, String password) {}
}

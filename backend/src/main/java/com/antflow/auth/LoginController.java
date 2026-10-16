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
import com.antflow.authz.AuthenticatedOnly;
import com.antflow.authz.PublicEndpoint;

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
    @PublicEndpoint
    public SessionPayload login(@RequestBody LoginReq body,
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
    @PublicEndpoint
    public SessionPayload refresh(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfHeader,
            @RequestParam(defaultValue = "false") boolean includeMobileBootstrap,
            HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookie(request, sessionService.refreshCookieName());
        String csrfCookie = cookie(request, sessionService.csrfCookieName());
        var authenticated = auditService.execute(
            () -> sessionService.refresh(refreshToken, csrfCookie, csrfHeader, request, response),
            result -> auditService.successAs(auditPrincipal(result), "auth.refresh", "USER",
                result.user().getId(), AuditService.RiskLevel.NORMAL, Map.of(), Map.of()));
        return payload(authenticated, includeMobileBootstrap);
    }

    @PostMapping("/logout")
    @PublicEndpoint
    public ResponseEntity<Void> logout(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfHeader,
            HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookie(request, sessionService.refreshCookieName());
        String csrfCookie = cookie(request, sessionService.csrfCookieName());
        auditService.execute(
            () -> sessionService.logout(refreshToken, csrfCookie, csrfHeader, request, response),
            () -> auditService.success("auth.logout", "SESSION", null,
                AuditService.RiskLevel.NORMAL, Map.of(), Map.of()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    @AuthenticatedOnly
    public List<AuthSessionService.DeviceSessionDto> sessions(HttpServletRequest request) {
        return sessionService.list(principal().userId(),
            cookie(request, sessionService.refreshCookieName()));
    }

    @DeleteMapping("/sessions/{id}")
    @AuthenticatedOnly
    public ResponseEntity<Void> revokeSession(@PathVariable UUID id) {
        long userId = principal().userId();
        auditService.execute(() -> sessionService.revoke(userId, id),
            () -> auditService.success("auth.session.revoke", "SESSION", id,
                AuditService.RiskLevel.HIGH, Map.of(), Map.of()));
        return ResponseEntity.noContent().build();
    }

    private SessionPayload payload(AuthService.Authenticated auth,
                                        boolean includeMobileBootstrap) {
        var snapshot = authorizationService.snapshot(auth.user().getId());
        SessionUser user = new SessionUser(auth.user().getId(), auth.user().getUsername(),
            auth.user().getDisplayName(), auth.user().getEmail() == null ? "" : auth.user().getEmail(),
            auth.roles(), snapshot.permissions(), auth.user().getAuthzVersion());
        return new SessionPayload(auth.accessToken(), user, snapshot.permissions(),
            auth.user().getAuthzVersion(), includeMobileBootstrap
                ? mobileBootstrapService.bootstrap(auth.user().getId(), auth.roles()) : null);
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
    @AuthenticatedOnly
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

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    public record LoginReq(String username, String password) {}
    public record SessionUser(Long id, String username, String displayName, String email,
                               List<String> roles, java.util.Set<String> permissions,
                               Long authzVersion) { }
    public record SessionPayload(String accessToken, SessionUser user,
                                  java.util.Set<String> permissions, Long authzVersion,
                                  @com.fasterxml.jackson.annotation.JsonInclude(
                                      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                  com.antflow.mobile.workflow.MobileBootstrapDto mobileBootstrap) { }
}

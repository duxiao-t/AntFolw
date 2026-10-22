package com.antflow.auth;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.integration.wecom.WecomSecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OidcService {
    private static final Duration FLOW_TTL = Duration.ofMinutes(5);
    private final JdbcTemplate jdbc;
    private final WecomSecretCipher cipher;
    private final ExternalAuthProperties properties;
    private final AuthorizationService authorization;
    private final AuthService authService;
    private final AuthSessionService sessions;
    private final OidcDiscoveryService oidcDiscovery;
    private final OidcTokenClient oidcTokens;
    private final OidcIdentityBinder oidcIdentities;
    private final SecureRandom random = new SecureRandom();

    public OidcService(JdbcTemplate jdbc, WecomSecretCipher cipher,
                       ExternalAuthProperties properties, AuthorizationService authorization,
                       AuthService authService, AuthSessionService sessions,
                       OidcDiscoveryService oidcDiscovery, OidcTokenClient oidcTokens,
                       OidcIdentityBinder oidcIdentities) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.properties = properties;
        this.authorization = authorization;
        this.authService = authService;
        this.sessions = sessions;
        this.oidcDiscovery = oidcDiscovery;
        this.oidcTokens = oidcTokens;
        this.oidcIdentities = oidcIdentities;
    }

    public List<PublicProvider> publicProviders() {
        return jdbc.query("""
            SELECT code, display_name FROM t_oidc_provider
            WHERE enabled ORDER BY display_name, id
            """, (rs, row) -> new PublicProvider(rs.getString(1), rs.getString(2)));
    }

    public URI authorize(String providerCode, String returnUrl) {
        OidcProvider provider = requireProvider(providerCode, true);
        OidcDiscoveryService.Discovery discovery = oidcDiscovery.discover(provider.issuerUri());
        String state = randomToken(32);
        String stateHash = AuthSessionService.hash(state);
        String nonce = randomToken(24);
        String verifier = randomToken(48);
        String challenge = base64Sha256(verifier);
        String callback = callbackUrl(provider.code());
        jdbc.update("""
            INSERT INTO t_external_auth_flow(state_hash, provider_type, provider_id, nonce,
                pkce_verifier_encrypted, return_path, expires_at)
            VALUES (?, 'OIDC', ?, ?, ?, ?, now() + interval '5 minutes')
            """, stateHash, provider.id(), nonce, cipher.encrypt(verifier, stateHash),
            safeReturnPath(returnUrl, "/"));
        return URI.create(discovery.authorizationEndpoint() + "?" + OidcSupport.form(Map.of(
            "response_type", "code", "client_id", provider.clientId(),
            "redirect_uri", callback, "scope", provider.scopes(), "state", state,
            "nonce", nonce, "code_challenge", challenge, "code_challenge_method", "S256")));
    }

    public URI callback(String providerCode, String state, String authorizationCode,
                        HttpServletRequest request, HttpServletResponse response) {
        if (state == null || state.isBlank() || authorizationCode == null || authorizationCode.isBlank()) {
            throw new BadCredentialsException("invalid OIDC callback");
        }
        String stateHash = AuthSessionService.hash(state);
        Flow flow = jdbc.query("""
            UPDATE t_external_auth_flow SET consumed_at = now()
            WHERE state_hash = ? AND provider_type = 'OIDC' AND consumed_at IS NULL
              AND expires_at > now()
            RETURNING provider_id, nonce, pkce_verifier_encrypted, return_path
            """, rs -> rs.next() ? new Flow(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4)) : null, stateHash);
        if (flow == null) throw new BadCredentialsException("OIDC state is invalid or expired");
        OidcProvider provider = requireProvider(providerCode, true);
        if (provider.id() != flow.providerId()) throw new BadCredentialsException("OIDC provider mismatch");
        OidcDiscoveryService.Discovery discovery = oidcDiscovery.discover(provider.issuerUri());
        String verifier = cipher.decrypt(flow.encryptedVerifier(), stateHash);
        JsonNode tokens = oidcTokens.exchange(provider, discovery, authorizationCode, verifier);
        String idToken = tokens.path("id_token").asText();
        if (idToken.isBlank()) throw new BadCredentialsException("OIDC provider did not return an ID token");
        Jwt jwt = oidcTokens.verify(provider, discovery, idToken);
        if (!constantTimeEquals(flow.nonce(), jwt.getClaimAsString("nonce"))) {
            throw new BadCredentialsException("OIDC nonce mismatch");
        }
        long userId = oidcIdentities.bindUser(provider, jwt);
        AuthService.Authenticated authenticated = authService.resume(userId)
            .orElseThrow(() -> new BadCredentialsException("local user is disabled"));
        sessions.create(authenticated, request, response);
        return properties.publicBaseUri().resolve(flow.returnPath());
    }

    public List<ProviderDto> providers() {
        requireManage();
        return jdbc.query("""
            SELECT id, code, display_name, issuer_uri, client_id, client_auth_method,
                   scopes, match_claim, match_field, enabled, client_secret_encrypted <> ''
            FROM t_oidc_provider ORDER BY display_name, id
            """, (rs, row) -> new ProviderDto(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getBoolean(10), rs.getBoolean(11)));
    }

    @Transactional(rollbackFor = Exception.class)
    public ProviderDto save(Long id, SaveProvider input) {
        requireManage();
        String code = OidcSupport.required(input.code(), "code").toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z0-9][a-z0-9_-]{1,63}")) {
            throw new BizException("OIDC_CODE_INVALID", "提供方代码格式不正确");
        }
        String issuer = oidcDiscovery.normalizeIssuer(input.issuerUri());
        OidcProvider current = id == null ? null : requireProvider(id);
        if (current != null && !current.issuerUri().equals(issuer)) {
            Long bindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_oidc_identity_binding WHERE provider_id = ?",
                Long.class, id);
            requireIssuerChangeSafe(current.issuerUri(), issuer,
                bindings == null ? 0 : bindings);
        }
        oidcDiscovery.discover(issuer);
        long actor = authorization.currentUserId();
        if (id == null) {
            String secret = OidcSupport.required(input.clientSecret(), "clientSecret");
            Long createdId = jdbc.queryForObject("""
                INSERT INTO t_oidc_provider(code, display_name, issuer_uri, client_id,
                    client_secret_encrypted, client_auth_method, scopes, match_claim,
                    match_field, enabled, created_by, updated_by)
                VALUES (?, ?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, code, OidcSupport.required(input.displayName(), "displayName"), issuer,
                OidcSupport.required(input.clientId(), "clientId"), authMethod(input.clientAuthMethod()),
                scopes(input.scopes()), OidcSupport.required(input.matchClaim(), "matchClaim"),
                matchField(input.matchField()), input.enabled(), actor, actor);
            jdbc.update("UPDATE t_oidc_provider SET client_secret_encrypted = ? WHERE id = ?",
                cipher.encrypt(secret, "oidc:" + createdId), createdId);
            id = createdId;
        } else {
            String encrypted = input.clientSecret() == null || input.clientSecret().isBlank()
                ? current.encryptedSecret() : cipher.encrypt(input.clientSecret().trim(), "oidc:" + id);
            jdbc.update("""
                UPDATE t_oidc_provider SET code = ?, display_name = ?, issuer_uri = ?, client_id = ?,
                    client_secret_encrypted = ?, client_auth_method = ?, scopes = ?, match_claim = ?,
                    match_field = ?, enabled = ?, updated_by = ?, updated_at = now() WHERE id = ?
                """, code, OidcSupport.required(input.displayName(), "displayName"), issuer,
                OidcSupport.required(input.clientId(), "clientId"), encrypted,
                authMethod(input.clientAuthMethod()), scopes(input.scopes()),
                OidcSupport.required(input.matchClaim(), "matchClaim"), matchField(input.matchField()),
                input.enabled(), actor, id);
        }
        long savedId = id;
        return providers().stream().filter(item -> item.id() == savedId).findFirst().orElseThrow();
    }

    static void requireIssuerChangeSafe(String currentIssuer, String nextIssuer, long bindings) {
        if (!currentIssuer.equals(nextIssuer) && bindings > 0) {
            throw new BizException("OIDC_ISSUER_IN_USE",
                "存在身份绑定时不能更换 OIDC issuer");
        }
    }

    public void delete(long id) {
        requireManage();
        if (jdbc.update("DELETE FROM t_oidc_provider WHERE id = ?", id) == 0) {
            throw new com.antflow.authz.HiddenResourceException("provider not found");
        }
    }

    public List<BindingDto> bindings(long providerId) {
        requireManage();
        return jdbc.query("""
            SELECT binding.id, binding.subject, binding.user_id, user_row.username,
                   user_row.display_name, binding.last_login_at
            FROM t_oidc_identity_binding binding JOIN t_user user_row ON user_row.id = binding.user_id
            WHERE binding.provider_id = ? ORDER BY binding.last_login_at DESC
            """, (rs, row) -> new BindingDto(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), rs.getObject(6, java.time.OffsetDateTime.class)),
            providerId);
    }

    public void unbind(long providerId, long bindingId) {
        requireManage();
        jdbc.update("DELETE FROM t_oidc_identity_binding WHERE id = ? AND provider_id = ?",
            bindingId, providerId);
    }

    @Scheduled(cron = "0 15 * * * *")
    public void cleanupFlows() {
        jdbc.update("DELETE FROM t_external_auth_flow WHERE expires_at < now() - interval '1 day'");
    }

    private OidcProvider requireProvider(String code, boolean enabled) {
        OidcProvider provider = jdbc.query("""
            SELECT id, code, issuer_uri, client_id, client_secret_encrypted, client_auth_method,
                   scopes, match_claim, match_field, enabled FROM t_oidc_provider WHERE code = ?
            """, rs -> rs.next() ? mapProvider(rs) : null, code);
        if (provider == null || (enabled && !provider.enabled())) {
            throw new com.antflow.authz.HiddenResourceException("provider not found");
        }
        return provider;
    }

    private OidcProvider requireProvider(long id) {
        OidcProvider provider = jdbc.query("""
            SELECT id, code, issuer_uri, client_id, client_secret_encrypted, client_auth_method,
                   scopes, match_claim, match_field, enabled FROM t_oidc_provider WHERE id = ?
            """, rs -> rs.next() ? mapProvider(rs) : null, id);
        if (provider == null) throw new com.antflow.authz.HiddenResourceException("provider not found");
        return provider;
    }

    private static OidcProvider mapProvider(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OidcProvider(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getString(9), rs.getBoolean(10));
    }

    private void requireManage() {
        authorization.requirePermission(PermissionCodes.INTEGRATION_IDENTITY_PROVIDER_MANAGE);
    }

    private String callbackUrl(String code) {
        return properties.publicBaseUri().resolve("/api/public/auth/oidc/" + OidcSupport.encode(code) + "/callback").toString();
    }

    public static String safeReturnPath(String candidate, String fallback) {
        if (candidate == null || !candidate.startsWith("/") || candidate.startsWith("//")
            || candidate.contains("://") || candidate.contains("\\")) return fallback;
        return candidate;
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String base64Sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String authMethod(String value) {
        return "POST".equalsIgnoreCase(value) ? "POST" : "BASIC";
    }

    private static String matchField(String value) {
        return switch (value) { case "email", "employeeNo" -> value; default -> "username"; };
    }

    private static String scopes(String value) {
        String scopes = value == null ? "" : value.trim();
        return scopes.contains("openid") ? scopes : "openid " + scopes;
    }

    private record Flow(long providerId, String nonce, String encryptedVerifier, String returnPath) { }
    public record PublicProvider(String code, String displayName) { }
    public record ProviderDto(long id, String code, String displayName, String issuerUri,
                              String clientId, String clientAuthMethod, String scopes,
                              String matchClaim, String matchField, boolean enabled,
                              boolean secretConfigured) { }
    public record SaveProvider(String code, String displayName, String issuerUri, String clientId,
                               String clientSecret, String clientAuthMethod, String scopes,
                               String matchClaim, String matchField, boolean enabled) { }
    public record BindingDto(long id, String subject, long userId, String username,
                             String displayName, java.time.OffsetDateTime lastLoginAt) { }
}

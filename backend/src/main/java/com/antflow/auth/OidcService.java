package com.antflow.auth;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.integration.wecom.WecomSecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OidcService {
    private static final Duration FLOW_TTL = Duration.ofMinutes(5);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WecomSecretCipher cipher;
    private final ExternalAuthProperties properties;
    private final AuthorizationService authorization;
    private final AuthService authService;
    private final AuthSessionService sessions;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public OidcService(JdbcTemplate jdbc, ObjectMapper json, WecomSecretCipher cipher,
                       ExternalAuthProperties properties, AuthorizationService authorization,
                       AuthService authService, AuthSessionService sessions,
                       TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
        this.properties = properties;
        this.authorization = authorization;
        this.authService = authService;
        this.sessions = sessions;
        this.transactions = transactions;
    }

    public List<PublicProvider> publicProviders() {
        return jdbc.query("""
            SELECT code, display_name FROM t_oidc_provider
            WHERE enabled ORDER BY display_name, id
            """, (rs, row) -> new PublicProvider(rs.getString(1), rs.getString(2)));
    }

    public URI authorize(String providerCode, String returnUrl) {
        Provider provider = requireProvider(providerCode, true);
        Discovery discovery = discover(provider.issuerUri());
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
        return URI.create(discovery.authorizationEndpoint() + "?" + form(Map.of(
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
        Provider provider = requireProvider(providerCode, true);
        if (provider.id() != flow.providerId()) throw new BadCredentialsException("OIDC provider mismatch");
        Discovery discovery = discover(provider.issuerUri());
        String verifier = cipher.decrypt(flow.encryptedVerifier(), stateHash);
        JsonNode tokens = exchange(provider, discovery, authorizationCode, verifier);
        String idToken = tokens.path("id_token").asText();
        if (idToken.isBlank()) throw new BadCredentialsException("OIDC provider did not return an ID token");
        Jwt jwt = verify(provider, discovery, idToken);
        if (!constantTimeEquals(flow.nonce(), jwt.getClaimAsString("nonce"))) {
            throw new BadCredentialsException("OIDC nonce mismatch");
        }
        long userId = bindUser(provider, jwt);
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
        String code = required(input.code(), "code").toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z0-9][a-z0-9_-]{1,63}")) {
            throw new BizException("OIDC_CODE_INVALID", "提供方代码格式不正确");
        }
        String issuer = normalizeAndValidateIssuer(input.issuerUri());
        discover(issuer);
        long actor = authorization.currentUserId();
        if (id == null) {
            String secret = required(input.clientSecret(), "clientSecret");
            Long createdId = jdbc.queryForObject("""
                INSERT INTO t_oidc_provider(code, display_name, issuer_uri, client_id,
                    client_secret_encrypted, client_auth_method, scopes, match_claim,
                    match_field, enabled, created_by, updated_by)
                VALUES (?, ?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, code, required(input.displayName(), "displayName"), issuer,
                required(input.clientId(), "clientId"), authMethod(input.clientAuthMethod()),
                scopes(input.scopes()), required(input.matchClaim(), "matchClaim"),
                matchField(input.matchField()), input.enabled(), actor, actor);
            jdbc.update("UPDATE t_oidc_provider SET client_secret_encrypted = ? WHERE id = ?",
                cipher.encrypt(secret, "oidc:" + createdId), createdId);
            id = createdId;
        } else {
            Provider current = requireProvider(id);
            String encrypted = input.clientSecret() == null || input.clientSecret().isBlank()
                ? current.encryptedSecret() : cipher.encrypt(input.clientSecret().trim(), "oidc:" + id);
            jdbc.update("""
                UPDATE t_oidc_provider SET code = ?, display_name = ?, issuer_uri = ?, client_id = ?,
                    client_secret_encrypted = ?, client_auth_method = ?, scopes = ?, match_claim = ?,
                    match_field = ?, enabled = ?, updated_by = ?, updated_at = now() WHERE id = ?
                """, code, required(input.displayName(), "displayName"), issuer,
                required(input.clientId(), "clientId"), encrypted,
                authMethod(input.clientAuthMethod()), scopes(input.scopes()),
                required(input.matchClaim(), "matchClaim"), matchField(input.matchField()),
                input.enabled(), actor, id);
        }
        long savedId = id;
        return providers().stream().filter(item -> item.id() == savedId).findFirst().orElseThrow();
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

    private JsonNode exchange(Provider provider, Discovery discovery, String code, String verifier) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("grant_type", "authorization_code");
        values.put("code", code);
        values.put("redirect_uri", callbackUrl(provider.code()));
        values.put("client_id", provider.clientId());
        values.put("code_verifier", verifier);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(discovery.tokenEndpoint()))
            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded");
        String secret = cipher.decrypt(provider.encryptedSecret(), "oidc:" + provider.id());
        if ("BASIC".equals(provider.clientAuthMethod())) {
            request.header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(
                (provider.clientId() + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        } else {
            values.put("client_secret", secret);
        }
        return sendJson(request.POST(HttpRequest.BodyPublishers.ofString(form(values))).build(),
            "OIDC token endpoint");
    }

    private Jwt verify(Provider provider, Discovery discovery, String token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(discovery.jwksUri()).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(provider.clientId())
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "invalid audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(provider.issuerUri()), audience));
        return decoder.decode(token);
    }

    private long bindUser(Provider provider, Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) throw new BadCredentialsException("OIDC subject is missing");
        Long bound = jdbc.query("""
            SELECT user_id FROM t_oidc_identity_binding WHERE provider_id = ? AND subject = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, provider.id(), subject);
        if (bound != null) {
            requireActiveUser(bound);
            jdbc.update("UPDATE t_oidc_identity_binding SET last_login_at = now() WHERE provider_id = ? AND subject = ?",
                provider.id(), subject);
            return bound;
        }
        if ("email".equals(provider.matchField()) && !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new BadCredentialsException("OIDC email is not verified");
        }
        Object rawClaim = jwt.getClaims().get(provider.matchClaim());
        String claim = rawClaim == null ? "" : rawClaim.toString().trim();
        if (claim.isBlank()) throw new BadCredentialsException("OIDC matching claim is missing");
        String column = switch (provider.matchField()) {
            case "email" -> "email";
            case "employeeNo" -> "employee_no";
            default -> "username";
        };
        List<Long> matches = jdbc.queryForList("SELECT id FROM t_user WHERE status = 'ACTIVE' AND lower("
            + column + ") = lower(?)", Long.class, claim);
        if (matches.size() != 1) throw new BadCredentialsException("OIDC identity does not match one active user");
        long userId = matches.get(0);
        try {
            return transactions.execute(status -> {
                jdbc.update("""
                    INSERT INTO t_oidc_identity_binding(provider_id, subject, user_id)
                    VALUES (?, ?, ?) ON CONFLICT (provider_id, subject) DO NOTHING
                    """, provider.id(), subject, userId);
                Long result = jdbc.queryForObject("""
                    SELECT user_id FROM t_oidc_identity_binding WHERE provider_id = ? AND subject = ?
                    """, Long.class, provider.id(), subject);
                if (result == null || result != userId) throw new BadCredentialsException("OIDC identity is already bound");
                return result;
            });
        } catch (DataIntegrityViolationException exception) {
            throw new BadCredentialsException("local user is already bound to another identity");
        }
    }

    private Discovery discover(String issuerInput) {
        String issuer = normalizeAndValidateIssuer(issuerInput);
        URI uri = URI.create(issuer + "/.well-known/openid-configuration");
        JsonNode node = sendJson(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json").GET().build(), "OIDC discovery endpoint");
        if (!issuer.equals(stripTrailingSlash(node.path("issuer").asText()))) {
            throw new BizException("OIDC_DISCOVERY_INVALID", "OIDC Discovery 返回了不同的 issuer");
        }
        String authorization = validatedEndpoint(node.path("authorization_endpoint").asText(), uri);
        String token = validatedEndpoint(node.path("token_endpoint").asText(), uri);
        String jwks = validatedEndpoint(node.path("jwks_uri").asText(), uri);
        return new Discovery(authorization, token, jwks);
    }

    private JsonNode sendJson(HttpRequest request, String label) {
        try {
            HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("OIDC_UPSTREAM_FAILED", label + " 请求失败");
            }
            return json.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException("OIDC_UPSTREAM_FAILED", label + " 请求已中断");
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new BizException("OIDC_UPSTREAM_FAILED", label + " 无法访问");
        }
    }

    private String normalizeAndValidateIssuer(String input) {
        String value = stripTrailingSlash(required(input, "issuerUri"));
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException error) {
            throw new BizException("OIDC_ISSUER_INVALID", "issuer 地址无效");
        }
        String host = uri.getHost();
        boolean loopback = host != null && ("localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host) || "::1".equals(host));
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean httpAllowed = "http".equalsIgnoreCase(uri.getScheme())
            && (loopback || !properties.isOidcHttpsOnly());
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || (!https && !httpAllowed)) {
            throw new BizException("OIDC_ISSUER_INVALID", "issuer 必须使用 HTTPS（本机测试除外）");
        }
        List<String> allowed = properties.getOidcAllowedHosts();
        if (!loopback && (allowed == null || allowed.stream().noneMatch(host::equalsIgnoreCase))) {
            throw new BizException("OIDC_ISSUER_BLOCKED", "issuer 主机不在 ANTFLOW_OIDC_ALLOWED_HOSTS 中");
        }
        if (!loopback) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                        throw new BizException("OIDC_ISSUER_BLOCKED", "issuer 不允许指向内网地址");
                    }
                }
            } catch (java.net.UnknownHostException error) {
                throw new BizException("OIDC_ISSUER_INVALID", "issuer 主机无法解析");
            }
        }
        return value;
    }

    private String validatedEndpoint(String value, URI issuer) {
        URI endpoint = URI.create(value);
        if (!issuer.getHost().equalsIgnoreCase(endpoint.getHost())
            || !issuer.getScheme().equalsIgnoreCase(endpoint.getScheme())) {
            throw new BizException("OIDC_DISCOVERY_INVALID", "OIDC Discovery 端点主机不受信任");
        }
        return endpoint.toString();
    }

    private Provider requireProvider(String code, boolean enabled) {
        Provider provider = jdbc.query("""
            SELECT id, code, issuer_uri, client_id, client_secret_encrypted, client_auth_method,
                   scopes, match_claim, match_field, enabled FROM t_oidc_provider WHERE code = ?
            """, rs -> rs.next() ? mapProvider(rs) : null, code);
        if (provider == null || (enabled && !provider.enabled())) {
            throw new com.antflow.authz.HiddenResourceException("provider not found");
        }
        return provider;
    }

    private Provider requireProvider(long id) {
        Provider provider = jdbc.query("""
            SELECT id, code, issuer_uri, client_id, client_secret_encrypted, client_auth_method,
                   scopes, match_claim, match_field, enabled FROM t_oidc_provider WHERE id = ?
            """, rs -> rs.next() ? mapProvider(rs) : null, id);
        if (provider == null) throw new com.antflow.authz.HiddenResourceException("provider not found");
        return provider;
    }

    private static Provider mapProvider(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Provider(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getString(9), rs.getBoolean(10));
    }

    private void requireManage() {
        authorization.requirePermission(PermissionCodes.PAGE_SETTINGS_IDENTITY_PROVIDERS);
    }

    private void requireActiveUser(long id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM t_user WHERE id = ? AND status = 'ACTIVE'",
            Integer.class, id);
        if (count == null || count != 1) throw new BadCredentialsException("local user is disabled");
    }

    private String callbackUrl(String code) {
        return properties.publicBaseUri().resolve("/api/public/auth/oidc/" + encode(code) + "/callback").toString();
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

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new BizException("OIDC_CONFIG_INVALID", field + " 不能为空");
        return normalized;
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

    private static String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record Provider(long id, String code, String issuerUri, String clientId,
                            String encryptedSecret, String clientAuthMethod, String scopes,
                            String matchClaim, String matchField, boolean enabled) { }
    private record Discovery(String authorizationEndpoint, String tokenEndpoint, String jwksUri) { }
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

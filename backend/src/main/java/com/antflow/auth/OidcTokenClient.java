package com.antflow.auth;

import com.antflow.integration.wecom.WecomSecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * 授权码交换与 ID Token 校验（原 OidcService#exchange / #verify）。
 *
 * 易错点：
 * 1. redirect_uri 必须与发起 authorize 时的值逐字一致，否则提供方拒绝交换；因此这里与 OidcService 各自用同一规则拼。
 * 2. BASIC 方式的 client_secret 只放在 Authorization 头里，POST 方式才放进表单，两者不可同时出现。
 * 3. audience 校验不能省：只校验 issuer 会接受签发给其他 client 的 ID Token。
 */
@Component
class OidcTokenClient {
    private final OidcHttpTransport transport;
    private final WecomSecretCipher cipher;
    private final ExternalAuthProperties properties;

    OidcTokenClient(OidcHttpTransport transport, WecomSecretCipher cipher,
                    ExternalAuthProperties properties) {
        this.transport = transport;
        this.cipher = cipher;
        this.properties = properties;
    }

    JsonNode exchange(OidcProvider provider, OidcDiscoveryService.Discovery discovery,
                      String code, String verifier) {
        Map<String, String> values = new LinkedHashMap<>();
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
        return transport.sendJson(request.POST(
            HttpRequest.BodyPublishers.ofString(OidcSupport.form(values))).build(),
            "OIDC token endpoint");
    }

    Jwt verify(OidcProvider provider, OidcDiscoveryService.Discovery discovery, String token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(discovery.jwksUri()).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(provider.clientId())
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "invalid audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(provider.issuerUri()), audience));
        return decoder.decode(token);
    }

    private String callbackUrl(String code) {
        return properties.publicBaseUri()
            .resolve("/api/public/auth/oidc/" + OidcSupport.encode(code) + "/callback").toString();
    }
}

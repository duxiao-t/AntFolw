package com.antflow.auth;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 配置校验与发现文档（原 OidcService#discover / #normalizeAndValidateIssuer / #validatedEndpoint）。
 *
 * 易错点：
 * 1. issuer 先规范化（去尾斜杠）再比对 discovery 返回的 issuer，否则 https://idp/ 与 https://idp 会被误判为不同 issuer。
 * 2. 发现的三个端点必须与 issuer 同 scheme + 同 host；否则 discovery 能被劫持到任意外部地址。
 * 3. 非 loopback 主机的 DNS 解析结果必须逐个排除内网/环回/链路本地地址（SSRF 防护），本机测试除外。
 */
@Component
class OidcDiscoveryService {
    private final OidcHttpTransport transport;
    private final ExternalAuthProperties properties;

    OidcDiscoveryService(OidcHttpTransport transport, ExternalAuthProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    Discovery discover(String issuerInput) {
        String issuer = normalizeIssuer(issuerInput);
        URI uri = URI.create(issuer + "/.well-known/openid-configuration");
        JsonNode node = transport.sendJson(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json").GET().build(), "OIDC discovery endpoint");
        if (!issuer.equals(OidcSupport.stripTrailingSlash(node.path("issuer").asText()))) {
            throw new BizException("OIDC_DISCOVERY_INVALID", "OIDC Discovery 返回了不同的 issuer");
        }
        String authorization = validatedEndpoint(node.path("authorization_endpoint").asText(), uri);
        String token = validatedEndpoint(node.path("token_endpoint").asText(), uri);
        String jwks = validatedEndpoint(node.path("jwks_uri").asText(), uri);
        return new Discovery(authorization, token, jwks);
    }

    String normalizeIssuer(String input) {
        String value = OidcSupport.stripTrailingSlash(OidcSupport.required(input, "issuerUri"));
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

    record Discovery(String authorizationEndpoint, String tokenEndpoint, String jwksUri) { }
}

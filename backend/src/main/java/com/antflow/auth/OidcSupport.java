package com.antflow.auth;

import com.antflow.engine.BizException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OIDC 拆分后多个类共用的小工具（纯函数、无状态）。
 * 校验错误码与文案必须与原 OidcService 完全一致：OIDC_CONFIG_INVALID。
 */
final class OidcSupport {
    private OidcSupport() { }

    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new BizException("OIDC_CONFIG_INVALID", field + " 不能为空");
        return normalized;
    }

    static String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

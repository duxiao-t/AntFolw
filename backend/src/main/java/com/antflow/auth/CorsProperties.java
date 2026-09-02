package com.antflow.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@ConfigurationProperties(prefix = "antflow.cors")
public class CorsProperties {
    private static final List<String> DEFAULT_ALLOWED_ORIGIN_PATTERNS = List.of(
        "http://localhost:*",
        "http://127.0.0.1:*",
        "http://[::1]:*",
        "http://192.168.*:*",
        "http://10.*:*",
        "http://172.*:*"
    );
    private List<String> allowedOriginPatterns = DEFAULT_ALLOWED_ORIGIN_PATTERNS;

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        if (allowedOriginPatterns == null) {
            this.allowedOriginPatterns = DEFAULT_ALLOWED_ORIGIN_PATTERNS;
            return;
        }
        List<String> normalized = allowedOriginPatterns.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(pattern -> !pattern.isEmpty())
            .toList();
        this.allowedOriginPatterns = normalized.isEmpty()
            ? DEFAULT_ALLOWED_ORIGIN_PATTERNS
            : normalized;
    }
}

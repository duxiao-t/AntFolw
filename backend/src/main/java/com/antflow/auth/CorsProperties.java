package com.antflow.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "antflow.cors")
public class CorsProperties {
    private List<String> allowedOriginPatterns = List.of(
        "http://localhost:*",
        "http://127.0.0.1:*",
        "http://[::1]:*",
        "http://192.168.*:*",
        "http://10.*:*",
        "http://172.*:*"
    );

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }
}

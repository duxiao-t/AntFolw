package com.antflow.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPropertiesTest {
    private static final List<String> DEFAULT_PATTERNS = List.of(
        "http://localhost:*",
        "http://127.0.0.1:*",
        "http://[::1]:*",
        "http://192.168.*:*",
        "http://10.*:*",
        "http://172.*:*"
    );

    @Test
    void keepsSafeDefaultsWhenEnvironmentValueIsMissingOrBlank() {
        CorsProperties properties = new CorsProperties();

        assertThat(properties.getAllowedOriginPatterns()).containsExactlyElementsOf(DEFAULT_PATTERNS);

        properties.setAllowedOriginPatterns(null);
        assertThat(properties.getAllowedOriginPatterns()).containsExactlyElementsOf(DEFAULT_PATTERNS);

        properties.setAllowedOriginPatterns(List.of("", "  "));
        assertThat(properties.getAllowedOriginPatterns()).containsExactlyElementsOf(DEFAULT_PATTERNS);
    }

    @Test
    void preservesExplicitOriginPatternsAndRemovesBlankEntries() {
        CorsProperties properties = new CorsProperties();

        properties.setAllowedOriginPatterns(List.of(" https://approval.example.com ", ""));

        assertThat(properties.getAllowedOriginPatterns())
            .containsExactly("https://approval.example.com");
    }
}

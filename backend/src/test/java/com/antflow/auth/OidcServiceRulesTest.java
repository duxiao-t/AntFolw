package com.antflow.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OidcServiceRulesTest {
    @Test
    void returnPathRejectsOpenRedirectsAndAcceptsLocalPaths() {
        assertThat(OidcService.safeReturnPath("/mobile/tasks/1?tab=pending", "/"))
            .isEqualTo("/mobile/tasks/1?tab=pending");
        assertThat(OidcService.safeReturnPath("https://evil.example", "/")).isEqualTo("/");
        assertThat(OidcService.safeReturnPath("//evil.example/path", "/")).isEqualTo("/");
        assertThat(OidcService.safeReturnPath("/\\evil.example", "/")).isEqualTo("/");
    }
}

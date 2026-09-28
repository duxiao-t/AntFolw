package com.antflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antflow.engine.BizException;

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

    @Test
    void issuerCannotChangeWhileIdentitiesAreBound() {
        assertThatThrownBy(() -> OidcService.requireIssuerChangeSafe(
            "https://issuer.one", "https://issuer.two", 1))
            .isInstanceOf(BizException.class);
        assertThatCode(() -> OidcService.requireIssuerChangeSafe(
            "https://issuer.one", "https://issuer.two", 0)).doesNotThrowAnyException();
    }
}

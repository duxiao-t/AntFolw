package com.antflow.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionAuditCredentialValidatorTest {
    @Test
    void rejectsDevelopmentDefaults() {
        TrustedProxyProperties properties = new TrustedProxyProperties();

        assertThatThrownBy(() -> new ProductionAuditCredentialValidator(properties)
            .afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("archive-access-key")
            .hasMessageContaining("archive-secret-key")
            .hasMessageContaining("archive-encryption-secret");
    }

    @Test
    void acceptsExplicitProductionCredentials() {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setArchiveAccessKey("archive-writer");
        properties.setArchiveSecretKey("a-production-secret");
        properties.setArchiveEncryptionSecret("a-separate-32-byte-encryption-secret");

        assertThatCode(() -> new ProductionAuditCredentialValidator(properties)
            .afterPropertiesSet()).doesNotThrowAnyException();
    }
}

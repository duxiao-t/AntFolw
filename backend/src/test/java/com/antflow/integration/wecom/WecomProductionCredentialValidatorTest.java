package com.antflow.integration.wecom;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WecomProductionCredentialValidatorTest {
    @Test
    void rejectsDevelopmentKey() {
        WecomProperties properties = new WecomProperties();
        assertThatThrownBy(() -> new WecomProductionCredentialValidator(properties)
            .afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsConfiguredKey() {
        WecomProperties properties = new WecomProperties();
        properties.setEncryptionKey("production-managed-key");
        assertThatCode(() -> new WecomProductionCredentialValidator(properties)
            .afterPropertiesSet()).doesNotThrowAnyException();
    }
}

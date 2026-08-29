package com.antflow.integration.wecom;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
class WecomProductionCredentialValidator implements InitializingBean {
    private final WecomProperties properties;

    WecomProductionCredentialValidator(WecomProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (WecomProperties.DEFAULT_ENCRYPTION_KEY.equals(properties.getEncryptionKey())) {
            throw new IllegalStateException(
                "ANTFLOW_INTEGRATION_ENCRYPTION_KEY must be configured in production");
        }
    }
}

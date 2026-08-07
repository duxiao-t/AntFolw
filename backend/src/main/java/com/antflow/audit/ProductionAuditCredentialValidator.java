package com.antflow.audit;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionAuditCredentialValidator implements InitializingBean {
    static final String DEFAULT_MINIO_CREDENTIAL = "minioadmin";
    static final String DEFAULT_ENCRYPTION_SECRET =
        "antflow-dev-audit-archive-key-change-me";

    private final TrustedProxyProperties properties;

    @Override
    public void afterPropertiesSet() {
        List<String> invalid = new ArrayList<>();
        rejectDefault(invalid, "antflow.audit.archive-access-key",
            properties.getArchiveAccessKey(), DEFAULT_MINIO_CREDENTIAL);
        rejectDefault(invalid, "antflow.audit.archive-secret-key",
            properties.getArchiveSecretKey(), DEFAULT_MINIO_CREDENTIAL);
        rejectDefault(invalid, "antflow.audit.archive-encryption-secret",
            properties.getArchiveEncryptionSecret(), DEFAULT_ENCRYPTION_SECRET);
        if (!invalid.isEmpty()) {
            throw new IllegalStateException(
                "Production audit credentials must be explicitly configured: "
                    + String.join(", ", invalid));
        }
    }

    private static void rejectDefault(List<String> invalid, String name, String value,
                                      String defaultValue) {
        if (value == null || value.isBlank() || defaultValue.equals(value)) {
            invalid.add(name);
        }
    }
}

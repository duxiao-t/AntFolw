package com.antflow.audit;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "antflow.audit")
public class TrustedProxyProperties {
    private List<String> trustedProxies = new ArrayList<>();
    private int onlineRetentionDays = 365;
    private String archiveCron = "0 20 2 * * *";
    private String archiveEndpoint = "http://localhost:9000";
    private String archiveAccessKey = "minioadmin";
    private String archiveSecretKey = "minioadmin";
    private String archiveBucket = "antflow-audit-archives";
    private String archiveRegion;
    private boolean archiveCreateBucket = true;
    private String archiveEncryptionSecret = "antflow-dev-audit-archive-key-change-me";
    private String archiveKeyId = "local-dev-v1";
}

package com.antflow.integration.wecom;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "antflow.wecom")
public class WecomProperties {
    static final String DEFAULT_ENCRYPTION_KEY = "antflow-dev-integration-key-change-me";

    private String baseUrl = "https://qyapi.weixin.qq.com";
    private String oauthBaseUrl = "https://open.weixin.qq.com";
    private String encryptionKey = DEFAULT_ENCRYPTION_KEY;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private int queueCapacity = 20;
}

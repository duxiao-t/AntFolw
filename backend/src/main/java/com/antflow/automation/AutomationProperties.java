package com.antflow.automation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "antflow.automation")
public class AutomationProperties {
    private String zoneId = "Asia/Shanghai";
    private List<String> allowedHosts = new ArrayList<>();
    private boolean httpsOnly = false;
    private boolean allowPrivateAddresses = false;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private Duration staleLockTimeout = Duration.ofMinutes(2);
    private int maxAttempts = 8;
}

package com.antflow.backup;

import java.nio.file.Path;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "antflow.backup")
public class BackupProperties {
    private Path directory = Path.of("/backups");
    private String encryptionSecret;
}

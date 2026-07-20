package com.antflow.mobile.workflow;

import java.nio.file.Path;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "antflow.mobile.files")
public class MobileFileProperties {
    private Path directory = Path.of("./data/mobile-files");
    private long maxBytes = 10L * 1024 * 1024;
    private List<String> allowedTypes = List.of("image/jpeg", "image/png", "application/pdf");
}

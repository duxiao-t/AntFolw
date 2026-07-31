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
    private String storage = "local";
    private Path directory = Path.of("./data/mobile-files");
    private long maxBytes = 10L * 1024 * 1024;
    private List<String> allowedTypes = List.of("image/jpeg", "image/png", "application/pdf");
    private Minio minio = new Minio();

    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "antflow-mobile-files";
        private String region;
        private boolean createBucket = true;
    }
}

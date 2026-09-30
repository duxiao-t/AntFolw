package com.antflow.mobile.workflow;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "antflow.mobile.files")
public class MobileFileProperties {
    private String storage = "minio";
    private long maxBytes = 100L * 1024 * 1024;
    private long maxVideoBytes = 100L * 1024 * 1024;
    private int processingConcurrency = 2;
    private int processingQueueCapacity = 20;
    /**
     * 租约多久算过期。要比"一次转码最长耗时 + MinIO 上下行"明显大：worker 处理期间会续租
     * （{@code renewProcessingClaim}），所以过期只意味着**进程真的没了**（崩溃/被杀）。
     */
    private int processingStaleMinutes = 30;
    /** 每轮 reaper 最多认领几条：要和真实空位（线程数 + 队列容量）对应，领了跑不了只是白领。 */
    private int processingReapLimit = 20;
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

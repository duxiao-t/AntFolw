package com.antflow.integration.storage;

import com.antflow.attachment.MobileFileProperties;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对象存储（MinIO / S3 兼容）配置的只读视图与连通性自检。
 *
 * <p>配置本身来自环境变量（{@code MINIO_*} 等），运行期不可改——本控制器只做两件事：
 * 让管理员看清<b>当前生效值</b>（密钥脱敏），以及用一次真实读写验证存储是否可用。
 * 不在响应里回传任何明文密钥。
 */
@RestController
@RequestMapping("/api/settings/storage")
@RequiredArgsConstructor
public class StorageConfigController {
    private final MobileFileProperties files;
    private final AuthorizationService authorizationService;

    @GetMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.INTEGRATION_STORAGE_MANAGE + "')")
    public StorageConfigView view() {
        authorizationService.requirePermission(PermissionCodes.INTEGRATION_STORAGE_MANAGE);
        MobileFileProperties.Minio minio = files.getMinio();
        return new StorageConfigView(
            files.getStorage(),
            minio.getEndpoint(),
            minio.getBucket(),
            minio.getRegion() == null ? "" : minio.getRegion(),
            minio.isCreateBucket(),
            mask(minio.getAccessKey()),
            minio.getSecretKey() != null && !minio.getSecretKey().isBlank(),
            files.getMaxBytes(),
            files.getMaxVideoBytes());
    }

    /** 真实读写一次探针对象（写入后立即删除）；只读权限或网络不通都会在这里暴露出来。 */
    @PostMapping("/test")
    @PreAuthorize("@authz.console('" + PermissionCodes.INTEGRATION_STORAGE_MANAGE + "')")
    public StorageProbeResult test() {
        authorizationService.requirePermission(PermissionCodes.INTEGRATION_STORAGE_MANAGE);
        if (!"minio".equalsIgnoreCase(files.getStorage())) {
            return new StorageProbeResult(false, false, false, 0,
                "当前文件存储实现为 " + files.getStorage() + "（非对象存储），无需连通性检查");
        }
        MobileFileProperties.Minio minio = files.getMinio();
        long started = System.currentTimeMillis();
        try {
            MinioClient client = client(minio);
            boolean bucketExists = client.bucketExists(
                BucketExistsArgs.builder().bucket(minio.getBucket()).build());
            boolean writable = false;
            String detail;
            if (!bucketExists) {
                detail = minio.isCreateBucket()
                    ? "桶 " + minio.getBucket() + " 尚不存在，首次上传时会自动创建"
                    : "桶 " + minio.getBucket() + " 不存在，且未开启自动创建";
            } else {
                writable = probeWrite(client, minio.getBucket());
                detail = writable ? "读写正常" : "桶可访问但写入被拒绝，请检查账号权限";
            }
            return new StorageProbeResult(bucketExists && writable, bucketExists, writable,
                System.currentTimeMillis() - started, detail);
        } catch (Exception error) {
            return new StorageProbeResult(false, false, false,
                System.currentTimeMillis() - started, "连接失败：" + error.getMessage());
        }
    }

    private static boolean probeWrite(MinioClient client, String bucket) throws Exception {
        String key = "_healthcheck/storage-probe-" + UUID.randomUUID();
        byte[] payload = "antflow-storage-probe".getBytes(StandardCharsets.UTF_8);
        long size = payload.length;
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(payload), size, -1L)
                .contentType("text/plain").build());
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } finally {
            // 探针对象必须清掉：自检不该在业务桶里留下垃圾。
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            } catch (Exception ignored) {
                // 删除失败不影响"可写"的结论
            }
        }
    }

    private static MinioClient client(MobileFileProperties.Minio minio) {
        var builder = MinioClient.builder()
            .endpoint(minio.getEndpoint())
            .credentials(minio.getAccessKey(), minio.getSecretKey());
        if (minio.getRegion() != null && !minio.getRegion().isBlank()) {
            builder.region(minio.getRegion());
        }
        return builder.build();
    }

    /** 只留前 4 位，够确认"配了哪一把钥匙"，不足以还原。 */
    private static String mask(String accessKey) {
        if (accessKey == null || accessKey.isBlank()) return "";
        return accessKey.length() <= 4 ? "****" : accessKey.substring(0, 4) + "****";
    }

    /**
     * 存储配置的只读视图。
     *
     * <p>凭据只以两种"够用但不泄漏"的形式出现：{@code accessKeyMasked} 保留前 4 位
     * （足以确认"配的是哪一把钥匙"），{@code secretConfigured} 只回答"是否配置"。
     * 两者合起来能定位"钥匙漏配/配错"，但不构成凭据泄漏。
     *
     * <p>{@code maxFileBytes}/{@code maxVideoBytes} 一并返回，便于管理员对照业务预期核对上传上限。
     */
    public record StorageConfigView(String storage, String endpoint, String bucket, String region,
                                    boolean createBucket, String accessKeyMasked, boolean secretConfigured,
                                    long maxFileBytes, long maxVideoBytes) { }

    /**
     * 自检结果。
     *
     * <p>{@code ok} 是"桶存在且可写"的合成结论；同时分开暴露 {@code bucketExists} 与 {@code writable}，
     * 是因为"桶没建"（改配置或让后端自动创建）与"桶在但没写权限"（改账号策略）是两种完全不同的运维动作，
     * 只给一个布尔值会让人猜。
     *
     * <p>{@code detail} 面向人类阅读，**不含任何凭据**；{@code elapsedMs} 便于区分"配置错"与"网络慢"。
     */
    public record StorageProbeResult(boolean ok, boolean bucketExists, boolean writable,
                                     long elapsedMs, String detail) { }
}

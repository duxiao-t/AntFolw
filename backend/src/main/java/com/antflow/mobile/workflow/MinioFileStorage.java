package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "antflow.mobile.files", name = "storage",
    havingValue = "minio")
@RequiredArgsConstructor
public class MinioFileStorage implements FileStorage {
    private final MobileFileProperties properties;
    private MinioClient client;

    @PostConstruct
    void initialize() {
        MobileFileProperties.Minio minio = properties.getMinio();
        var builder = MinioClient.builder()
            .endpoint(minio.getEndpoint())
            .credentials(minio.getAccessKey(), minio.getSecretKey());
        if (minio.getRegion() != null && !minio.getRegion().isBlank()) {
            builder.region(minio.getRegion());
        }
        client = builder.build();
        if (minio.isCreateBucket()) {
            ensureBucket();
        }
    }

    @Override
    public StoredObject put(String storageKey, InputStream content, long size,
                            String contentType) throws IOException {
        try {
            client.putObject(PutObjectArgs.builder()
                .bucket(bucket())
                .object(storageKey)
                .stream(content, size, -1L)
                .contentType(contentType)
                .build());
            return new StoredObject(storageKey, size);
        } catch (Exception exception) {
            throw new IOException("could not write object to MinIO", exception);
        }
    }

    @Override
    public Resource get(String storageKey) {
        try {
            return new InputStreamResource(client.getObject(GetObjectArgs.builder()
                .bucket(bucket())
                .object(storageKey)
                .build()));
        } catch (Exception exception) {
            throw new BizException("FILE_STORAGE_FAILED", "could not read object from MinIO");
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            client.statObject(StatObjectArgs.builder()
                .bucket(bucket())
                .object(storageKey)
                .build());
            return true;
        } catch (ErrorResponseException exception) {
            String code = exception.errorResponse() == null ? null : exception.errorResponse().code();
            // `statObject` 是 HEAD 请求，没有响应体，各家返回的错误码并不统一（NoSuchKey / NotFound）；
            // 404 是 HTTP 层面唯一可靠的信号。判错方向的代价不对称：把"缺失"误判成"查不动"会让每次
            // 重复上传都直接失败，把"查不动"误判成"缺失"只是白写一份同样的字节。
            boolean missing = "NoSuchKey".equals(code) || "NoSuchBucket".equals(code)
                || "NotFound".equals(code)
                || (exception.response() != null && exception.response().code() == 404);
            if (missing) {
                return false;
            }
            // 其它错误码（权限、签名…）不是"不存在"，不能顺着往下补写。
            throw new BizException("FILE_STORAGE_FAILED", "could not check object in MinIO");
        } catch (Exception exception) {
            // 网络超时/连接抖动：一律当"查不动"。当成"缺失"会让每次抖动都白重传一份。
            throw new BizException("FILE_STORAGE_FAILED", "could not check object in MinIO");
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket())
                .object(storageKey)
                .build());
        } catch (Exception exception) {
            throw new IOException("could not delete object from MinIO", exception);
        }
    }

    private void ensureBucket() {
        try {
            boolean found = client.bucketExists(BucketExistsArgs.builder()
                .bucket(bucket())
                .build());
            if (!found) {
                var builder = MakeBucketArgs.builder().bucket(bucket());
                String region = properties.getMinio().getRegion();
                if (region != null && !region.isBlank()) {
                    builder.region(region);
                }
                client.makeBucket(builder.build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("could not initialize MinIO bucket", exception);
        }
    }

    private String bucket() {
        return properties.getMinio().getBucket();
    }
}

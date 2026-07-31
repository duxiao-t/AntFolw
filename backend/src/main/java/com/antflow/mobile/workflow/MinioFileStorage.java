package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
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

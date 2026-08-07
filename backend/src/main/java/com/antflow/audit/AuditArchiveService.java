package com.antflow.audit;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.authz.PermissionCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class AuditArchiveService implements HealthIndicator {
    private static final long LOCK_ID = 0x414E54464C4F5741L;
    private static final byte[] HEADER = "ANTFLOW1".getBytes(StandardCharsets.US_ASCII);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final TrustedProxyProperties properties;
    private final AuthorizationService authorizationService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AtomicReference<String> lastFailure = new AtomicReference<>();
    private volatile OffsetDateTime lastSuccess;
    private volatile MinioClient client;
    private volatile boolean bucketReady;

    @Scheduled(cron = "${antflow.audit.archive-cron:0 20 2 * * *}", zone = "UTC")
    public void scheduledArchive() {
        archiveExpiredMonths();
    }

    public void archiveExpiredMonths() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection)) return;
            try {
                OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC)
                    .minusDays(properties.getOnlineRetentionDays());
                OffsetDateTime minimum = jdbcTemplate.query("""
                    SELECT MIN(occurred_at) FROM t_audit_event
                    """, rs -> rs.next() ? rs.getObject(1, OffsetDateTime.class) : null);
                if (minimum == null) return;
                YearMonth month = YearMonth.from(minimum.atZoneSameInstant(ZoneOffset.UTC));
                while (true) {
                    OffsetDateTime rangeStart = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
                    OffsetDateTime rangeEnd = month.plusMonths(1).atDay(1)
                        .atStartOfDay().atOffset(ZoneOffset.UTC);
                    if (rangeEnd.isAfter(cutoff)) break;
                    archiveMonth(month, rangeStart, rangeEnd);
                    month = month.plusMonths(1);
                }
                lastSuccess = OffsetDateTime.now(ZoneOffset.UTC);
                lastFailure.set(null);
            } finally {
                unlock(connection);
            }
        } catch (Exception exception) {
            lastFailure.set(exception.getMessage());
        }
    }

    public List<ArchiveDto> list() {
        authorizationService.requirePermission(PermissionCodes.SECURITY_AUDIT_READ);
        return jdbcTemplate.query("""
            SELECT id, range_start, range_end, event_count, object_key, key_id, sha256,
                   status, error_message, created_at, verified_at
            FROM t_audit_archive ORDER BY range_start DESC
            """, (rs, row) -> new ArchiveDto(rs.getObject("id", UUID.class),
                rs.getObject("range_start", OffsetDateTime.class),
                rs.getObject("range_end", OffsetDateTime.class), rs.getLong("event_count"),
                rs.getString("object_key"), rs.getString("key_id"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("error_message"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("verified_at", OffsetDateTime.class)));
    }

    public ArchiveDownload download(UUID id) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_AUDIT_ARCHIVE_DOWNLOAD);
        ArchiveDto archive = jdbcTemplate.query("""
            SELECT id, range_start, range_end, event_count, object_key, key_id, sha256,
                   status, error_message, created_at, verified_at
            FROM t_audit_archive WHERE id = ? AND status = 'READY'
            """, rs -> rs.next() ? new ArchiveDto(rs.getObject("id", UUID.class),
                rs.getObject("range_start", OffsetDateTime.class),
                rs.getObject("range_end", OffsetDateTime.class), rs.getLong("event_count"),
                rs.getString("object_key"), rs.getString("key_id"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("error_message"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("verified_at", OffsetDateTime.class)) : null, id);
        if (archive == null) throw new HiddenResourceException("audit archive not found");
        byte[] content = readObject(archive.objectKey());
        if (!sha256(content).equals(archive.sha256())) {
            throw new IllegalStateException("audit archive checksum mismatch");
        }
        String fileName = archive.objectKey().substring(archive.objectKey().lastIndexOf('/') + 1);
        return new ArchiveDownload(fileName, archive.objectKey(), content);
    }

    private void archiveMonth(YearMonth month, OffsetDateTime start, OffsetDateTime end) {
        String objectKey = "audit/" + month + ".ndjson.gz.enc";
        Long ready = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_audit_archive WHERE object_key = ? AND status = 'READY'
            """, Long.class, objectKey);
        if (ready != null && ready > 0) return;

        List<Map<String, Object>> events = jdbcTemplate.queryForList("""
            SELECT id, occurred_at, request_id, actor_user_id, actor_username,
                   actor_display_name, session_id, action, resource_type, resource_id,
                   result, risk_level, client_ip, user_agent, failure_code,
                   field_diff::text AS field_diff, metadata::text AS metadata
            FROM t_audit_event
            WHERE occurred_at >= ? AND occurred_at < ?
            ORDER BY occurred_at, id
            """, start, end);
        if (events.isEmpty()) return;

        try {
            byte[] ndjson = ndjson(events);
            byte[] encrypted = encrypt(gzip(ndjson), objectKey);
            String digest = sha256(encrypted);
            putObject(objectKey, encrypted);
            byte[] verified = readObject(objectKey);
            if (!MessageDigest.isEqual(encrypted, verified) || !digest.equals(sha256(verified))) {
                throw new IllegalStateException("uploaded archive verification failed");
            }
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update("""
                    INSERT INTO t_audit_archive(id, range_start, range_end, event_count,
                        object_key, key_id, sha256, status, error_message, verified_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'READY', NULL, now())
                    ON CONFLICT (object_key) DO UPDATE SET
                        range_start = EXCLUDED.range_start,
                        range_end = EXCLUDED.range_end,
                        event_count = EXCLUDED.event_count,
                        key_id = EXCLUDED.key_id,
                        sha256 = EXCLUDED.sha256,
                        status = 'READY', error_message = NULL, verified_at = now()
                    """, start, end, events.size(), objectKey,
                    properties.getArchiveKeyId(), digest);
                jdbcTemplate.execute("SET LOCAL antflow.audit_archive = 'on'");
                jdbcTemplate.update("DELETE FROM t_audit_event WHERE occurred_at >= ? AND occurred_at < ?",
                    start, end);
            });
        } catch (Exception exception) {
            jdbcTemplate.update("""
                INSERT INTO t_audit_archive(id, range_start, range_end, event_count,
                    object_key, key_id, sha256, status, error_message)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'FAILED', ?)
                ON CONFLICT (object_key) DO UPDATE SET status = 'FAILED',
                    error_message = EXCLUDED.error_message
                """, start, end, events.size(), objectKey, properties.getArchiveKeyId(),
                "0".repeat(64), truncate(exception.getMessage(), 2000));
            throw new IllegalStateException("audit archive failed for " + month, exception);
        }
    }

    private byte[] ndjson(List<Map<String, Object>> events) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Map<String, Object> event : events) {
            for (String key : List.of("field_diff", "metadata")) {
                Object value = event.get(key);
                if (value instanceof String json && !json.isBlank()) {
                    event.put(key, objectMapper.readTree(json));
                }
            }
            output.write(objectMapper.writeValueAsBytes(event));
            output.write('\n');
        }
        return output.toByteArray();
    }

    private byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(input);
        }
        return output.toByteArray();
    }

    private byte[] encrypt(byte[] input, String objectKey) throws Exception {
        byte[] nonce = new byte[12];
        secureRandom.nextBytes(nonce);
        byte[] key = MessageDigest.getInstance("SHA-256").digest(
            properties.getArchiveEncryptionSecret().getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(128, nonce));
        cipher.updateAAD(objectKey.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(input);
        return ByteBuffer.allocate(HEADER.length + nonce.length + encrypted.length)
            .put(HEADER).put(nonce).put(encrypted).array();
    }

    private void putObject(String objectKey, byte[] content) throws Exception {
        ensureBucket();
        minio().putObject(PutObjectArgs.builder()
            .bucket(properties.getArchiveBucket()).object(objectKey)
            .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
            .contentType("application/octet-stream").build());
    }

    private byte[] readObject(String objectKey) {
        try {
            ensureBucket();
            try (var input = minio().getObject(GetObjectArgs.builder()
                    .bucket(properties.getArchiveBucket()).object(objectKey).build())) {
                return input.readAllBytes();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("could not read audit archive", exception);
        }
    }

    private synchronized void ensureBucket() throws Exception {
        if (bucketReady) return;
        boolean exists = minio().bucketExists(BucketExistsArgs.builder()
            .bucket(properties.getArchiveBucket()).build());
        if (!exists) {
            if (!properties.isArchiveCreateBucket()) {
                throw new IllegalStateException("audit archive bucket does not exist");
            }
            var builder = MakeBucketArgs.builder().bucket(properties.getArchiveBucket());
            if (properties.getArchiveRegion() != null && !properties.getArchiveRegion().isBlank()) {
                builder.region(properties.getArchiveRegion());
            }
            minio().makeBucket(builder.build());
        }
        bucketReady = true;
    }

    private MinioClient minio() {
        MinioClient result = client;
        if (result == null) {
            synchronized (this) {
                result = client;
                if (result == null) {
                    var builder = MinioClient.builder()
                        .endpoint(properties.getArchiveEndpoint())
                        .credentials(properties.getArchiveAccessKey(), properties.getArchiveSecretKey());
                    if (properties.getArchiveRegion() != null && !properties.getArchiveRegion().isBlank()) {
                        builder.region(properties.getArchiveRegion());
                    }
                    result = builder.build();
                    client = result;
                }
            }
        }
        return result;
    }

    private boolean tryLock(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_ID);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection) {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_ID);
            statement.execute();
        } catch (Exception ignored) {
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "archive failed";
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public Health health() {
        String failure = lastFailure.get();
        if (failure != null) {
            return Health.down().withDetail("lastFailure", failure)
                .withDetail("lastSuccess", lastSuccess == null ? "never" : lastSuccess).build();
        }
        return Health.up().withDetail("lastSuccess", lastSuccess == null ? "never" : lastSuccess).build();
    }

    public record ArchiveDto(UUID id, OffsetDateTime rangeStart, OffsetDateTime rangeEnd,
                             long eventCount, String objectKey, String keyId, String sha256,
                             String status, String errorMessage, OffsetDateTime createdAt,
                             OffsetDateTime verifiedAt) { }
    public record ArchiveDownload(String fileName, String objectKey, byte[] content) { }
}

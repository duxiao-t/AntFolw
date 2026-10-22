package com.antflow.backup;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.antflow.audit.TrustedProxyProperties;
import com.antflow.engine.BizException;
import com.antflow.attachment.MobileFileProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.SecretKeyFactory;
import org.springframework.beans.factory.annotation.Qualifier;import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class BackupService {

    /**
     * 备份文件名的唯一判定口径：全量 {@code antflow-<ts>.afbackup}、仅库 {@code antflow-db-<ts>.afbackup}。
     *
     * <p>这个正则被「列目录」「下载/删除的路径校验」两处共用，**必须只有一份**：
     * 早先两处各写一遍 {@code antflow-[0-9-]+}，加 {@code -db} 前缀时只改了一处（其实一处都没改），
     * 结果是仅库备份能生成却永远列不出来、也无法下载。抽成常量后新格式只需改这里。
     */
    static final Pattern BACKUP_NAME = Pattern.compile("antflow(-db)?-[0-9-]+\\.afbackup");
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final BackupProperties properties;
    private final MobileFileProperties mobileFiles;
    private final TrustedProxyProperties auditProperties;
    private final ObjectMapper json;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String lastError;
    @Value("${spring.datasource.url}") private String jdbcUrl;
    @Value("${spring.datasource.username}") private String username;
    @Value("${spring.datasource.password}") private String password;

    public BackupService(JdbcTemplate jdbc, AuthorizationService authorization,
                         AuditService audit, BackupProperties properties,
                         MobileFileProperties mobileFiles,
                         TrustedProxyProperties auditProperties,
                         ObjectMapper json,
                         @Qualifier("backupExecutor") Executor executor) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.properties = properties;
        this.mobileFiles = mobileFiles;
        this.auditProperties = auditProperties;
        this.json = json;
        this.executor = executor;
    }

    public Settings settings() {
        require();
        return loadSettings();
    }

    @Transactional
    public Settings updateSettings(SettingsWrite request) {
        require();
        if (request == null || request.version() == null || request.localTime() == null
            || request.retentionDays() < 1 || request.retentionDays() > 365) {
            throw new BizException("BACKUP_SETTINGS_INVALID", "备份设置无效");
        }
        int updated = jdbc.update("""
            UPDATE t_system_backup_setting
            SET enabled = ?, local_time = ?, retention_days = ?, version = version + 1,
                updated_by = ?, updated_at = now()
            WHERE id = 1 AND version = ?
            """, request.enabled(), request.localTime(), request.retentionDays(),
            authorization.currentUserId(), request.version());
        if (updated != 1) throw new BizException("BACKUP_SETTINGS_CONFLICT", "备份设置已被修改，请刷新");
        return loadSettings();
    }

    public List<BackupFile> list() {
        require();
        return files();
    }

    /**
     * @param databaseOnly true 时只打包数据库（跳过 MinIO 对象）。
     *                     媒体占归档 99.96%（实测 1.3GB 里 dump 只有 520KB），灾备演练/异地传输
     *                     只需要库时，用它把恢复路径从 GB 级降到百 KB 级；媒体本身应走对象存储的
     *                     复制/版本化，不要每次备份都整桶重传。
     */
    public Status create(boolean databaseOnly) {
        require();
        if (!running.compareAndSet(false, true)) {
            throw new BizException("BACKUP_RUNNING", "已有备份任务正在执行");
        }
        lastError = null;
        CompletableFuture.runAsync(() -> {
            try { createArchive(databaseOnly); }
            catch (RuntimeException error) { lastError = "备份失败，请检查服务日志"; log.error("system backup failed", error); }
            finally { running.set(false); }
        }, executor);
        return new Status(true, null, files().stream().findFirst().orElse(null));
    }

    public Status status() {
        require();
        return new Status(running.get(), lastError, files().stream().findFirst().orElse(null));
    }

    public Download download(String name) {
        require();
        Path file = file(name);
        try {
            return new Download(name, Files.size(file), new InputStreamResource(Files.newInputStream(file)));
        } catch (Exception error) {
            throw new BizException("BACKUP_NOT_FOUND", "备份文件不存在");
        }
    }

    public void delete(String name) {
        require();
        try { Files.delete(file(name)); }
        catch (Exception error) { throw new BizException("BACKUP_DELETE_FAILED", "备份文件删除失败"); }
    }

    @Scheduled(fixedDelay = 60_000)
    void scheduled() {
        Settings setting = loadSettings();
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Shanghai"));
        boolean due = setting.enabled() && now.getHour() == setting.localTime().getHour()
            && now.getMinute() == setting.localTime().getMinute();
        boolean already = files().stream().anyMatch(file -> file.createdAt().atZoneSameInstant(
            ZoneId.of("Asia/Shanghai")).toLocalDate().equals(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
        if (due && !already && running.compareAndSet(false, true)) {
            lastError = null;
            CompletableFuture.runAsync(() -> {
                // 定时任务永远做全量备份：只备份库会给人"有媒体副本"的错觉。
                try { createArchive(false); }
                catch (RuntimeException error) { lastError = "备份失败，请检查服务日志"; log.error("scheduled system backup failed", error); }
                finally { running.set(false); }
            }, executor);
        }
        cleanup(setting.retentionDays());
    }

    private void createArchive(boolean databaseOnly) {
        validateSecret();
        Path target = null;
        try {
            Files.createDirectories(properties.getDirectory());
            String name = "antflow" + (databaseOnly ? "-db" : "") + "-"
                + OffsetDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".afbackup";
            target = properties.getDirectory().resolve(name);
            try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(target,
                    StandardOpenOption.CREATE_NEW));
                 ZipOutputStream zip = new ZipOutputStream(BackupArchiveCipher.encrypt(file,
                    properties.getEncryptionSecret().toCharArray()))) {
                List<ManifestEntry> entries = new ArrayList<>();
                entries.add(database(zip));
                long mobileCount = 0;
                long auditCount = 0;
                if (!databaseOnly) {
                    mobileCount = bucket(zip, "mobile", mobileFiles.getMinio().getEndpoint(),
                        mobileFiles.getMinio().getAccessKey(), mobileFiles.getMinio().getSecretKey(),
                        mobileFiles.getMinio().getBucket(), entries);
                    auditCount = bucket(zip, "audit", auditProperties.getArchiveEndpoint(),
                        auditProperties.getArchiveAccessKey(), auditProperties.getArchiveSecretKey(),
                        auditProperties.getArchiveBucket(), entries);
                }
                entry(zip, "manifest.json", json.writeValueAsBytes(new Manifest(1,
                    OffsetDateTime.now(), mobileCount, auditCount, entries)));
            }
            try {
                Files.setPosixFilePermissions(target, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) { }
            audit.success("system.backup.create", "SYSTEM_BACKUP", name,
                AuditService.RiskLevel.CRITICAL, Map.of(), Map.of("bytes", Files.size(target)));
        } catch (Exception error) {
            if (target != null) try { Files.deleteIfExists(target); } catch (Exception ignored) { }
            throw new IllegalStateException("system backup failed", error);
        }
    }

    private ManifestEntry database(ZipOutputStream zip) throws Exception {
        Jdbc jdbc = parseJdbc();
        ProcessBuilder builder = new ProcessBuilder("pg_dump", "--format=custom", "--no-owner",
            "--no-privileges", "--host", jdbc.host(), "--port", String.valueOf(jdbc.port()),
            "--username", username, "--dbname", jdbc.database());
        builder.environment().put("PGPASSWORD", password);
        Process process = builder.start();
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> {
            try { return process.getErrorStream().readAllBytes(); }
            catch (Exception error) { return new byte[0]; }
        });
        zip.putNextEntry(new ZipEntry("database.dump"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream input = new DigestInputStream(process.getInputStream(), digest)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
        int exit = process.waitFor();
        String error = new String(stderr.join(), StandardCharsets.UTF_8);
        if (exit != 0) throw new IllegalStateException("pg_dump failed: " + error);
        return new ManifestEntry("database.dump", HexFormat.of().formatHex(digest.digest()));
    }

    private long bucket(ZipOutputStream zip, String prefix, String endpoint, String access,
                        String secret, String bucket, List<ManifestEntry> entries) throws Exception {
        MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(access, secret).build();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) return 0;
        long count = 0;
        for (var result : client.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
            String object = result.get().objectName();
            if (object.startsWith("/") || object.contains("../") || object.contains("\\")) {
                throw new IllegalStateException("unsafe object key");
            }
            String path = "minio/" + prefix + "/" + object;
            zip.putNextEntry(new ZipEntry(path));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(object).build());
                 DigestInputStream verified = new DigestInputStream(input, digest)) {
                verified.transferTo(zip);
            }
            zip.closeEntry();
            entries.add(new ManifestEntry(path, HexFormat.of().formatHex(digest.digest())));
            count++;
        }
        return count;
    }

    private void entry(ZipOutputStream zip, String name, byte[] value) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value); zip.closeEntry();
    }

    private List<BackupFile> files() {
        try {
            if (!Files.isDirectory(properties.getDirectory())) return List.of();
            try (var stream = Files.list(properties.getDirectory())) {
                return stream.filter(Files::isRegularFile)
                    .filter(path -> BACKUP_NAME.matcher(path.getFileName().toString()).matches())
                    .map(this::describe).sorted(Comparator.comparing(BackupFile::createdAt).reversed()).toList();
            }
        } catch (Exception error) { return List.of(); }
    }

    private BackupFile describe(Path path) {
        try { return new BackupFile(path.getFileName().toString(), Files.size(path),
            Files.getLastModifiedTime(path).toInstant().atOffset(java.time.ZoneOffset.UTC)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void cleanup(int days) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        files().stream().filter(file -> file.createdAt().isBefore(cutoff)).forEach(file -> {
            try { Files.deleteIfExists(file(file.name())); } catch (Exception ignored) { }
        });
    }

    private Path file(String name) {
        if (name == null || !BACKUP_NAME.matcher(name).matches()) {
            throw new BizException("BACKUP_NOT_FOUND", "备份文件不存在");
        }
        Path path = properties.getDirectory().resolve(name).normalize();
        if (!path.getParent().equals(properties.getDirectory().normalize()) || !Files.isRegularFile(path)) {
            throw new BizException("BACKUP_NOT_FOUND", "备份文件不存在");
        }
        return path;
    }

    private Settings loadSettings() {
        return jdbc.query("SELECT enabled, local_time, retention_days, version FROM t_system_backup_setting WHERE id = 1",
            rs -> rs.next() ? new Settings(rs.getBoolean(1), rs.getObject(2, LocalTime.class),
                rs.getInt(3), rs.getInt(4)) : new Settings(true, LocalTime.of(2, 30), 30, 0));
    }

    private Jdbc parseJdbc() {
        java.net.URI uri = java.net.URI.create(jdbcUrl.substring("jdbc:".length()).split("\\?", 2)[0]);
        return new Jdbc(uri.getHost(), uri.getPort() < 0 ? 5432 : uri.getPort(), uri.getPath().substring(1));
    }

    private void validateSecret() {
        if (properties.getEncryptionSecret() == null || properties.getEncryptionSecret().length() < 32) {
            throw new BizException("BACKUP_KEY_INVALID", "BACKUP_ENCRYPTION_SECRET 至少需要 32 个字符");
        }
    }

    /**
     * 启动期校验，与 JWT/MinIO/归档/集成密钥的 fail-closed 口径对齐（见 code-review B5）。
     *
     * <p>原状：`BACKUP_ENCRYPTION_SECRET` 在 application.yml 里是空默认值，应用能正常启动，
     * 直到第一次点"备份"才在用户面前失败。这里把两类问题提前：
     * 配了但太短 → 直接启动失败（配置错误）；完全没配 → 明确告警"备份不可用"（不阻断启动，
     * 因为备份是通过设置页开启的可选能力）。
     */
    @jakarta.annotation.PostConstruct
    void validateSecretOnStartup() {
        String secret = properties.getEncryptionSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("BACKUP_ENCRYPTION_SECRET 未配置：系统备份不可用（备份接口会拒绝执行）");
            return;
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("BACKUP_ENCRYPTION_SECRET 至少需要 32 个字符");
        }
    }

    private void require() { authorization.requirePermission(PermissionCodes.SYSTEM_BACKUP_MANAGE); }

    public record Settings(boolean enabled, LocalTime localTime, int retentionDays, int version) { }
    public record SettingsWrite(boolean enabled, LocalTime localTime, int retentionDays, Integer version) { }
    public record BackupFile(String name, long bytes, OffsetDateTime createdAt) { }
    public record Status(boolean running, String error, BackupFile latest) { }
    public record Download(String name, long bytes, InputStreamResource resource) { }
    private record Manifest(int version, OffsetDateTime createdAt, long mobileObjects,
                            long auditObjects, List<ManifestEntry> entries) { }
    private record ManifestEntry(String path, String sha256) { }
    private record Jdbc(String host, int port, String database) { }
}

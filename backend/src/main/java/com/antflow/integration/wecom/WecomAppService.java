package com.antflow.integration.wecom;

import com.antflow.auth.AuthService;
import com.antflow.auth.AuthSessionService;
import com.antflow.auth.ExternalAuthProperties;
import com.antflow.auth.OidcService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.mobile.workflow.MobileFileDto;
import com.antflow.mobile.workflow.MobileFileService;
import com.antflow.notify.NotificationEvent;
import com.antflow.notify.NotificationListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class WecomAppService implements NotificationListener {
    private static final Set<String> MESSAGE_EVENTS = Set.of(
        "TASK_ASSIGNED", "TASK_RETURNED", "TASK_CANCELLED", "APPROVAL_INVALIDATED",
        "CC_ASSIGNED", "TASK_TIMEOUT_REMINDER", "INSTANCE_APPROVED", "INSTANCE_REJECTED");
    private final JdbcTemplate jdbc;
    private final WecomClient client;
    private final WecomSecretCipher cipher;
    private final WecomProperties wecom;
    private final ExternalAuthProperties authProperties;
    private final AuthService authService;
    private final AuthSessionService sessions;
    private final AuthorizationService authorization;
    private final MobileFileService files;
    private final SecureRandom random = new SecureRandom();
    private final String workerId = UUID.randomUUID().toString();
    private final Map<Long, CachedSession> appSessions = new ConcurrentHashMap<>();
    private final Map<Long, CachedTicket> tickets = new ConcurrentHashMap<>();

    public WecomAppService(JdbcTemplate jdbc, WecomClient client, WecomSecretCipher cipher,
                           WecomProperties wecom, ExternalAuthProperties authProperties,
                           AuthService authService, AuthSessionService sessions,
                           AuthorizationService authorization, MobileFileService files) {
        this.jdbc = jdbc;
        this.client = client;
        this.cipher = cipher;
        this.wecom = wecom;
        this.authProperties = authProperties;
        this.authService = authService;
        this.sessions = sessions;
        this.authorization = authorization;
        this.files = files;
    }

    public Status status() {
        Boolean enabled = jdbc.query("SELECT true FROM t_wecom_config WHERE oauth_enabled LIMIT 1",
            rs -> rs.next() ? Boolean.TRUE : Boolean.FALSE);
        return new Status(Boolean.TRUE.equals(enabled));
    }

    public URI authorize(String returnUrl) {
        AppConfig config = requireEnabled("oauth_enabled");
        String state = randomToken(32);
        jdbc.update("""
            INSERT INTO t_external_auth_flow(state_hash, provider_type, provider_id, return_path, expires_at)
            VALUES (?, 'WECOM', ?, ?, now() + interval '5 minutes')
            """, AuthSessionService.hash(state), config.companyId(),
            OidcService.safeReturnPath(returnUrl, "/mobile/"));
        String callback = authProperties.publicBaseUri().resolve("/api/public/auth/wecom/callback").toString();
        return URI.create(wecom.getOauthBaseUrl() + "/connect/oauth2/authorize?appid="
            + encode(config.corpId()) + "&redirect_uri=" + encode(callback)
            + "&response_type=code&scope=snsapi_base&state=" + encode(state) + "#wechat_redirect");
    }

    public URI callback(String state, String code, HttpServletRequest request,
                        HttpServletResponse response) {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            throw new BadCredentialsException("invalid WeCom callback");
        }
        Flow flow = jdbc.query("""
            UPDATE t_external_auth_flow SET consumed_at = now()
            WHERE state_hash = ? AND provider_type = 'WECOM' AND consumed_at IS NULL
              AND expires_at > now()
            RETURNING provider_id, return_path
            """, rs -> rs.next() ? new Flow(rs.getLong(1), rs.getString(2)) : null,
            AuthSessionService.hash(state));
        if (flow == null) throw new BadCredentialsException("WeCom state is invalid or expired");
        AppConfig config = requireConfig(flow.companyId(), "oauth_enabled");
        String wecomUserId = client.oauthUserId(session(config), code);
        Long userId = jdbc.query("""
            SELECT mapping.user_id FROM t_wecom_user_mapping mapping
            JOIN t_user user_row ON user_row.id = mapping.user_id AND user_row.status = 'ACTIVE'
            WHERE mapping.company_id = ? AND mapping.wecom_user_id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, config.companyId(), wecomUserId);
        if (userId == null) throw new BadCredentialsException("企业微信成员尚未同步或本地账号已停用");
        AuthService.Authenticated authenticated = authService.resume(userId)
            .orElseThrow(() -> new BadCredentialsException("local user is disabled"));
        sessions.create(authenticated, request, response);
        return authProperties.publicBaseUri().resolve(flow.returnPath());
    }

    public JsSdkConfig jsSdkConfig(String requestedUrl) {
        AppConfig config = requireEnabled("js_sdk_enabled");
        String url = safeJsUrl(requestedUrl);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = randomToken(16);
        String ticket = ticket(config);
        String source = "jsapi_ticket=" + ticket + "&noncestr=" + nonce
            + "&timestamp=" + timestamp + "&url=" + url;
        return new JsSdkConfig(config.corpId(), timestamp, nonce, sha1(source));
    }

    public MobileFileDto importMedia(String mediaId, String mediaType) {
        authorization.requirePermission(PermissionCodes.FILE_UPLOAD);
        if (mediaId == null || mediaId.isBlank() || mediaId.length() > 512) {
            throw new BizException("WECOM_MEDIA_INVALID", "企业微信临时素材 ID 无效");
        }
        AppConfig config = requireEnabled("js_sdk_enabled");
        String fallback = switch (mediaType == null ? "" : mediaType) {
            case "image" -> MediaType.IMAGE_JPEG_VALUE;
            case "voice", "audio" -> "audio/amr";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
        WecomClient.Media media = client.temporaryMedia(session(config), mediaId, fallback);
        long owner = PrincipalHolder.current().orElseThrow().userId();
        return files.upload(new ByteArrayMultipartFile(media.fileName(), media.contentType(), media.content()), owner);
    }

    public void sendTestMessage(long companyId) {
        authorization.requirePermission(PermissionCodes.ORG_COMPANY_MANAGE);
        long userId = authorization.currentUserId();
        Recipient recipient = recipient(userId, companyId);
        if (recipient == null) throw new BizException("WECOM_USER_NOT_MAPPED", "当前管理员尚未同步到该企业微信通讯录");
        client.sendTextCard(session(recipient.config()), recipient.config().agentId(), recipient.wecomUserId(),
            "AntFlow 企业微信连接测试", "应用消息发送成功，审批通知将通过此入口送达。",
            authProperties.publicBaseUri().resolve("/mobile/").toString());
    }

    public DeliveryStatus deliveryStatus(long companyId) {
        authorization.requirePermission(PermissionCodes.ORG_COMPANY_MANAGE);
        return jdbc.query("""
            SELECT count(*) FILTER (WHERE delivery.status IN ('PENDING', 'RUNNING')),
                   count(*) FILTER (WHERE delivery.status = 'DEAD'),
                   min(delivery.created_at) FILTER (WHERE delivery.status IN ('PENDING', 'RUNNING'))
            FROM t_wecom_message_delivery delivery
            JOIN t_wecom_user_mapping mapping ON mapping.user_id = delivery.recipient_id
            WHERE mapping.company_id = ?
            """, rs -> rs.next() ? new DeliveryStatus(rs.getLong(1), rs.getLong(2),
                rs.getObject(3, OffsetDateTime.class)) : new DeliveryStatus(0, 0, null), companyId);
    }

    public void retryDead(long companyId) {
        authorization.requirePermission(PermissionCodes.ORG_COMPANY_MANAGE);
        jdbc.update("""
            UPDATE t_wecom_message_delivery delivery SET status = 'PENDING', attempts = 0,
                next_attempt_at = now(), last_error = NULL
            FROM t_wecom_user_mapping mapping
            WHERE mapping.user_id = delivery.recipient_id AND mapping.company_id = ?
              AND delivery.status = 'DEAD'
            """, companyId);
    }

    @Override
    public boolean accepts(NotificationEvent event) {
        return event.getUserId() != null && MESSAGE_EVENTS.contains(event.getType());
    }

    @Override
    public void onEvent(NotificationEvent event) {
        String key = String.join(":", event.getType(), String.valueOf(event.getProcInstId()),
            String.valueOf(event.getTaskId()), String.valueOf(event.getUserId()));
        jdbc.update("""
            INSERT INTO t_wecom_message_delivery(
                dedupe_key, event_type, proc_inst_id, task_id, recipient_id, title)
            SELECT ?, ?, ?, ?, ?, ?
            WHERE EXISTS (
                SELECT 1 FROM t_wecom_user_mapping mapping
                JOIN t_wecom_config config ON config.company_id = mapping.company_id
                WHERE mapping.user_id = ? AND config.message_enabled)
            ON CONFLICT (dedupe_key) DO NOTHING
            """, key, event.getType(), event.getProcInstId(), event.getTaskId(),
            event.getUserId(), title(event.getType()), event.getUserId());
    }

    @Scheduled(fixedDelayString = "${antflow.wecom.message-poll-interval-ms:1000}")
    public void pollMessages() {
        try {
            for (int i = 0; i < 25; i++) {
                Delivery delivery = claim();
                if (delivery == null) return;
                deliver(delivery);
            }
        } catch (RuntimeException ignored) {
            // The next scheduled poll retries persisted work.
        }
    }

    Delivery claim() {
        return jdbc.query("""
            WITH candidate AS (
                SELECT id FROM t_wecom_message_delivery
                WHERE (status = 'PENDING' AND next_attempt_at <= now())
                   OR (status = 'RUNNING' AND locked_at < now() - interval '2 minutes')
                ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
            UPDATE t_wecom_message_delivery delivery
            SET status = 'RUNNING', attempts = attempts + 1, locked_at = now(), locked_by = ?
            FROM candidate WHERE delivery.id = candidate.id
            RETURNING delivery.id, delivery.event_type, delivery.proc_inst_id,
                      delivery.task_id, delivery.recipient_id, delivery.title, delivery.attempts
            """, rs -> rs.next() ? new Delivery(rs.getLong(1), rs.getString(2),
                nullableLong(rs.getObject(3)), nullableLong(rs.getObject(4)), rs.getLong(5),
                rs.getString(6), rs.getInt(7)) : null, workerId);
    }

    void deliver(Delivery delivery) {
        try {
            Recipient recipient = recipient(delivery.recipientId(), null);
            if (recipient == null) throw new BizException("WECOM_DELIVERY_UNAVAILABLE", "企业微信应用消息未启用或成员未映射");
            String url = authProperties.publicBaseUri().resolve(deepLink(delivery)).toString();
            client.sendTextCard(session(recipient.config()), recipient.config().agentId(),
                recipient.wecomUserId(), delivery.title(), delivery.title(), url);
            jdbc.update("""
                UPDATE t_wecom_message_delivery SET status = 'DELIVERED', delivered_at = now(),
                    locked_at = NULL, locked_by = NULL, last_error = NULL
                WHERE id = ? AND status = 'RUNNING' AND locked_by = ?
                """, delivery.id(), workerId);
        } catch (RuntimeException error) {
            jdbc.update("""
                UPDATE t_wecom_message_delivery
                SET status = CASE WHEN attempts >= 10 THEN 'DEAD' ELSE 'PENDING' END,
                    next_attempt_at = now() + make_interval(secs => LEAST(3600, power(2, attempts)::int)),
                    locked_at = NULL, locked_by = NULL, last_error = left(?, 2000)
                WHERE id = ? AND status = 'RUNNING' AND locked_by = ?
                """, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                delivery.id(), workerId);
        }
    }

    private String deepLink(Delivery delivery) {
        if (delivery.taskId() != null && ("TASK_ASSIGNED".equals(delivery.eventType())
            || "TASK_RETURNED".equals(delivery.eventType())
            || "TASK_TIMEOUT_REMINDER".equals(delivery.eventType()))) {
            TaskLink task = jdbc.query("""
                SELECT task.task_type, form.code FROM t_task task
                JOIN t_process_instance instance ON instance.id = task.proc_inst_id
                JOIN t_process_definition process ON process.id = instance.proc_def_id
                JOIN t_form_definition form ON form.id = process.form_def_id
                WHERE task.id = ?
                """, rs -> rs.next() ? new TaskLink(rs.getString(1), rs.getString(2)) : null,
                delivery.taskId());
            if (task != null && "REWORK".equals(task.type())) {
                return "/mobile/forms/" + encode(task.formCode()) + "?reworkTaskId=" + delivery.taskId();
            }
            return "/mobile/tasks/" + delivery.taskId();
        }
        return "/mobile/processes/" + delivery.instanceId();
    }

    private Recipient recipient(long userId, Long companyId) {
        return jdbc.query("""
            SELECT mapping.wecom_user_id, config.company_id, config.corp_id, config.agent_id,
                   config.agent_secret_encrypted, config.oauth_enabled, config.js_sdk_enabled,
                   config.message_enabled
            FROM t_wecom_user_mapping mapping
            JOIN t_wecom_config config ON config.company_id = mapping.company_id
            WHERE mapping.user_id = ? AND config.message_enabled
              AND (?::bigint IS NULL OR config.company_id = ?)
            ORDER BY config.company_id LIMIT 1
            """, rs -> rs.next() ? new Recipient(rs.getString(1), appConfig(rs)) : null,
            userId, companyId, companyId);
    }

    private String ticket(AppConfig config) {
        CachedTicket cached = tickets.get(config.companyId());
        if (cached != null && cached.secretFingerprint().equals(config.encryptedAgentSecret())
            && cached.expiresAt().isAfter(Instant.now())) return cached.ticket();
        String value = client.jsapiTicket(session(config));
        tickets.put(config.companyId(), new CachedTicket(config.encryptedAgentSecret(), value,
            Instant.now().plusSeconds(7000)));
        return value;
    }

    private WecomClient.Session session(AppConfig config) {
        CachedSession cached = appSessions.get(config.companyId());
        if (cached != null && cached.secretFingerprint().equals(config.encryptedAgentSecret())) {
            return cached.session();
        }
        WecomClient.Session created = client.connect(config.corpId(),
            cipher.decrypt(config.encryptedAgentSecret(), "wecom-agent:" + config.companyId()));
        appSessions.put(config.companyId(), new CachedSession(config.encryptedAgentSecret(), created));
        tickets.remove(config.companyId());
        return created;
    }

    private AppConfig requireEnabled(String column) {
        AppConfig config = jdbc.query("SELECT company_id, corp_id, agent_id, agent_secret_encrypted, "
            + "oauth_enabled, js_sdk_enabled, message_enabled FROM t_wecom_config WHERE "
            + column + " LIMIT 1", rs -> rs.next() ? appConfig(rs) : null);
        if (config == null) throw new BizException("WECOM_APP_DISABLED", "企业微信应用能力尚未启用");
        return config;
    }

    private AppConfig requireConfig(long companyId, String enabledColumn) {
        AppConfig config = jdbc.query("SELECT company_id, corp_id, agent_id, agent_secret_encrypted, "
            + "oauth_enabled, js_sdk_enabled, message_enabled FROM t_wecom_config WHERE company_id = ? AND "
            + enabledColumn, rs -> rs.next() ? appConfig(rs) : null, companyId);
        if (config == null) throw new BizException("WECOM_APP_DISABLED", "企业微信应用能力尚未启用");
        return config;
    }

    private static AppConfig appConfig(java.sql.ResultSet rs) throws java.sql.SQLException {
        Integer agentId = (Integer) rs.getObject("agent_id");
        String secret = rs.getString("agent_secret_encrypted");
        if (agentId == null || secret == null) throw new BizException("WECOM_APP_REQUIRED", "企业微信应用配置不完整");
        return new AppConfig(rs.getLong("company_id"), rs.getString("corp_id"), agentId,
            secret, rs.getBoolean("oauth_enabled"), rs.getBoolean("js_sdk_enabled"),
            rs.getBoolean("message_enabled"));
    }

    private String safeJsUrl(String input) {
        URI requested;
        try { requested = URI.create(input); } catch (RuntimeException error) {
            throw new BizException("WECOM_JS_URL_INVALID", "JS-SDK 页面地址无效");
        }
        URI base = authProperties.publicBaseUri();
        int requestPort = requested.getPort() < 0 ? defaultPort(requested.getScheme()) : requested.getPort();
        int basePort = base.getPort() < 0 ? defaultPort(base.getScheme()) : base.getPort();
        if (!base.getScheme().equalsIgnoreCase(requested.getScheme())
            || !base.getHost().equalsIgnoreCase(requested.getHost()) || basePort != requestPort
            || requested.getUserInfo() != null) {
            throw new BizException("WECOM_JS_URL_INVALID", "JS-SDK 页面必须与系统公网地址同源");
        }
        try {
            return new URI(requested.getScheme(), null, requested.getHost(), requested.getPort(),
                requested.getPath(), requested.getQuery(), null).toString();
        } catch (java.net.URISyntaxException error) {
            throw new BizException("WECOM_JS_URL_INVALID", "JS-SDK 页面地址无效");
        }
    }

    private static int defaultPort(String scheme) { return "https".equalsIgnoreCase(scheme) ? 443 : 80; }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String sha1(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static String title(String type) {
        return switch (type) {
            case "TASK_ASSIGNED" -> "您有新的审批任务";
            case "TASK_RETURNED" -> "申请已退回修改";
            case "TASK_CANCELLED" -> "审批任务已作废";
            case "APPROVAL_INVALIDATED" -> "您的审批已作废";
            case "CC_ASSIGNED" -> "您收到一条抄送";
            case "TASK_TIMEOUT_REMINDER" -> "审批任务即将超时";
            case "INSTANCE_APPROVED" -> "流程已审批通过";
            case "INSTANCE_REJECTED" -> "流程已被驳回";
            default -> "流程状态已更新";
        };
    }

    private record AppConfig(long companyId, String corpId, int agentId,
                             String encryptedAgentSecret, boolean oauthEnabled,
                             boolean jsSdkEnabled, boolean messageEnabled) { }
    private record CachedSession(String secretFingerprint, WecomClient.Session session) { }
    private record CachedTicket(String secretFingerprint, String ticket, Instant expiresAt) { }
    private record Flow(long companyId, String returnPath) { }
    private record Recipient(String wecomUserId, AppConfig config) { }
    private record Delivery(long id, String eventType, Long instanceId, Long taskId,
                            long recipientId, String title, int attempts) { }
    private record TaskLink(String type, String formCode) { }
    public record Status(boolean oauthEnabled) { }
    public record JsSdkConfig(String appId, long timestamp, String nonceStr, String signature) { }
    public record DeliveryStatus(long pending, long dead, OffsetDateTime oldestPendingAt) { }

    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final String name;
        private final String contentType;
        private final byte[] content;
        private ByteArrayMultipartFile(String name, String contentType, byte[] content) {
            this.name = name; this.contentType = contentType; this.content = content;
        }
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(java.io.File destination) throws IOException {
            java.nio.file.Files.write(destination.toPath(), content);
        }
    }
}

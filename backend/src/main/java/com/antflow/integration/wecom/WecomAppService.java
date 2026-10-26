package com.antflow.integration.wecom;

import com.antflow.attachment.MobileFileDto;
import com.antflow.attachment.MobileFileService;
import com.antflow.auth.ExternalAuthProperties;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 企微应用侧对外门面：应用状态、接入信息、JS-SDK 签名、素材导入、投递运维。
 * 免登在 {@link WecomAppLoginService}，消息入队与投递在 {@link WecomAppMessageDispatcher}，
 * access_token / jsapi_ticket 缓存在 {@link WecomAppTokenCache}，配置读取在 {@link WecomAppConfigStore}。
 */
@Service
public class WecomAppService {
    private final JdbcTemplate jdbc;
    private final WecomClient client;
    private final ExternalAuthProperties authProperties;
    private final AuthorizationService authorization;
    private final MobileFileService files;
    private final WecomAppConfigStore configs;
    private final WecomAppTokenCache tokens;
    private final WecomAppMessageDispatcher dispatcher;
    private final WecomAppLoginService login;

    public WecomAppService(JdbcTemplate jdbc, WecomClient client,
                           ExternalAuthProperties authProperties,
                           AuthorizationService authorization, MobileFileService files,
                           WecomAppConfigStore configs, WecomAppTokenCache tokens,
                           WecomAppMessageDispatcher dispatcher, WecomAppLoginService login) {
        this.jdbc = jdbc;
        this.client = client;
        this.authProperties = authProperties;
        this.authorization = authorization;
        this.files = files;
        this.configs = configs;
        this.tokens = tokens;
        this.dispatcher = dispatcher;
        this.login = login;
    }

    public Status status() {
        Boolean enabled = jdbc.query("SELECT true FROM t_wecom_config WHERE oauth_enabled LIMIT 1",
            rs -> rs.next() ? Boolean.TRUE : Boolean.FALSE);
        return new Status(Boolean.TRUE.equals(enabled));
    }

    public AccessInfo accessInfo() {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        return accessInfo(authProperties.publicBaseUri());
    }

    static AccessInfo accessInfo(URI base) {
        return new AccessInfo(base.toString(), base.getHost(),
            base.resolve("/mobile/").toString(),
            base.resolve("/api/public/auth/wecom/callback").toString(),
            base.resolve("/WW_verify_F22tLVc7f8HR2P9B.txt").toString(),
            "https".equalsIgnoreCase(base.getScheme()));
    }

    /** 免登入口保留在本门面上（Controller 依赖它），实现见 {@link WecomAppLoginService}。 */
    public URI authorize(String returnUrl) {
        return login.authorize(returnUrl);
    }

    /** 免登回调同 {@link #authorize(String)}：对外签名不变，实现已下沉。 */
    public URI callback(String state, String code, HttpServletRequest request,
                        HttpServletResponse response) {
        return login.callback(state, code, request, response);
    }

    public JsSdkConfig jsSdkConfig(String requestedUrl) {
        WecomAppConfig config = configs.requireEnabled("js_sdk_enabled");
        String url = safeJsUrl(requestedUrl);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = WecomAppSupport.randomToken(16);
        String ticket = tokens.ticket(config);
        String source = "jsapi_ticket=" + ticket + "&noncestr=" + nonce
            + "&timestamp=" + timestamp + "&url=" + url;
        return new JsSdkConfig(config.corpId(), timestamp, nonce, WecomAppSupport.sha1(source));
    }

    public MobileFileDto importMedia(String mediaId, String mediaType) {
        authorization.requirePermission(PermissionCodes.FILE_ATTACHMENT_UPLOAD);
        if (mediaId == null || mediaId.isBlank() || mediaId.length() > 512) {
            throw new BizException("WECOM_MEDIA_INVALID", "企业微信临时素材 ID 无效");
        }
        WecomAppConfig config = configs.requireEnabled("js_sdk_enabled");
        String fallback = switch (mediaType == null ? "" : mediaType) {
            case "image" -> MediaType.IMAGE_JPEG_VALUE;
            case "voice", "audio" -> "audio/amr";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
        WecomClient.Media media = client.temporaryMedia(tokens.session(config), mediaId, fallback);
        long owner = PrincipalHolder.current().orElseThrow().userId();
        return files.upload(new ByteArrayMultipartFile(media.fileName(), media.contentType(), media.content()), owner);
    }

    public void sendTestMessage() {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        long userId = authorization.currentUserId();
        WecomAppMessageDispatcher.Recipient recipient = dispatcher.recipient(userId);
        if (recipient == null) throw new BizException("WECOM_USER_NOT_MAPPED", "当前管理员尚未同步到该企业微信通讯录");
        client.sendTextCard(tokens.session(recipient.config()), recipient.config().agentId(), recipient.wecomUserId(),
            "AntFlow 企业微信连接测试", "应用消息发送成功，审批通知将通过此入口送达。",
            authProperties.publicBaseUri().resolve("/mobile/").toString());
    }

    public DeliveryStatus deliveryStatus() {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        return jdbc.query("""
            SELECT count(*) FILTER (WHERE delivery.status IN ('PENDING', 'RUNNING')),
                   count(*) FILTER (WHERE delivery.status = 'DEAD'),
                   min(delivery.created_at) FILTER (WHERE delivery.status IN ('PENDING', 'RUNNING'))
            FROM t_wecom_message_delivery delivery
            JOIN t_wecom_user_mapping mapping ON mapping.user_id = delivery.recipient_id
            """, rs -> rs.next() ? new DeliveryStatus(rs.getLong(1), rs.getLong(2),
                rs.getObject(3, OffsetDateTime.class)) : new DeliveryStatus(0, 0, null));
    }

    public void retryDead() {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        jdbc.update("""
            UPDATE t_wecom_message_delivery delivery SET status = 'PENDING', attempts = 0,
                next_attempt_at = now(), last_error = NULL
            FROM t_wecom_user_mapping mapping
            WHERE mapping.user_id = delivery.recipient_id
              AND delivery.status = 'DEAD'
            """);
    }

    /**
     * JS-SDK 的 url 必须与系统公网地址**同源**（scheme/host/port），否则企微会拒绝签名；
     * 这里同时丢弃 userInfo/fragment，避免把凭据带进签名串。
     */
    private String safeJsUrl(String input) {
        URI requested;
        try { requested = URI.create(input); } catch (RuntimeException error) {
            throw new BizException("WECOM_JS_URL_INVALID", "JS-SDK 页面地址无效");
        }
        URI base = authProperties.publicBaseUri();
        int requestPort = requested.getPort() < 0 ? WecomAppSupport.defaultPort(requested.getScheme()) : requested.getPort();
        int basePort = base.getPort() < 0 ? WecomAppSupport.defaultPort(base.getScheme()) : base.getPort();
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

    public record Status(boolean oauthEnabled) { }
    public record AccessInfo(String publicBaseUrl, String trustedDomain,
                             String mobileHomeUrl, String oauthCallbackUrl,
                             String verificationFileUrl, boolean secure) { }
    public record JsSdkConfig(String appId, long timestamp, String nonceStr, String signature) { }
    public record DeliveryStatus(long pending, long dead, OffsetDateTime oldestPendingAt) { }
}

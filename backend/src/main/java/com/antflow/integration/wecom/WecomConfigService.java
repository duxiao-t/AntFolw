package com.antflow.integration.wecom;

import com.antflow.audit.AuditService;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.integration.wecom.WecomService.SettingsDto;
import com.antflow.integration.wecom.WecomService.JobDto;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 企微应用的连接配置与同步计划：CorpID/Secret/AgentID、免登与消息开关、每日同步时间。
 *
 * <p>为什么独立：配置是"读一次、改一次"的 CRUD，与同步执行没有任何共享状态；
 * 它的失败语义是"回滚 + 提示"，与同步任务的"租约 + 重试"完全不同。混在
 * {@code WecomService} 里时，改一个配置字段要在 1300 行里定位。
 *
 * <p>两个约定：
 * <ol>
 *   <li>**Secret 只进不出**：接口返回 `secretConfigured` 布尔，从不回显明文；
 *       更新时留空表示"保持原值"。</li>
 *   <li>**权限自守**：本类每个写入口自己校验 {@code INTEGRATION_WECOM_MANAGE} 与公司归属，
 *       不依赖调用方——它也会被别的编排（如定时任务）间接调用。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class WecomConfigService {

    private static final LocalTime DEFAULT_SCHEDULE_TIME = LocalTime.of(3, 0);
    private static final DateTimeFormatter SCHEDULE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final WecomSecretCipher cipher;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final WecomJobStore jobStore;
    private final WecomAccessGuard accessGuard;

    public SettingsDto settings(long companyId) {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        accessGuard.requireCompanyAccess(companyId);
        Config config = config(companyId);
        return new SettingsDto(companyId, config == null ? "" : config.corpId(),
            config != null && !config.encryptedSecret().isBlank(), jobStore.latestJob(companyId),
            config == null ? null : config.agentId(),
            config != null && config.encryptedAgentSecret() != null,
            config != null && config.oauthEnabled(), config != null && config.jsSdkEnabled(),
            config != null && config.messageEnabled(),
            config != null && config.scheduleEnabled(),
            formatScheduleTime(config == null ? DEFAULT_SCHEDULE_TIME : config.scheduleTime()),
            config == null ? "INCREMENTAL" : config.scheduleMode());
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret) {
        Config current = config(companyId);
        return saveSettings(companyId, corpId, secret,
            current == null ? null : current.agentId(), null,
            current != null && current.oauthEnabled(), current != null && current.jsSdkEnabled(),
            current != null && current.messageEnabled(), null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret, Integer agentId,
                                    String agentSecret, boolean oauthEnabled,
                                    boolean jsSdkEnabled, boolean messageEnabled) {
        return saveSettings(companyId, corpId, secret, agentId, agentSecret, oauthEnabled,
            jsSdkEnabled, messageEnabled, null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(long companyId, String corpId, String secret, Integer agentId,
                                    String agentSecret, Boolean oauthEnabled,
                                    Boolean jsSdkEnabled, Boolean messageEnabled,
                                    Boolean scheduleEnabled, String scheduleTime,
                                    String scheduleMode) {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        accessGuard.requireCompanyAccess(companyId);
        String normalizedCorpId = corpId == null ? "" : corpId.trim();
        if (normalizedCorpId.isBlank() || normalizedCorpId.length() > 128) {
            throw new BizException("WECOM_CORP_ID_REQUIRED", "请输入有效的 CorpID");
        }
        Config current = config(companyId);
        String encrypted = secret == null || secret.isBlank()
            ? current == null ? null : current.encryptedSecret()
            : cipher.encrypt(secret.trim(), companyId);
        if (encrypted == null) {
            throw new BizException("WECOM_SECRET_REQUIRED", "首次配置时请输入通讯录同步 Secret");
        }
        String encryptedAgentSecret = agentSecret == null || agentSecret.isBlank()
            ? current == null ? null : current.encryptedAgentSecret()
            : cipher.encrypt(agentSecret.trim(), "wecom-agent:" + companyId);
        Integer savedAgentId = agentId == null && current != null ? current.agentId() : agentId;
        boolean savedOauthEnabled = oauthEnabled == null
            ? current != null && current.oauthEnabled() : oauthEnabled;
        boolean savedJsSdkEnabled = jsSdkEnabled == null
            ? current != null && current.jsSdkEnabled() : jsSdkEnabled;
        boolean savedMessageEnabled = messageEnabled == null
            ? current != null && current.messageEnabled() : messageEnabled;
        boolean appEnabled = savedOauthEnabled || savedJsSdkEnabled || savedMessageEnabled;
        if (appEnabled && (savedAgentId == null || savedAgentId <= 0 || encryptedAgentSecret == null)) {
            throw new BizException("WECOM_APP_REQUIRED", "启用免登、JS-SDK 或应用消息前请配置 AgentId 和应用 Secret");
        }
        if (savedOauthEnabled) {
            Integer otherOauth = jdbc.queryForObject("""
                SELECT count(*) FROM t_wecom_config WHERE oauth_enabled AND company_id <> ?
                """, Integer.class, companyId);
            if (otherOauth != null && otherOauth > 0) {
                throw new BizException("WECOM_OAUTH_CONFLICT", "当前只允许启用一个企业微信 Corp 免登入口");
            }
        }
        boolean savedScheduleEnabled = scheduleEnabled == null
            ? current != null && current.scheduleEnabled() : scheduleEnabled;
        LocalTime savedScheduleTime = scheduleTime == null
            ? current == null ? DEFAULT_SCHEDULE_TIME : current.scheduleTime()
            : parseScheduleTime(scheduleTime);
        String savedScheduleMode = scheduleMode == null
            ? current == null ? "INCREMENTAL" : current.scheduleMode()
            : normalizeScheduleMode(scheduleMode);
        long actorId = authorization.currentUserId();
        jdbc.update("""
            INSERT INTO t_wecom_config(company_id, corp_id, secret_encrypted, agent_id,
                agent_secret_encrypted, oauth_enabled, js_sdk_enabled, message_enabled,
                schedule_enabled, schedule_time, schedule_mode, created_by, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (company_id) DO UPDATE SET
                corp_id = EXCLUDED.corp_id,
                secret_encrypted = EXCLUDED.secret_encrypted,
                agent_id = EXCLUDED.agent_id,
                agent_secret_encrypted = EXCLUDED.agent_secret_encrypted,
                oauth_enabled = EXCLUDED.oauth_enabled,
                js_sdk_enabled = EXCLUDED.js_sdk_enabled,
                message_enabled = EXCLUDED.message_enabled,
                schedule_enabled = EXCLUDED.schedule_enabled,
                schedule_time = EXCLUDED.schedule_time,
                schedule_mode = EXCLUDED.schedule_mode,
                updated_by = EXCLUDED.updated_by,
                updated_at = now()
            """, companyId, normalizedCorpId, encrypted, savedAgentId, encryptedAgentSecret,
            savedOauthEnabled, savedJsSdkEnabled, savedMessageEnabled, savedScheduleEnabled,
            savedScheduleTime, savedScheduleMode, actorId, actorId);
        audit.success("integration.wecom.settings.update", "COMPANY", companyId,
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("corpId", "secretConfigured", "scheduleEnabled",
                "scheduleTime", "scheduleMode")), Map.of());
        return new SettingsDto(companyId, normalizedCorpId, true, jobStore.latestJob(companyId), savedAgentId,
            encryptedAgentSecret != null, savedOauthEnabled, savedJsSdkEnabled, savedMessageEnabled,
            savedScheduleEnabled, formatScheduleTime(savedScheduleTime), savedScheduleMode);
    }

    Config config(long companyId) {
        return jdbc.query("""
            SELECT corp_id, secret_encrypted, agent_id, agent_secret_encrypted,
                   oauth_enabled, js_sdk_enabled, message_enabled,
                   schedule_enabled, schedule_time, schedule_mode
            FROM t_wecom_config WHERE company_id = ?
            """, rs -> rs.next() ? new Config(rs.getString(1), rs.getString(2),
                (Integer) rs.getObject(3), rs.getString(4), rs.getBoolean(5),
                rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8),
                rs.getObject(9, LocalTime.class), rs.getString(10)) : null, companyId);
    }

    static LocalTime parseScheduleTime(String value) {
        if (value == null || !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d")) {
            throw new BizException("WECOM_SCHEDULE_TIME_INVALID", "自动同步时间格式必须为 HH:mm:ss");
        }
        return LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME);
    }

    static String normalizeScheduleMode(String value) {
        if (!"INCREMENTAL".equals(value) && !"FULL".equals(value)) {
            throw new BizException("WECOM_SCHEDULE_MODE_INVALID", "自动同步模式无效");
        }
        return value;
    }

    private static String formatScheduleTime(LocalTime value) {
        return value.format(SCHEDULE_TIME_FORMAT);
    }

    /** 连接配置的读取结果。包内可见：{@code WecomService.run()} 需要一个具名类型来持有它。 */
    record Config(String corpId, String encryptedSecret, Integer agentId,
                          String encryptedAgentSecret, boolean oauthEnabled,
                          boolean jsSdkEnabled, boolean messageEnabled,
                          boolean scheduleEnabled, LocalTime scheduleTime,
                          String scheduleMode) { }
}

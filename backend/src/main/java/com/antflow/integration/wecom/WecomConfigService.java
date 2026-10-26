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

    public SettingsDto settings() {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        Config config = config();
        return new SettingsDto(config == null ? "" : config.corpId(),
            config != null && !config.encryptedSecret().isBlank(), jobStore.latestJob(),
            config == null ? null : config.agentId(),
            config != null && config.encryptedAgentSecret() != null,
            config != null && config.oauthEnabled(), config != null && config.jsSdkEnabled(),
            config != null && config.messageEnabled(),
            config != null && config.scheduleEnabled(),
            formatScheduleTime(config == null ? DEFAULT_SCHEDULE_TIME : config.scheduleTime()),
            config == null ? "INCREMENTAL" : config.scheduleMode());
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(String corpId, String secret) {
        Config current = config();
        return saveSettings(corpId, secret,
            current == null ? null : current.agentId(), null,
            current != null && current.oauthEnabled(), current != null && current.jsSdkEnabled(),
            current != null && current.messageEnabled(), null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(String corpId, String secret, Integer agentId,
                                    String agentSecret, boolean oauthEnabled,
                                    boolean jsSdkEnabled, boolean messageEnabled) {
        return saveSettings(corpId, secret, agentId, agentSecret, oauthEnabled,
            jsSdkEnabled, messageEnabled, null, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SettingsDto saveSettings(String corpId, String secret, Integer agentId,
                                    String agentSecret, Boolean oauthEnabled,
                                    Boolean jsSdkEnabled, Boolean messageEnabled,
                                    Boolean scheduleEnabled, String scheduleTime,
                                    String scheduleMode) {
        authorization.requirePermission(PermissionCodes.INTEGRATION_WECOM_MANAGE);
        String normalizedCorpId = corpId == null ? "" : corpId.trim();
        if (normalizedCorpId.isBlank() || normalizedCorpId.length() > 128) {
            throw new BizException("WECOM_CORP_ID_REQUIRED", "请输入有效的 CorpID");
        }
        Config current = config();
        String encrypted = secret == null || secret.isBlank()
            ? current == null ? null : current.encryptedSecret()
            : cipher.encrypt(secret.trim(), companyIdFor(current));
        if (encrypted == null) {
            throw new BizException("WECOM_SECRET_REQUIRED", "首次配置时请输入通讯录同步 Secret");
        }
        String encryptedAgentSecret = agentSecret == null || agentSecret.isBlank()
            ? current == null ? null : current.encryptedAgentSecret()
            : cipher.encrypt(agentSecret.trim(), "wecom-agent:" + companyIdFor(current));
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
            """, companyIdFor(current), normalizedCorpId, encrypted, savedAgentId, encryptedAgentSecret,
            savedOauthEnabled, savedJsSdkEnabled, savedMessageEnabled, savedScheduleEnabled,
            savedScheduleTime, savedScheduleMode, actorId, actorId);
        audit.success("integration.wecom.settings.update", "WECOM_CONFIG", companyIdFor(current),
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("corpId", "secretConfigured", "scheduleEnabled",
                "scheduleTime", "scheduleMode")), Map.of());
        return new SettingsDto(normalizedCorpId, true, jobStore.latestJob(), savedAgentId,
            encryptedAgentSecret != null, savedOauthEnabled, savedJsSdkEnabled, savedMessageEnabled,
            savedScheduleEnabled, formatScheduleTime(savedScheduleTime), savedScheduleMode);
    }

    Config config() {
        return jdbc.query("""
            SELECT company_id, corp_id, secret_encrypted, agent_id, agent_secret_encrypted,
                   oauth_enabled, js_sdk_enabled, message_enabled,
                   schedule_enabled, schedule_time, schedule_mode
            FROM t_wecom_config ORDER BY company_id LIMIT 1
            """, rs -> rs.next() ? new Config(rs.getLong(1), rs.getString(2), rs.getString(3),
                (Integer) rs.getObject(4), rs.getString(5), rs.getBoolean(6),
                rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9),
                rs.getObject(10, LocalTime.class), rs.getString(11)) : null);
    }

    /**
     * 配置行的 company_id。
     *
     * <p>t_wecom_config 没有独立主键，company_id 就是主键；它同时是密文的 AAD 上下文。
     * 所以这里保留这个取值，但**只用于行标识与解密**，不再参与任何分区或权限判断。
     * 首次保存（还没有行）时取库里唯一那家公司。
     */
    private long companyIdFor(Config current) {
        if (current != null) return current.companyId();
        Long only = jdbc.query("SELECT id FROM t_company WHERE deleted = 0 ORDER BY created_at, id LIMIT 1",
            rs -> rs.next() ? rs.getLong(1) : null);
        if (only == null) {
            throw new BizException("COMPANY_NOT_FOUND", "系统内没有可用公司，无法保存企业微信配置");
        }
        return only;
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
    /** 配置行。{@code companyId} 是 t_wecom_config 的主键（该表没有独立 id），
     *  同时充当密文的 AAD 上下文——保留它不是为了分区，而是为了能解开既有密文。 */
    record Config(long companyId, String corpId, String encryptedSecret, Integer agentId,
                          String encryptedAgentSecret, boolean oauthEnabled,
                          boolean jsSdkEnabled, boolean messageEnabled,
                          boolean scheduleEnabled, LocalTime scheduleTime,
                          String scheduleMode) { }
}

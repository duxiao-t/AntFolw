package com.antflow.integration.wecom;

import com.antflow.engine.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 企微应用配置读取（原 WecomAppService#requireEnabled / #requireConfig / #appConfig）。
 *
 * 易错点：{@code column} / {@code enabledColumn} 是**拼进 SQL 的列名**，只能由本包传入常量
 * （oauth_enabled / js_sdk_enabled / message_enabled），任何外部输入都不能走到这里，否则是注入。
 */
@Component
class WecomAppConfigStore {
    private final JdbcTemplate jdbc;

    WecomAppConfigStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    WecomAppConfig requireEnabled(String column) {
        WecomAppConfig config = jdbc.query("SELECT company_id, corp_id, agent_id, agent_secret_encrypted, "
            + "oauth_enabled, js_sdk_enabled, message_enabled FROM t_wecom_config WHERE "
            + column + " LIMIT 1", rs -> rs.next() ? appConfig(rs) : null);
        if (config == null) throw new BizException("WECOM_APP_DISABLED", "企业微信应用能力尚未启用");
        return config;
    }

    WecomAppConfig requireConfig(String enabledColumn) {
        WecomAppConfig config = jdbc.query("SELECT company_id, corp_id, agent_id, agent_secret_encrypted, "
            + "oauth_enabled, js_sdk_enabled, message_enabled FROM t_wecom_config WHERE "
            + enabledColumn, rs -> rs.next() ? appConfig(rs) : null);
        if (config == null) throw new BizException("WECOM_APP_DISABLED", "企业微信应用能力尚未启用");
        return config;
    }

    static WecomAppConfig appConfig(java.sql.ResultSet rs) throws java.sql.SQLException {
        Integer agentId = (Integer) rs.getObject("agent_id");
        String secret = rs.getString("agent_secret_encrypted");
        if (agentId == null || secret == null) throw new BizException("WECOM_APP_REQUIRED", "企业微信应用配置不完整");
        return new WecomAppConfig(rs.getLong("company_id"), rs.getString("corp_id"), agentId,
            secret, rs.getBoolean("oauth_enabled"), rs.getBoolean("js_sdk_enabled"),
            rs.getBoolean("message_enabled"));
    }
}

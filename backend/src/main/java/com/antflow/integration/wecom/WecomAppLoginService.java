package com.antflow.integration.wecom;

import com.antflow.auth.AuthService;
import com.antflow.auth.AuthSessionService;
import com.antflow.auth.ExternalAuthProperties;
import com.antflow.auth.OidcService;
import com.antflow.engine.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/**
 * 企微网页免登：发起授权与回调换取本地会话（原 WecomAppService#authorize / #callback）。
 *
 * 易错点：
 * 1. state 只存哈希（AuthSessionService.hash），且用 UPDATE ... RETURNING 原子消费，重放会查不到行。
 * 2. 回调必须按 flow 里的 company_id 重新读配置，不能退回"第一条启用配置"——多公司下会串号。
 * 3. 成员未同步或本地账号停用统一返回 WECOM_ACCOUNT_UNAVAILABLE，不区分两种情况，避免探测内部状态。
 */
@Component
class WecomAppLoginService {
    private final JdbcTemplate jdbc;
    private final WecomClient client;
    private final WecomProperties wecom;
    private final ExternalAuthProperties authProperties;
    private final AuthService authService;
    private final AuthSessionService sessions;
    private final WecomAppConfigStore configs;
    private final WecomAppTokenCache tokens;

    WecomAppLoginService(JdbcTemplate jdbc, WecomClient client, WecomProperties wecom,
                         ExternalAuthProperties authProperties, AuthService authService,
                         AuthSessionService sessions, WecomAppConfigStore configs,
                         WecomAppTokenCache tokens) {
        this.jdbc = jdbc;
        this.client = client;
        this.wecom = wecom;
        this.authProperties = authProperties;
        this.authService = authService;
        this.sessions = sessions;
        this.configs = configs;
        this.tokens = tokens;
    }

    URI authorize(String returnUrl) {
        WecomAppConfig config = configs.requireEnabled("oauth_enabled");
        String state = WecomAppSupport.randomToken(32);
        jdbc.update("""
            INSERT INTO t_external_auth_flow(state_hash, provider_type, provider_id, return_path, expires_at)
            VALUES (?, 'WECOM', ?, ?, now() + interval '5 minutes')
            """, AuthSessionService.hash(state), config.companyId(),
            OidcService.safeReturnPath(returnUrl, "/mobile/"));
        String callback = authProperties.publicBaseUri().resolve("/api/public/auth/wecom/callback").toString();
        return URI.create(wecom.getOauthBaseUrl() + "/connect/oauth2/authorize?appid="
            + WecomAppSupport.encode(config.corpId()) + "&redirect_uri=" + WecomAppSupport.encode(callback)
            + "&response_type=code&scope=snsapi_base&state=" + WecomAppSupport.encode(state) + "#wechat_redirect");
    }

    URI callback(String state, String code, HttpServletRequest request,
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
        WecomAppConfig config = configs.requireConfig("oauth_enabled");
        String wecomUserId = client.oauthUserId(tokens.session(config), code);
        Long userId = jdbc.query("""
            SELECT mapping.user_id FROM t_wecom_user_mapping mapping
            JOIN t_user user_row ON user_row.id = mapping.user_id AND user_row.status = 'ACTIVE'
            WHERE mapping.wecom_user_id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, wecomUserId);
        if (userId == null) {
            throw new BizException("WECOM_ACCOUNT_UNAVAILABLE", "企业微信成员尚未同步或本地账号已停用");
        }
        AuthService.Authenticated authenticated = authService.resume(userId)
            .orElseThrow(() -> new BizException("WECOM_ACCOUNT_UNAVAILABLE", "本地账号已停用"));
        sessions.create(authenticated, request, response);
        return authProperties.publicBaseUri().resolve(flow.returnPath());
    }

    private record Flow(long companyId, String returnPath) { }
}

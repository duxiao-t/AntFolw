package com.antflow.auth;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 用户信息映射与身份绑定（原 OidcService#bindUser / #requireActiveUser）。
 *
 * 易错点：
 * 1. 已绑定身份直接复用，不再走 claim 匹配——否则用户改名后会因匹配不到而登录失败。
 * 2. email 匹配必须先确认 email_verified，未验证邮箱可被他人抢注。
 * 3. 首次绑定必须在事务里 INSERT ... ON CONFLICT DO NOTHING 后回查：并发下保证只有一方成功，
 *    另一方看到 user_id 不一致时按"已绑定"拒绝，不能覆盖既有绑定。
 */
@Component
class OidcIdentityBinder {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    OidcIdentityBinder(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    long bindUser(OidcProvider provider, Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) throw new BadCredentialsException("OIDC subject is missing");
        Long bound = jdbc.query("""
            SELECT user_id FROM t_oidc_identity_binding WHERE provider_id = ? AND subject = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, provider.id(), subject);
        if (bound != null) {
            requireActiveUser(bound);
            jdbc.update("UPDATE t_oidc_identity_binding SET last_login_at = now() WHERE provider_id = ? AND subject = ?",
                provider.id(), subject);
            return bound;
        }
        if ("email".equals(provider.matchField()) && !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new BadCredentialsException("OIDC email is not verified");
        }
        Object rawClaim = jwt.getClaims().get(provider.matchClaim());
        String claim = rawClaim == null ? "" : rawClaim.toString().trim();
        if (claim.isBlank()) throw new BadCredentialsException("OIDC matching claim is missing");
        String column = switch (provider.matchField()) {
            case "email" -> "email";
            case "employeeNo" -> "employee_no";
            default -> "username";
        };
        List<Long> matches = jdbc.queryForList("SELECT id FROM t_user WHERE status = 'ACTIVE' AND lower("
            + column + ") = lower(?)", Long.class, claim);
        if (matches.size() != 1) throw new BadCredentialsException("OIDC identity does not match one active user");
        long userId = matches.get(0);
        try {
            return transactions.execute(status -> {
                jdbc.update("""
                    INSERT INTO t_oidc_identity_binding(provider_id, subject, user_id)
                    VALUES (?, ?, ?) ON CONFLICT (provider_id, subject) DO NOTHING
                    """, provider.id(), subject, userId);
                Long result = jdbc.queryForObject("""
                    SELECT user_id FROM t_oidc_identity_binding WHERE provider_id = ? AND subject = ?
                    """, Long.class, provider.id(), subject);
                if (result == null || result != userId) throw new BadCredentialsException("OIDC identity is already bound");
                return result;
            });
        } catch (DataIntegrityViolationException exception) {
            throw new BadCredentialsException("local user is already bound to another identity");
        }
    }

    private void requireActiveUser(long id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM t_user WHERE id = ? AND status = 'ACTIVE'",
            Integer.class, id);
        if (count == null || count != 1) throw new BadCredentialsException("local user is disabled");
    }
}

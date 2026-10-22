package com.antflow.integration.wecom;

import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 企微模块的访问校验：企业是否存在、当前用户是否属于该企业。
 *
 * <p>为什么独立：配置读写、任务查询、发起同步三条路径都要它。留在
 * {@code WecomService} 里时，拆出去的配置服务与任务仓储要么复制这两段 SQL，
 * 要么反向依赖原类——两者都会让"公司边界"这条规则出现多份。
 *
 * <p>非管理员用 {@code HiddenResourceException}（而不是 403）：不让人通过状态码探测
 * 别家企业的存在性。这是全仓一致的"隐藏资源"语义。
 */
@Component
@RequiredArgsConstructor
class WecomAccessGuard {

    private final JdbcTemplate jdbc;

    void requireCompany(long companyId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_company WHERE id = ?",
            Long.class, companyId);
        if (count == null || count == 0) throw new BizException("COMPANY_NOT_FOUND", "企业不存在");
    }

    void requireCompanyAccess(long companyId) {
        requireCompany(companyId);
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        if (principal.isAdmin()) return;
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM t_user user_row
            JOIN t_department department ON department.id = user_row.dept_id
            WHERE user_row.id = ? AND department.company_id = ?
            """, Long.class, principal.userId(), companyId);
        if (count == null || count == 0) {
            throw new com.antflow.authz.HiddenResourceException("company not found");
        }
    }
}

package com.antflow.org;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrgCompanyAccess {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    public Long currentCompanyId() {
        return jdbc.query("""
            SELECT department.company_id FROM t_user member
            JOIN t_department department ON department.id = member.dept_id
            JOIN t_company company ON company.id = department.company_id
            WHERE member.id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, authorization.currentUserId());
    }

    public void requireCompany(Long companyId) {
        if (companyId == null) throw new BizException("COMPANY_REQUIRED", "请先选择公司");
        if (!authorization.isAdmin() && !companyId.equals(currentCompanyId())) {
            throw new HiddenResourceException("company not found");
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM t_company WHERE id = ?)", Boolean.class, companyId))) {
            throw new HiddenResourceException("company not found");
        }
    }

    public Long requireDepartment(Long departmentId) {
        if (departmentId == null) {
            throw new BizException("DEPARTMENT_REQUIRED", "请选择所属部门");
        }
        Long companyId = jdbc.query("SELECT company_id FROM t_department WHERE id = ?",
            rs -> rs.next() ? rs.getLong(1) : null, departmentId);
        if (companyId == null) throw new HiddenResourceException("department not found");
        requireCompany(companyId);
        return companyId;
    }

    public void requireUserSource(Long departmentId) {
        if (departmentId == null && authorization.isAdmin()) return;
        requireDepartment(departmentId);
    }

    /** 组织管理的公司边界不改变运行时选择器和业务单据的数据范围。 */
    public void constrainUsers(QueryWrapper<User> query, Long requestedCompanyId) {
        Long companyId = requestedCompanyId;
        if (companyId != null) requireCompany(companyId);
        if (!authorization.isAdmin()) {
            companyId = currentCompanyId();
            if (companyId == null) {
                query.apply("1 = 0");
                return;
            }
        }
        if (companyId != null) {
            query.apply("dept_id IN (SELECT id FROM t_department WHERE company_id = {0})", companyId);
        }
    }
}

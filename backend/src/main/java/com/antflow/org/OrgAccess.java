package com.antflow.org;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.engine.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 组织模块的入参存在性校验。
 *
 * <p>本类的前身是 {@code OrgAccess}：除了"部门必须存在"，它还要校验"该部门与调用者同公司"，
 * 并支撑"按公司裁剪用户列表"。单公司改造后公司不再是组织边界：
 * 同公司校验恒真且会误伤（例如无部门的管理员），按公司裁剪也失去意义，两者一并移除。
 * 保留下来的是仍然成立的**存在性**校验——写库前确认外键指向的行确实在。
 */
@Component
@RequiredArgsConstructor
public class OrgAccess {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    /** 校验部门存在，返回其 id。 */
    public Long requireDepartment(Long departmentId) {
        if (departmentId == null) {
            throw new BizException("DEPARTMENT_REQUIRED", "请选择所属部门");
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM t_department WHERE id = ?)", Boolean.class, departmentId))) {
            throw new HiddenResourceException("department not found");
        }
        return departmentId;
    }

    /** 成员的来源部门：管理员可以不给（建在根上），其他人必须给。 */
    public void requireUserSource(Long departmentId) {
        if (departmentId == null && authorization.isAdmin()) return;
        requireDepartment(departmentId);
    }
}

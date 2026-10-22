package com.antflow.authz;

import static com.antflow.authz.FormGrantSupport.grantUser;
import static com.antflow.authz.FormGrantSupport.nullableLong;
import static com.antflow.authz.FormGrantSupport.placeholders;

import com.antflow.authz.FormGrantService.FormGrantCandidates;
import com.antflow.authz.FormGrantService.GrantCompany;
import com.antflow.authz.FormGrantService.GrantDepartment;
import com.antflow.authz.FormGrantService.GrantRole;
import com.antflow.authz.FormGrantService.GrantUser;
import com.antflow.authz.FormGrantService.GrantUserPage;
import com.antflow.engine.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 授权对象（用户/角色/部门/公司）的候选查询与写入前校验。
 *
 * <p>从 {@link FormGrantService} 搬出（2026-10-22 类体积治理第三刀）。它只读 {@code t_user}/
 * {@code t_role}/{@code t_department}/{@code t_company}，不碰两张授权真源，所以和两个 Store 分开。
 *
 * <p><b>三条不能动的语义：</b>
 * <ol>
 *   <li>候选集合一律按当前主体的数据范围裁剪：管理员看全部，非管理员只看 {@code manageableDepartments}
 *       与 {@code SELF}；两者都没有时拼 {@code AND false}（fail-closed），不能"查不到就返回全部"。</li>
 *   <li>{@code departmentId} 过滤是**向下包含**（{@code department.path <@ selected_department.path}），
 *       与数据范围的部门树口径一致。</li>
 *   <li>非管理员不得把表单授予角色（{@code MISSING_PERMISSION}）、也不得授出数据范围之外的
 *       用户/部门（{@code OUTSIDE_DATA_SCOPE}）；这两条是写入前的最后一道。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class FormGrantCandidateQuery {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;

    FormGrantCandidates candidates(long formId) {
        authorizationService.requireFormMaintenance(formId,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        return loadCandidates();
    }

    FormGrantCandidates candidates() {
        authorizationService.requirePermission(PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        return loadCandidates();
    }

    private FormGrantCandidates loadCandidates() {
        AuthorizationService.AuthzSnapshot snapshot = authorizationService.currentSnapshot();
        List<GrantRole> roles = snapshot.admin() ? jdbcTemplate.query("""
            SELECT id, code, name FROM t_role WHERE enabled = true ORDER BY builtin DESC, id
            """, (rs, row) -> new GrantRole(rs.getLong("id"), rs.getString("code"),
                rs.getString("name"))) : List.of();
        List<GrantCompany> companies = snapshot.admin() ? jdbcTemplate.query(
            "SELECT id, name FROM t_company ORDER BY id",
            (rs, row) -> new GrantCompany(rs.getLong("id"), rs.getString("name"))) : List.of();
        return new FormGrantCandidates(roles, grantDepartments(snapshot), companies);
    }

    GrantUserPage userCandidates(Long formId, int page, int size, String keyword,
                                 Long departmentId) {
        if (formId == null) {
            authorizationService.requirePermission(PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        } else {
            authorizationService.requireFormMaintenance(formId,
                PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        }
        AuthorizationService.AuthzSnapshot snapshot = authorizationService.currentSnapshot();
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        CandidateQuery query = candidateQuery(snapshot, keyword, departmentId);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + query.fromWhere(),
            Long.class, query.args().toArray());
        List<Object> pageArgs = new ArrayList<>(query.args());
        pageArgs.add(normalizedSize);
        pageArgs.add((normalizedPage - 1) * normalizedSize);
        List<GrantUser> items = jdbcTemplate.query("""
            SELECT user_row.id, user_row.username, user_row.display_name,
                   user_row.employee_no, user_row.dept_id, department.name AS department_name
            """ + query.fromWhere() + """
            ORDER BY user_row.display_name, user_row.id LIMIT ? OFFSET ?
            """, (rs, row) -> grantUser(rs), pageArgs.toArray());
        return new GrantUserPage(items, total == null ? 0 : total, normalizedPage, normalizedSize);
    }

    /** 公司授权只对全局管理员开放，且公司必须存在；管理员校验本身留在门面（错误码不同）。 */
    void validateCompanies(Set<Long> companyIds) {
        for (Long companyId : companyIds) {
            Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_company WHERE id = ?", Long.class, companyId);
            if (count == null || count == 0) {
                throw new BizException("COMPANY_NOT_FOUND", "grant company not found");
            }
        }
    }

    void validateSubjects(Set<Long> userIds, Set<Long> roleIds,
                          Set<Long> departmentIds) {
        AuthorizationService.AuthzSnapshot snapshot = authorizationService.currentSnapshot();
        if (!snapshot.admin() && !roleIds.isEmpty()) {
            throw new AuthorizationFailureException("MISSING_PERMISSION",
                "only administrators can grant forms to roles");
        }
        for (Long userId : userIds) {
            CandidateUser user = jdbcTemplate.query("""
                SELECT id, username, display_name, employee_no, dept_id
                FROM t_user WHERE id = ? AND status = 'ACTIVE'
                """, rs -> rs.next() ? new CandidateUser(rs.getLong("id"),
                    rs.getString("username"), rs.getString("display_name"),
                    rs.getString("employee_no"), nullableLong(rs, "dept_id")) : null, userId);
            if (user == null) {
                throw new BizException("USER_NOT_FOUND", "grant user not found");
            }
            if (!snapshot.admin() && !authorizationService.inDataScope(snapshot,
                PermissionCodes.FORM_AUTHORIZATION_MANAGE, user.id(), user.departmentId())) {
                throw new AuthorizationFailureException("OUTSIDE_DATA_SCOPE",
                    "grant user is outside the permitted data scope");
            }
        }
        for (Long roleId : roleIds) {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_role WHERE id = ?",
                Long.class, roleId);
            if (count == null || count == 0) {
                throw new BizException("ROLE_NOT_FOUND", "grant role not found");
            }
        }
        Set<Long> manageable = snapshot.admin() ? Set.of()
            : authorizationService.manageableDepartments(snapshot,
                PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        for (Long departmentId : departmentIds) {
            Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_department WHERE id = ?", Long.class, departmentId);
            if (count == null || count == 0) {
                throw new BizException("DEPARTMENT_NOT_FOUND", "grant department not found");
            }
            if (!snapshot.admin() && !manageable.contains(departmentId)) {
                throw new AuthorizationFailureException("OUTSIDE_DATA_SCOPE",
                    "grant department is outside the permitted data scope");
            }
        }
    }

    private CandidateQuery candidateQuery(AuthorizationService.AuthzSnapshot snapshot,
                                          String keyword, Long departmentId) {
        StringBuilder sql = new StringBuilder("""
            FROM t_user user_row
            LEFT JOIN t_department department ON department.id = user_row.dept_id
            WHERE user_row.status = 'ACTIVE'
            """);
        List<Object> args = new ArrayList<>();
        if (!snapshot.admin()) {
            List<AuthorizationService.RoleGrant> grants = snapshot.permissionRoles()
                .getOrDefault(PermissionCodes.FORM_AUTHORIZATION_MANAGE, List.of());
            boolean self = grants.stream().anyMatch(grant -> grant.dataScope() == DataScope.SELF);
            Set<Long> departments = authorizationService.manageableDepartments(snapshot,
                PermissionCodes.FORM_AUTHORIZATION_MANAGE);
            if (!self && departments.isEmpty()) {
                sql.append(" AND false");
            } else {
                sql.append(" AND (");
                if (self) {
                    sql.append("user_row.id = ?");
                    args.add(snapshot.userId());
                }
                if (!departments.isEmpty()) {
                    if (self) sql.append(" OR ");
                    sql.append("user_row.dept_id IN (")
                        .append(placeholders(departments.size())).append(')');
                    args.addAll(departments);
                }
                sql.append(')');
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            String search = "%" + keyword.trim() + "%";
            sql.append("""
                 AND (user_row.display_name ILIKE ? OR user_row.username ILIKE ?
                   OR user_row.employee_no ILIKE ?)
                """);
            args.add(search);
            args.add(search);
            args.add(search);
        }
        if (departmentId != null) {
            sql.append("""
                 AND EXISTS (
                   SELECT 1 FROM t_department selected_department
                   WHERE selected_department.id = ?
                     AND department.path <@ selected_department.path
                 )
                """);
            args.add(departmentId);
        }
        return new CandidateQuery(sql.toString(), args);
    }

    private List<GrantDepartment> grantDepartments(AuthorizationService.AuthzSnapshot snapshot) {
        if (snapshot.admin()) {
            return jdbcTemplate.query("""
                SELECT id, parent_id, name FROM t_department ORDER BY path, sort_order, id
                """, (rs, row) -> new GrantDepartment(rs.getLong("id"),
                    nullableLong(rs, "parent_id"), rs.getString("name")));
        }
        Set<Long> visible = authorizationService.manageableDepartments(snapshot,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        boolean self = snapshot.permissionRoles()
            .getOrDefault(PermissionCodes.FORM_AUTHORIZATION_MANAGE, List.of()).stream()
            .anyMatch(grant -> grant.dataScope() == DataScope.SELF);
        if (self && snapshot.departmentId() != null) visible.add(snapshot.departmentId());
        if (visible.isEmpty()) return List.of();
        String placeholders = placeholders(visible.size());
        List<Object> args = new ArrayList<>(visible);
        args.addAll(visible);
        String sql = "SELECT DISTINCT department.id, department.parent_id, department.name, "
            + "department.path FROM t_department department WHERE department.id IN ("
            + placeholders + ") OR EXISTS (SELECT 1 FROM t_department target WHERE target.id IN ("
            + placeholders + ") AND target.path <@ department.path) ORDER BY department.path";
        return jdbcTemplate.query(sql, (rs, row) -> new GrantDepartment(rs.getLong("id"),
                nullableLong(rs, "parent_id"), rs.getString("name")), args.toArray());
    }

    private record CandidateUser(long id, String username, String displayName,
                                 String employeeNo, Long departmentId) { }
    private record CandidateQuery(String fromWhere, List<Object> args) { }
}

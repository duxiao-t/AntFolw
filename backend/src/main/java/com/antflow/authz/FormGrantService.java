package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FormGrantService {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public FormGrantDto get(long formId) {
        authorizationService.requireFormManagementScope(formId,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        Integer version = version(formId);
        List<Long> userIds = jdbcTemplate.queryForList("""
                SELECT subject_id FROM t_form_resource_grant
                WHERE form_def_id = ? AND subject_type = 'USER' ORDER BY subject_id
                """, Long.class, formId);
        List<Long> roleIds = jdbcTemplate.queryForList("""
                SELECT subject_id FROM t_form_resource_grant
                WHERE form_def_id = ? AND subject_type = 'ROLE' ORDER BY subject_id
                """, Long.class, formId);
        List<Long> departmentIds = jdbcTemplate.queryForList("""
                SELECT subject_id FROM t_form_resource_grant
                WHERE form_def_id = ? AND subject_type = 'DEPARTMENT' ORDER BY subject_id
                """, Long.class, formId);
        return new FormGrantDto(version, userIds, roleIds, departmentIds,
            selectedUsers(formId), selectedRoles(formId), selectedDepartments(formId));
    }

    public FormGrantCandidates candidates(long formId) {
        authorizationService.requireFormManagementScope(formId,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        return loadCandidates();
    }

    public FormGrantCandidates candidates() {
        authorizationService.requirePermission(PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        return loadCandidates();
    }

    private FormGrantCandidates loadCandidates() {
        AuthorizationService.AuthzSnapshot snapshot = authorizationService.currentSnapshot();
        List<GrantRole> roles = snapshot.admin() ? jdbcTemplate.query("""
            SELECT id, code, name FROM t_role WHERE enabled = true ORDER BY builtin DESC, id
            """, (rs, row) -> new GrantRole(rs.getLong("id"), rs.getString("code"),
                rs.getString("name"))) : List.of();
        return new FormGrantCandidates(roles, grantDepartments(snapshot));
    }

    public GrantUserPage userCandidates(Long formId, int page, int size, String keyword,
                                        Long departmentId) {
        if (formId == null) {
            authorizationService.requirePermission(PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        } else {
            authorizationService.requireFormManagementScope(formId,
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

    @Transactional
    public FormGrantDto replace(long formId, FormGrantWriteRequest request) {
        authorizationService.requireFormManagementScope(formId,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        if (request == null || request.version() == null) {
            throw new BizException("FORM_GRANT_VERSION_REQUIRED", "grant version is required");
        }
        validateSubjects(request.userIds(), request.roleIds(), request.departmentIds());
        boolean admin = authorizationService.isAdmin();
        int updated = jdbcTemplate.update("""
            UPDATE t_form_definition SET authz_version = authz_version + 1
            WHERE id = ? AND authz_version = ?
            """, formId, request.version());
        if (updated != 1) {
            throw new BizException("FORM_GRANT_VERSION_CONFLICT",
                "form administrators were changed by another user");
        }
        jdbcTemplate.update(admin
            ? "DELETE FROM t_form_resource_grant WHERE form_def_id = ?"
            : "DELETE FROM t_form_resource_grant WHERE form_def_id = ? AND subject_type IN ('USER', 'DEPARTMENT')",
            formId);
        Long actorId = PrincipalHolder.current().orElseThrow().userId();
        request.userIds().forEach(userId -> jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'USER', ?, ?)
            """, formId, userId, actorId));
        if (admin) {
            request.roleIds().forEach(roleId -> jdbcTemplate.update("""
                INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
                VALUES (?, 'ROLE', ?, ?)
                """, formId, roleId, actorId));
        }
        request.departmentIds().forEach(departmentId -> jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'DEPARTMENT', ?, ?)
            """, formId, departmentId, actorId));
        List<Long> effectiveRoleIds = admin ? request.roleIds().stream().sorted().toList()
            : jdbcTemplate.queryForList("""
                SELECT subject_id FROM t_form_resource_grant
                WHERE form_def_id = ? AND subject_type = 'ROLE' ORDER BY subject_id
                """, Long.class, formId);
        auditService.success("form.authorization.update", "FORM", formId,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", List.of("userIds", "roleIds", "departmentIds")),
            java.util.Map.of("userCount", request.userIds().size(),
                "roleCount", effectiveRoleIds.size(),
                "departmentCount", request.departmentIds().size()));
        return new FormGrantDto(request.version() + 1,
            request.userIds().stream().sorted().toList(),
            effectiveRoleIds, request.departmentIds().stream().sorted().toList(),
            selectedUsers(formId), selectedRoles(formId), selectedDepartments(formId));
    }

    @Transactional
    public void grantCreator(long formId, long creatorId) {
        jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'USER', ?, ?)
            ON CONFLICT (form_def_id, subject_type, subject_id) DO NOTHING
            """, formId, creatorId, creatorId);
    }

    private void validateSubjects(Set<Long> userIds, Set<Long> roleIds,
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

    private int version(long formId) {
        Integer version = jdbcTemplate.query("""
            SELECT authz_version FROM t_form_definition WHERE id = ? AND deleted = 0
            """, rs -> rs.next() ? rs.getInt(1) : null, formId);
        if (version == null) {
            throw new HiddenResourceException("form not found");
        }
        return version;
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

    private List<GrantUser> selectedUsers(long formId) {
        return jdbcTemplate.query("""
            SELECT user_row.id, user_row.username, user_row.display_name,
                   user_row.employee_no, user_row.dept_id, department.name AS department_name
            FROM t_form_resource_grant form_grant
            JOIN t_user user_row ON user_row.id = form_grant.subject_id
            LEFT JOIN t_department department ON department.id = user_row.dept_id
            WHERE form_grant.form_def_id = ? AND form_grant.subject_type = 'USER'
            ORDER BY user_row.display_name, user_row.id
            """, (rs, row) -> grantUser(rs), formId);
    }

    private List<GrantRole> selectedRoles(long formId) {
        return jdbcTemplate.query("""
            SELECT role.id, role.code, role.name
            FROM t_form_resource_grant form_grant
            JOIN t_role role ON role.id = form_grant.subject_id
            WHERE form_grant.form_def_id = ? AND form_grant.subject_type = 'ROLE'
            ORDER BY role.name, role.id
            """, (rs, row) -> new GrantRole(rs.getLong("id"), rs.getString("code"),
                rs.getString("name")), formId);
    }

    private List<GrantDepartment> selectedDepartments(long formId) {
        return jdbcTemplate.query("""
            SELECT department.id, department.parent_id, department.name
            FROM t_form_resource_grant form_grant
            JOIN t_department department ON department.id = form_grant.subject_id
            WHERE form_grant.form_def_id = ? AND form_grant.subject_type = 'DEPARTMENT'
            ORDER BY department.path, department.id
            """, (rs, row) -> new GrantDepartment(rs.getLong("id"),
                nullableLong(rs, "parent_id"), rs.getString("name")), formId);
    }

    private static GrantUser grantUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GrantUser(rs.getLong("id"), rs.getString("username"),
            rs.getString("display_name"), rs.getString("employee_no"),
            nullableLong(rs, "dept_id"), rs.getString("department_name"));
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    public record FormGrantDto(int version, List<Long> userIds, List<Long> roleIds,
                               List<Long> departmentIds, List<GrantUser> users,
                               List<GrantRole> roles,
                               List<GrantDepartment> departments) { }
    public record FormGrantCandidates(List<GrantRole> roles,
                                      List<GrantDepartment> departments) { }
    public record GrantUser(long id, String username, String displayName, String employeeNo,
                            Long departmentId, String departmentName) { }
    public record GrantRole(long id, String code, String name) { }
    public record GrantDepartment(long id, Long parentId, String name) { }
    public record GrantUserPage(List<GrantUser> items, long total, int page, int size) { }
    private record CandidateUser(long id, String username, String displayName,
                                 String employeeNo, Long departmentId) { }
    private record CandidateQuery(String fromWhere, List<Object> args) { }
    public record FormGrantWriteRequest(Integer version, Set<Long> userIds,
                                        Set<Long> roleIds, Set<Long> departmentIds) {
        public FormGrantWriteRequest {
            userIds = userIds == null ? Set.of() : new LinkedHashSet<>(userIds);
            roleIds = roleIds == null ? Set.of() : new LinkedHashSet<>(roleIds);
            departmentIds = departmentIds == null
                ? Set.of() : new LinkedHashSet<>(departmentIds);
        }
    }
}

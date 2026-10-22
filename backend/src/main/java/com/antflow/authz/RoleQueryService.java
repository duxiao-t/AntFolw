package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 角色的**读**侧：角色列表/详情、某人的有效能力、用户-角色绑定分页、可授部门候选。
 *
 * <p>从 {@link RoleAdminService} 搬出（2026-10-22 类体积治理第二刀）。这里的方法一律只读、不加事务，
 * 与写入路径（CRUD + 授权替换 + {@code authz_version} 递增）分开，避免"读查询里顺手改一行"。
 *
 * <p><b>两条不能动的语义：</b>
 * <ol>
 *   <li>{@link #effective} 只有查**自己**才免 {@code security:effective:read}；查别人必须过能力校验。
 *       范围计算直接复用 {@code AuthorizationService#manageableDepartments}，不要在这里另算一套。</li>
 *   <li>分页参数一律夹紧（page≥1、1≤size≤100）：前端传 0 或负数时不能把 OFFSET 算成负数。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class RoleQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;
    private final RoleStore store;

    List<RoleAdminService.RoleDto> roles() {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_READ);
        return store.all();
    }

    RoleAdminService.EffectivePermissionDto effective(long userId) {
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        if (principal.userId() != userId) {
            authorizationService.requirePermission(PermissionCodes.SECURITY_EFFECTIVE_READ);
        }
        AuthorizationService.AuthzSnapshot snapshot = authorizationService.snapshot(userId);
        Map<String, RoleAdminService.PermissionScopeDto> scopes = new LinkedHashMap<>();
        Set<Long> adminDepartments = snapshot.admin()
            ? new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT id FROM t_department ORDER BY id", Long.class)) : Set.of();
        snapshot.permissions().stream().sorted().forEach(permission -> {
            Set<DataScope> modes = snapshot.permissionRoles().getOrDefault(permission, List.of())
                .stream().map(AuthorizationService.RoleGrant::dataScope)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            scopes.put(permission, new RoleAdminService.PermissionScopeDto(modes,
                snapshot.admin() ? adminDepartments
                    : authorizationService.manageableDepartments(snapshot, permission),
                snapshot.admin() || modes.contains(DataScope.ALL)));
        });
        return new RoleAdminService.EffectivePermissionDto(userId, snapshot.roleCodes(), snapshot.permissions(),
            snapshot.departmentId(), snapshot.admin(), scopes);
    }

    RoleAdminService.UserRolePage userAssignments(int page, int size, String keyword) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_USER_ROLE_READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String query = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        Long total = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_user u
            WHERE (?::text IS NULL OR u.username ILIKE ? OR u.display_name ILIKE ?
                OR u.employee_no ILIKE ?)
            """, Long.class, query, query, query, query);
        List<RoleAdminService.UserRoleView> records = jdbcTemplate.query("""
            SELECT u.id, u.username, u.display_name, u.employee_no, u.status, u.dept_id,
                   COALESCE(array_agg(ur.role_id ORDER BY ur.role_id)
                       FILTER (WHERE ur.role_id IS NOT NULL), '{}') AS role_ids,
                   u.authz_version
            FROM t_user u
            LEFT JOIN t_user_role ur ON ur.user_id = u.id
            WHERE (?::text IS NULL OR u.username ILIKE ? OR u.display_name ILIKE ?
                OR u.employee_no ILIKE ?)
            GROUP BY u.id
            ORDER BY u.display_name, u.id LIMIT ? OFFSET ?
            """, (rs, row) -> new RoleAdminService.UserRoleView(rs.getLong("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("employee_no"),
                rs.getString("status"), nullableLong(rs, "dept_id"),
                longArray(rs.getArray("role_ids")), rs.getLong("authz_version")),
            query, query, query, query, safeSize, (safePage - 1) * safeSize);
        return new RoleAdminService.UserRolePage(records, total == null ? 0 : total, safePage, safeSize);
    }

    List<RoleAdminService.DepartmentCandidate> departmentCandidates() {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_MANAGE);
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        Set<Long> departmentIds = new LinkedHashSet<>();
        if (principal.isAdmin()) {
            departmentIds.addAll(jdbcTemplate.queryForList(
                "SELECT id FROM t_department ORDER BY id", Long.class));
        } else {
            AuthorizationService.AuthzSnapshot snapshot =
                authorizationService.snapshot(principal.userId());
            snapshot.permissions().stream().filter(PermissionCatalog::isScopeable)
                .forEach(permission -> departmentIds.addAll(
                    authorizationService.manageableDepartments(snapshot, permission)));
        }
        if (departmentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(
            departmentIds.size(), "?"));
        return jdbcTemplate.query("SELECT id, name FROM t_department WHERE id IN ("
                + placeholders + ") ORDER BY path, id",
            (rs, row) -> new RoleAdminService.DepartmentCandidate(rs.getLong("id"), rs.getString("name")),
            departmentIds.toArray());
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static List<Long> longArray(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        Object raw = array.getArray();
        if (raw instanceof Long[] values) return List.of(values);
        if (raw instanceof Object[] values) {
            return java.util.Arrays.stream(values).map(value -> ((Number) value).longValue()).toList();
        }
        return List.of();
    }
}

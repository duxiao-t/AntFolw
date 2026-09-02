package com.antflow.authz;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoleAdminService {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9._-]{1,63}");

    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public List<PermissionDto> permissions() {
        authorizationService.requirePermission(PermissionCodes.SECURITY_PERMISSION_READ);
        return jdbcTemplate.query("""
            SELECT code, name, category, risk_level, sort_order, kind, admin_only
            FROM t_permission ORDER BY sort_order, code
            """, (rs, row) -> new PermissionDto(rs.getString("code"), rs.getString("name"),
                rs.getString("category"), rs.getString("risk_level"), rs.getInt("sort_order"),
                rs.getString("kind"), rs.getBoolean("admin_only"),
                PagePermissionPolicy.dependencies(rs.getString("code"))));
    }

    public List<RoleDto> roles() {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_READ);
        return jdbcTemplate.query("""
            SELECT role.id, role.code, role.name, role.description, role.data_scope,
                   role.enabled, role.builtin, role.version,
                   COALESCE((SELECT array_agg(permission.permission_code
                       ORDER BY permission.permission_code)
                     FROM t_role_permission permission WHERE permission.role_id = role.id),
                     '{}') AS permission_codes,
                   COALESCE((SELECT array_agg(department.department_id
                       ORDER BY department.department_id)
                     FROM t_role_department department WHERE department.role_id = role.id),
                     '{}') AS department_ids,
                   (SELECT COUNT(*) FROM t_user_role user_role
                     WHERE user_role.role_id = role.id) AS user_count
            FROM t_role role
            ORDER BY role.builtin DESC, role.id
            """, (rs, row) -> new RoleDto(rs.getLong("id"), rs.getString("code"),
                rs.getString("name"), rs.getString("description"),
                rs.getString("data_scope"), rs.getBoolean("enabled"),
                rs.getBoolean("builtin"), rs.getInt("version"),
                stringArray(rs.getArray("permission_codes")),
                longArray(rs.getArray("department_ids")), rs.getLong("user_count")));
    }

    public RoleDto role(long id) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_READ);
        RoleDto result = roleOrNull(id);
        if (result == null) {
            throw new BizException("ROLE_NOT_FOUND", "role not found");
        }
        return result;
    }

    @Transactional
    public RoleDto create(RoleWriteRequest request) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_WRITE);
        validateRequest(request, false);
        validateGrantCeiling(request);
        jdbcTemplate.update("""
            INSERT INTO t_role(code, name, description, data_scope, enabled, builtin, version)
            VALUES (?, ?, ?, ?, ?, false, 0)
            """, request.code().trim(), request.name().trim(), normalized(request.description()),
            request.dataScope().name(), request.enabled());
        Long id = jdbcTemplate.queryForObject("SELECT id FROM t_role WHERE code = ?",
            Long.class, request.code().trim());
        replaceRoleConfiguration(id, request.permissionCodes(), request.customDepartmentIds());
        RoleDto created = requiredRole(id);
        auditService.success("security.role.create", "ROLE", id,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", List.of("code", "name", "dataScope",
                "enabled", "permissionCodes", "customDepartmentIds")),
            java.util.Map.of("permissionCount", request.permissionCodes().size()));
        return created;
    }

    @Transactional
    public RoleDto update(long id, RoleWriteRequest request) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_WRITE);
        RoleDto existing = roleOrNull(id);
        if (existing == null) {
            throw new BizException("ROLE_NOT_FOUND", "role not found");
        }
        validateRequest(request, true);
        if (!Objects.equals(existing.version(), request.version())) {
            throw new BizException("ROLE_VERSION_CONFLICT", "role was changed by another administrator");
        }
        if (existing.builtin()) {
            if (!existing.code().equals(request.code())
                || existing.enabled() != request.enabled()
                || !existing.dataScope().equals(request.dataScope().name())
                || !new LinkedHashSet<>(existing.permissionCodes())
                    .equals(new LinkedHashSet<>(request.permissionCodes()))) {
                throw new BizException("BUILTIN_ROLE_PROTECTED", "built-in role policy is immutable");
            }
        } else {
            if (!existing.code().equals(request.code())) {
                throw new BizException("ROLE_CODE_IMMUTABLE", "role code cannot be changed");
            }
            validateGrantCeiling(request);
        }
        List<Long> affectedUsers = usersWithRole(id);
        int updated = jdbcTemplate.update("""
            UPDATE t_role
            SET name = ?, description = ?, data_scope = ?, enabled = ?,
                version = version + 1, updated_at = now()
            WHERE id = ? AND version = ?
            """, request.name().trim(), normalized(request.description()), request.dataScope().name(),
            request.enabled(), id, request.version());
        if (updated != 1) {
            throw new BizException("ROLE_VERSION_CONFLICT", "role was changed by another administrator");
        }
        if (!existing.builtin()) {
            replaceRoleConfiguration(id, request.permissionCodes(), request.customDepartmentIds());
        }
        bumpUsers(affectedUsers);
        RoleDto result = requiredRole(id);
        auditService.success("security.role.update", "ROLE", id,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", List.of("name", "description", "dataScope",
                "enabled", "permissionCodes", "customDepartmentIds")),
            java.util.Map.of("affectedUserCount", affectedUsers.size(),
                "permissionCount", request.permissionCodes().size()));
        return result;
    }

    @Transactional
    public void delete(long id, int version) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_WRITE);
        RoleDto existing = roleOrNull(id);
        if (existing == null) {
            return;
        }
        if (existing.builtin() || "admin".equals(existing.code())) {
            throw new BizException("BUILTIN_ROLE_PROTECTED", "built-in roles cannot be deleted");
        }
        if (!Objects.equals(existing.version(), version)) {
            throw new BizException("ROLE_VERSION_CONFLICT", "role was changed by another administrator");
        }
        if (existing.userCount() > 0) {
            throw new BizException("ROLE_IN_USE", "remove users from the role before deleting it");
        }
        jdbcTemplate.update("DELETE FROM t_role WHERE id = ? AND version = ?", id, version);
        auditService.success("security.role.delete", "ROLE", id,
            AuditService.RiskLevel.CRITICAL, java.util.Map.of(),
            java.util.Map.of("code", existing.code()));
    }

    public EffectivePermissionDto effective(long userId) {
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        if (principal.userId() != userId) {
            authorizationService.requirePermission(PermissionCodes.SECURITY_EFFECTIVE_READ);
        }
        AuthorizationService.AuthzSnapshot snapshot = authorizationService.snapshot(userId);
        Map<String, PermissionScopeDto> scopes = new LinkedHashMap<>();
        Set<Long> adminDepartments = snapshot.admin()
            ? new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT id FROM t_department ORDER BY id", Long.class)) : Set.of();
        snapshot.permissions().stream().sorted().forEach(permission -> {
            Set<DataScope> modes = snapshot.permissionRoles().getOrDefault(permission, List.of())
                .stream().map(AuthorizationService.RoleGrant::dataScope)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            scopes.put(permission, new PermissionScopeDto(modes,
                snapshot.admin() ? adminDepartments
                    : authorizationService.manageableDepartments(snapshot, permission),
                snapshot.admin() || modes.contains(DataScope.ALL)));
        });
        return new EffectivePermissionDto(userId, snapshot.roleCodes(), snapshot.permissions(),
            snapshot.departmentId(), snapshot.admin(), scopes);
    }

    public UserRolePage userAssignments(int page, int size, String keyword) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_USER_ROLE_READ);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String query = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        Long total = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_user u
            WHERE (?::text IS NULL OR u.username ILIKE ? OR u.display_name ILIKE ?
                OR u.employee_no ILIKE ?)
            """, Long.class, query, query, query, query);
        List<UserRoleView> records = jdbcTemplate.query("""
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
            """, (rs, row) -> new UserRoleView(rs.getLong("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("employee_no"),
                rs.getString("status"), nullableLong(rs, "dept_id"),
                longArray(rs.getArray("role_ids")), rs.getLong("authz_version")),
            query, query, query, query, safeSize, (safePage - 1) * safeSize);
        return new UserRolePage(records, total == null ? 0 : total, safePage, safeSize);
    }

    public List<DepartmentCandidate> departmentCandidates() {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_WRITE);
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        Set<Long> departmentIds = authorizationService.manageableDepartments(
            authorizationService.snapshot(principal.userId()),
            PermissionCodes.SECURITY_ROLE_WRITE);
        if (departmentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(
            departmentIds.size(), "?"));
        return jdbcTemplate.query("SELECT id, name FROM t_department WHERE id IN ("
                + placeholders + ") ORDER BY path, id",
            (rs, row) -> new DepartmentCandidate(rs.getLong("id"), rs.getString("name")),
            departmentIds.toArray());
    }

    private void validateRequest(RoleWriteRequest request, boolean update) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BizException("ROLE_NAME_REQUIRED", "role name is required");
        }
        if (request.code() == null || !CODE.matcher(request.code().trim()).matches()) {
            throw new BizException("ROLE_CODE_INVALID", "role code must be lowercase and 2-64 characters");
        }
        if (request.dataScope() == null) {
            throw new BizException("DATA_SCOPE_REQUIRED", "data scope is required");
        }
        if (update && request.version() == null) {
            throw new BizException("ROLE_VERSION_REQUIRED", "role version is required");
        }
        Set<String> known = new LinkedHashSet<>(jdbcTemplate.queryForList(
            "SELECT code FROM t_permission", String.class));
        if (!known.containsAll(request.permissionCodes())) {
            throw new BizException("PERMISSION_UNKNOWN", "role contains an unknown permission");
        }
        PagePermissionPolicy.validate(request.permissionCodes(), "admin".equals(request.code()));
        for (Long departmentId : request.customDepartmentIds()) {
            Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_department WHERE id = ?", Long.class, departmentId);
            if (count == null || count == 0) {
                throw new BizException("DEPARTMENT_NOT_FOUND", "custom department not found");
            }
        }
    }

    private void validateGrantCeiling(RoleWriteRequest request) {
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        if (principal.isAdmin()) {
            return;
        }
        if (!principal.permissions().containsAll(request.permissionCodes())) {
            throw new AccessDeniedException("cannot grant permissions you do not hold");
        }
        AuthorizationService.AuthzSnapshot snapshot = authorizationService.snapshot(principal.userId());
        List<AuthorizationService.RoleGrant> grantingRoles = snapshot.permissionRoles()
            .getOrDefault(PermissionCodes.SECURITY_ROLE_WRITE, List.of());
        boolean allowed = grantingRoles.stream().anyMatch(grant -> switch (request.dataScope()) {
            case SELF -> true;
            case DEPARTMENT -> grant.dataScope() == DataScope.DEPARTMENT
                || grant.dataScope() == DataScope.DEPARTMENT_AND_DESCENDANTS
                || grant.dataScope() == DataScope.ALL;
            case DEPARTMENT_AND_DESCENDANTS -> grant.dataScope() == DataScope.DEPARTMENT_AND_DESCENDANTS
                || grant.dataScope() == DataScope.ALL;
            case ALL -> grant.dataScope() == DataScope.ALL;
            case CUSTOM -> grant.dataScope() == DataScope.CUSTOM
                || grant.dataScope() == DataScope.DEPARTMENT
                || grant.dataScope() == DataScope.DEPARTMENT_AND_DESCENDANTS
                || grant.dataScope() == DataScope.ALL;
        });
        if (!allowed) {
            throw new AccessDeniedException("requested data scope exceeds your authority");
        }
        if (request.dataScope() == DataScope.CUSTOM) {
            Set<Long> manageable = authorizationService.manageableDepartments(snapshot,
                PermissionCodes.SECURITY_ROLE_WRITE);
            if (!manageable.containsAll(request.customDepartmentIds())) {
                throw new AccessDeniedException("custom departments exceed your authority");
            }
        }
    }

    private void replaceRoleConfiguration(long roleId, Set<String> permissions,
                                          Set<Long> customDepartmentIds) {
        jdbcTemplate.update("DELETE FROM t_role_permission WHERE role_id = ?", roleId);
        permissions.forEach(permission -> jdbcTemplate.update("""
            INSERT INTO t_role_permission(role_id, permission_code) VALUES (?, ?)
            """, roleId, permission));
        jdbcTemplate.update("DELETE FROM t_role_department WHERE role_id = ?", roleId);
        customDepartmentIds.forEach(departmentId -> jdbcTemplate.update("""
            INSERT INTO t_role_department(role_id, department_id) VALUES (?, ?)
            """, roleId, departmentId));
    }

    private RoleDto roleOrNull(long id) {
        RoleBase base = jdbcTemplate.query("""
            SELECT role.id, role.code, role.name, role.description, role.data_scope,
                   role.enabled, role.builtin, role.version,
                   (SELECT COUNT(*) FROM t_user_role ur WHERE ur.role_id = role.id) AS user_count
            FROM t_role role WHERE role.id = ?
            """, rs -> rs.next() ? new RoleBase(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("data_scope"),
                rs.getBoolean("enabled"), rs.getBoolean("builtin"), rs.getInt("version"),
                rs.getLong("user_count")) : null, id);
        if (base == null) return null;
        return new RoleDto(base.id(), base.code(), base.name(), base.description(),
            base.dataScope(), base.enabled(), base.builtin(), base.version(),
            jdbcTemplate.queryForList("""
                SELECT permission_code FROM t_role_permission
                WHERE role_id = ? ORDER BY permission_code
                """, String.class, id),
            jdbcTemplate.queryForList("""
                SELECT department_id FROM t_role_department
                WHERE role_id = ? ORDER BY department_id
                """, Long.class, id), base.userCount());
    }

    private RoleDto requiredRole(long id) {
        RoleDto result = roleOrNull(id);
        if (result == null) throw new BizException("ROLE_NOT_FOUND", "role not found");
        return result;
    }

    private List<Long> usersWithRole(long roleId) {
        return jdbcTemplate.queryForList("SELECT user_id FROM t_user_role WHERE role_id = ?",
            Long.class, roleId);
    }

    public void bumpUsers(List<Long> userIds) {
        userIds.forEach(userId -> {
            jdbcTemplate.update("UPDATE t_user SET authz_version = authz_version + 1 WHERE id = ?",
                userId);
            authorizationService.evict(userId);
        });
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private static List<String> stringArray(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        Object raw = array.getArray();
        if (raw instanceof String[] values) return List.of(values);
        if (raw instanceof Object[] values) {
            return java.util.Arrays.stream(values).map(String::valueOf).toList();
        }
        return List.of();
    }

    public record PermissionDto(String code, String name, String category,
                                String riskLevel, int sortOrder, String kind,
                                boolean adminOnly, List<String> requiredPermissionCodes) { }
    public record RoleDto(long id, String code, String name, String description,
                          String dataScope, boolean enabled, boolean builtin, int version,
                          List<String> permissionCodes, List<Long> customDepartmentIds,
                          long userCount) { }
    public record RoleWriteRequest(String code, String name, String description,
                                   DataScope dataScope, boolean enabled, Integer version,
                                   Set<String> permissionCodes,
                                   Set<Long> customDepartmentIds) {
        public RoleWriteRequest {
            permissionCodes = permissionCodes == null ? Set.of() : Set.copyOf(permissionCodes);
            customDepartmentIds = customDepartmentIds == null
                ? Set.of() : Set.copyOf(customDepartmentIds);
        }
    }
    public record EffectivePermissionDto(long userId, Set<String> roleCodes,
                                          Set<String> permissions, Long departmentId,
                                          boolean admin,
                                          Map<String, PermissionScopeDto> permissionScopes) { }
    public record PermissionScopeDto(Set<DataScope> modes, Set<Long> departmentIds,
                                     boolean all) { }
    public record DepartmentCandidate(long id, String name) { }
    public record UserRoleView(long id, String username, String displayName, String employeeNo,
                               String status, Long departmentId, List<Long> roleIds,
                               long authzVersion) { }
    public record UserRolePage(List<UserRoleView> records, long total, int page, int size) { }
    private record RoleBase(long id, String code, String name, String description,
                            String dataScope, boolean enabled, boolean builtin, int version,
                            long userCount) { }
}

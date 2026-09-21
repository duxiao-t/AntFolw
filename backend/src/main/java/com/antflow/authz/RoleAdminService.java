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
            SELECT code, name, domain, risk_level, sort_order, admin_only, scopeable, default_scope
            FROM t_permission
            WHERE deprecated_at IS NULL
            ORDER BY sort_order, code
            """, (rs, row) -> {
            String domain = rs.getString("domain");
            return new PermissionDto(rs.getString("code"), rs.getString("name"), domain,
                PermissionCatalog.domainLabel(domain), rs.getString("risk_level"),
                rs.getInt("sort_order"), rs.getBoolean("admin_only"), rs.getBoolean("scopeable"),
                rs.getString("default_scope"));
        });
    }

    public List<RoleDto> roles() {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_READ);
        List<RoleBase> bases = jdbcTemplate.query(BASE_SQL + " ORDER BY role.builtin DESC, role.id",
            (rs, row) -> roleBase(rs));
        Map<Long, List<PermissionGrantDto>> grants = grantsFor(
            bases.stream().map(RoleBase::id).toList());
        return bases.stream().map(base -> toDto(base,
            grants.getOrDefault(base.id(), List.of()))).toList();
    }

    private static final String BASE_SQL = """
        SELECT role.id, role.code, role.name, role.description,
               role.enabled, role.builtin, role.version,
               (SELECT COUNT(*) FROM t_user_role user_role
                 WHERE user_role.role_id = role.id) AS user_count
        FROM t_role role
        """;

    private static RoleBase roleBase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RoleBase(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("description"), rs.getBoolean("enabled"), rs.getBoolean("builtin"),
            rs.getInt("version"), rs.getLong("user_count"));
    }

    private static RoleDto toDto(RoleBase base, List<PermissionGrantDto> grants) {
        return new RoleDto(base.id(), base.code(), base.name(), base.description(),
            base.enabled(), base.builtin(), base.version(), grants, base.userCount());
    }

    /** 角色 × 能力授权，含各自的数据范围覆盖值与自定义部门明细。 */
    private Map<Long, List<PermissionGrantDto>> grantsFor(List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(roleIds.size(), "?"));
        Map<Long, Map<String, String>> scopes = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT granted.role_id, granted.permission_code, granted.scope_override
            FROM t_role_permission granted
            JOIN t_permission permission ON permission.code = granted.permission_code
            WHERE permission.deprecated_at IS NULL AND granted.role_id IN (%s)
            ORDER BY permission.sort_order, granted.permission_code
            """.formatted(placeholders), rs -> {
            scopes.computeIfAbsent(rs.getLong("role_id"), key -> new LinkedHashMap<>())
                .put(rs.getString("permission_code"), rs.getString("scope_override"));
        }, roleIds.toArray());
        Map<String, List<Long>> departments = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT role_id, permission_code, department_id
            FROM t_role_permission_department
            WHERE role_id IN (%s)
            ORDER BY role_id, permission_code, department_id
            """.formatted(placeholders), rs -> {
            String key = rs.getLong("role_id") + "|" + rs.getString("permission_code");
            departments.computeIfAbsent(key, ignored -> new java.util.ArrayList<>())
                .add(rs.getLong("department_id"));
        }, roleIds.toArray());
        Map<Long, List<PermissionGrantDto>> result = new LinkedHashMap<>();
        scopes.forEach((roleId, byCode) -> result.put(roleId, byCode.entrySet().stream()
            .map(entry -> new PermissionGrantDto(entry.getKey(), entry.getValue(),
                List.copyOf(departments.getOrDefault(roleId + "|" + entry.getKey(), List.of()))))
            .toList()));
        return result;
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
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_MANAGE);
        validateRequest(request, false);
        jdbcTemplate.update("""
            INSERT INTO t_role(code, name, description, enabled, builtin, version)
            VALUES (?, ?, ?, ?, false, 0)
            """, request.code().trim(), request.name().trim(), normalized(request.description()),
            request.enabled());
        Long id = jdbcTemplate.queryForObject("SELECT id FROM t_role WHERE code = ?",
            Long.class, request.code().trim());
        replaceRoleConfiguration(id, request.permissions());
        RoleDto created = requiredRole(id);
        auditService.success("security.role.create", "ROLE", id,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", List.of("code", "name", "enabled", "permissions")),
            java.util.Map.of("permissionCount", request.permissions().size()));
        return created;
    }

    @Transactional
    public RoleDto update(long id, RoleWriteRequest request) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_MANAGE);
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
                || !existing.permissions().stream()
                    .map(grant -> grantSignature(grant.code(), grant.scopeOverride(),
                        grant.departmentIds()))
                    .collect(java.util.stream.Collectors.toSet())
                    .equals(request.permissions().stream()
                        .map(grant -> grantSignature(grant.code(), grant.scopeOverride(),
                            grant.departmentIds()))
                        .collect(java.util.stream.Collectors.toSet()))) {
                throw new BizException("BUILTIN_ROLE_PROTECTED", "built-in role policy is immutable");
            }
        } else {
            if (!existing.code().equals(request.code())) {
                throw new BizException("ROLE_CODE_IMMUTABLE", "role code cannot be changed");
            }
        }
        List<Long> affectedUsers = usersWithRole(id);
        int updated = jdbcTemplate.update("""
            UPDATE t_role
            SET name = ?, description = ?, enabled = ?,
                version = version + 1, updated_at = now()
            WHERE id = ? AND version = ?
            """, request.name().trim(), normalized(request.description()),
            request.enabled(), id, request.version());
        if (updated != 1) {
            throw new BizException("ROLE_VERSION_CONFLICT", "role was changed by another administrator");
        }
        if (!existing.builtin()) {
            replaceRoleConfiguration(id, request.permissions());
        }
        bumpUsers(affectedUsers);
        RoleDto result = requiredRole(id);
        auditService.success("security.role.update", "ROLE", id,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", List.of("name", "description", "enabled",
                "permissions")),
            java.util.Map.of("affectedUserCount", affectedUsers.size(),
                "permissionCount", request.permissions().size()));
        return result;
    }

    @Transactional
    public void delete(long id, int version) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_ROLE_MANAGE);
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
        if (update && request.version() == null) {
            throw new BizException("ROLE_VERSION_REQUIRED", "role version is required");
        }
        boolean adminRole = "admin".equals(request.code());
        Set<String> known = new LinkedHashSet<>(jdbcTemplate.queryForList(
            "SELECT code FROM t_permission WHERE deprecated_at IS NULL", String.class));
        Set<String> seen = new LinkedHashSet<>();
        for (PermissionGrantWriteRequest grant : request.permissions()) {
            if (grant.code() == null || !known.contains(grant.code())) {
                throw new BizException("PERMISSION_UNKNOWN", "role contains an unknown permission");
            }
            if (!seen.add(grant.code())) {
                throw new BizException("PERMISSION_DUPLICATED", "role contains a duplicated permission");
            }
            if (!adminRole && PermissionCatalog.isAdminOnly(grant.code())) {
                throw new BizException("ADMIN_ONLY_PERMISSION", "超管专属能力不能授予普通角色");
            }
            validateScope(grant);
            validateGrantCeiling(grant);
        }
    }

    /**
     * 授权天花板：非管理员不得把自己不具备的能力/更宽的数据范围授予他人。
     *
     * <p>本方法在 {@code 9e76add} 被删除且一直未恢复（原版基于已废弃的
     * {@code security.role.write} 旧码与已删除的 {@code t_role.data_scope}，无法照搬），
     * 此处按 V40 之后的"能力 + 每能力范围"模型重写。
     *
     * <p>今天对非管理员不可达——角色配置入口由 {@code security:role:manage} 把守且该能力
     * 是超管专属；保留它是防御纵深：一旦该能力放开给普通角色，这层校验就是唯一的兜底。
     */
    void validateGrantCeiling(PermissionGrantWriteRequest grant) {
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        if (principal.isAdmin()) {
            return;
        }
        if (!principal.permissions().contains(grant.code())) {
            throw new AccessDeniedException("cannot grant permissions you do not hold");
        }
        String requested = grant.scopeOverride();
        // 不写覆盖值即"使用能力默认范围"，是推荐用法，不在此处收紧。
        if (requested == null || requested.isBlank()) {
            return;
        }
        DataScope requestedScope = parseScope(requested);
        AuthorizationService.AuthzSnapshot snapshot = authorizationService.snapshot(principal.userId());
        boolean allowed = snapshot.permissionRoles().getOrDefault(grant.code(), List.of()).stream()
            .anyMatch(held -> covers(held.dataScope(), requestedScope));
        if (!allowed) {
            throw new AccessDeniedException("cannot grant a wider data scope than you hold");
        }
    }

    /** 授予方持有的范围是否覆盖被请求的范围。 */
    private static boolean covers(DataScope held, DataScope requested) {
        return switch (requested) {
            case SELF -> true;
            case DEPARTMENT -> held == DataScope.DEPARTMENT
                || held == DataScope.DEPARTMENT_AND_DESCENDANTS || held == DataScope.ALL;
            case DEPARTMENT_AND_DESCENDANTS -> held == DataScope.DEPARTMENT_AND_DESCENDANTS
                || held == DataScope.ALL;
            case ALL -> held == DataScope.ALL;
            case CUSTOM -> held == DataScope.CUSTOM || held == DataScope.DEPARTMENT;
        };
    }

    private void validateScope(PermissionGrantWriteRequest grant) {
        Set<Long> departmentIds = grant.departmentIds();
        if (!PermissionCatalog.isScopeable(grant.code())) {
            if (grant.scopeOverride() != null || !departmentIds.isEmpty()) {
                throw new BizException("DATA_SCOPE_NOT_SUPPORTED", "该能力不支持数据范围");
            }
            return;
        }
        // scopeOverride 为空表示"使用能力默认范围"，这是推荐用法，不是缺参数
        if (grant.scopeOverride() == null || grant.scopeOverride().isBlank()) {
            if (!departmentIds.isEmpty()) {
                throw new BizException("DATA_SCOPE_DEPARTMENT_UNEXPECTED",
                    "只有自定义数据范围才能选择部门");
            }
            return;
        }
        DataScope scope = parseScope(grant.scopeOverride());
        if (scope == DataScope.CUSTOM && departmentIds.isEmpty()) {
            throw new BizException("DATA_SCOPE_DEPARTMENT_REQUIRED", "自定义数据范围需要选择部门");
        }
        if (scope != DataScope.CUSTOM && !departmentIds.isEmpty()) {
            throw new BizException("DATA_SCOPE_DEPARTMENT_UNEXPECTED", "只有自定义数据范围才能选择部门");
        }
        for (Long departmentId : departmentIds) {
            Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_department WHERE id = ?", Long.class, departmentId);
            if (count == null || count == 0) {
                throw new BizException("DEPARTMENT_NOT_FOUND", "custom department not found");
            }
        }
    }

    private static DataScope parseScope(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("DATA_SCOPE_REQUIRED", "data scope is required");
        }
        try {
            return DataScope.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new BizException("DATA_SCOPE_INVALID", "unknown data scope: " + value);
        }
    }

    private void replaceRoleConfiguration(long roleId, Set<PermissionGrantWriteRequest> permissions) {
        jdbcTemplate.update("DELETE FROM t_role_permission WHERE role_id = ?", roleId);
        jdbcTemplate.update("DELETE FROM t_role_permission_department WHERE role_id = ?", roleId);
        for (PermissionGrantWriteRequest grant : permissions) {
            String scope = PermissionCatalog.isScopeable(grant.code())
                && grant.scopeOverride() != null && !grant.scopeOverride().isBlank()
                ? parseScope(grant.scopeOverride()).name() : null;
            jdbcTemplate.update("""
                INSERT INTO t_role_permission(role_id, permission_code, scope_override)
                VALUES (?, ?, ?)
                """, roleId, grant.code(), scope);
            if (DataScope.CUSTOM.name().equals(scope)) {
                grant.departmentIds().forEach(departmentId -> jdbcTemplate.update("""
                    INSERT INTO t_role_permission_department(role_id, permission_code, department_id)
                    VALUES (?, ?, ?)
                    """, roleId, grant.code(), departmentId));
            }
        }
    }

    private RoleDto roleOrNull(long id) {
        RoleBase base = jdbcTemplate.query(BASE_SQL + " WHERE role.id = ?",
            rs -> rs.next() ? roleBase(rs) : null, id);
        if (base == null) return null;
        return toDto(base, grantsFor(List.of(id)).getOrDefault(id, List.of()));
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

    public record PermissionDto(String code, String name, String domain, String domainLabel,
                                String riskLevel, int sortOrder, boolean adminOnly,
                                boolean scopeable, String defaultScope) { }
    /** 一条授权：能力 + 数据范围覆盖值（null=用能力默认）+ 自定义部门明细。 */
    public record PermissionGrantDto(String code, String scopeOverride, List<Long> departmentIds) { }
    public record RoleDto(long id, String code, String name, String description,
                          boolean enabled, boolean builtin, int version,
                          List<PermissionGrantDto> permissions, long userCount) { }
    public record RoleWriteRequest(String code, String name, String description, boolean enabled,
                                   Integer version,
                                   Set<PermissionGrantWriteRequest> permissions) {
        public RoleWriteRequest {
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }
    public record PermissionGrantWriteRequest(String code, String scopeOverride,
                                              Set<Long> departmentIds) {
        public PermissionGrantWriteRequest {
            departmentIds = departmentIds == null ? Set.of() : Set.copyOf(departmentIds);
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
                            boolean enabled, boolean builtin, int version,
                            long userCount) { }

    /** 授权签名，用于内置角色「策略不可变」的相等判断。 */
    private static String grantSignature(String code, String scopeOverride,
                                         java.util.Collection<Long> departmentIds) {
        String departments = departmentIds == null ? "" : departmentIds.stream()
            .sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return code + "|" + (scopeOverride == null ? "" : scopeOverride) + "|" + departments;
    }
}

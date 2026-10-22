package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 能力码这一侧：能力目录读、角色写入请求的授权校验（含"授权天花板"）、角色 × 能力的落库。
 *
 * <p>从 {@link RoleAdminService} 搬出（2026-10-22 类体积治理第二刀）。它和角色 CRUD 的触发因素不同：
 * 能力码目录或数据范围模型一变（例如 V40 的"能力 + 每能力范围"），改的是这里；角色本身的增删改
 * 与审计留在门面。
 *
 * <p><b>三条不能动的语义：</b>
 * <ol>
 *   <li>{@link #validateGrantCeiling} 是**防御纵深**：今天非管理员到不了角色配置入口
 *       （{@code security:role:manage} 是超管专属），但一旦放开，这层就是唯一兜底。不要因为
 *       "不可达"删掉它（{@code 9e76add} 删过一次，后来按新模型重写回来了）。</li>
 *   <li>{@code scopeOverride} 为空表示"使用能力默认范围"，是推荐用法：校验时**不能**当成缺参数，
 *       落库时也**不能**写入默认值（写死默认值会让能力目录日后调整默认范围时失效）。</li>
 *   <li>{@link #replaceRoleConfiguration} 是"先删后插"的全量替换：内置角色永远不走这里
 *       （门面里 {@code if (!existing.builtin())} 把守），否则内置策略会被改掉。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class RoleGrantAdmin {
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9._-]{1,63}");

    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;

    List<RoleAdminService.PermissionDto> permissions() {
        authorizationService.requirePermission(PermissionCodes.SECURITY_PERMISSION_READ);
        return jdbcTemplate.query("""
            SELECT code, name, domain, risk_level, sort_order, admin_only, scopeable, default_scope
            FROM t_permission
            WHERE deprecated_at IS NULL
            ORDER BY sort_order, code
            """, (rs, row) -> {
            String domain = rs.getString("domain");
            return new RoleAdminService.PermissionDto(rs.getString("code"), rs.getString("name"), domain,
                PermissionCatalog.domainLabel(domain), rs.getString("risk_level"),
                rs.getInt("sort_order"), rs.getBoolean("admin_only"), rs.getBoolean("scopeable"),
                rs.getString("default_scope"));
        });
    }

    void validateRequest(RoleAdminService.RoleWriteRequest request, boolean update) {
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
        for (RoleAdminService.PermissionGrantWriteRequest grant : request.permissions()) {
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
    void validateGrantCeiling(RoleAdminService.PermissionGrantWriteRequest grant) {
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

    private void validateScope(RoleAdminService.PermissionGrantWriteRequest grant) {
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

    void replaceRoleConfiguration(long roleId, Set<RoleAdminService.PermissionGrantWriteRequest> permissions) {
        jdbcTemplate.update("DELETE FROM t_role_permission WHERE role_id = ?", roleId);
        jdbcTemplate.update("DELETE FROM t_role_permission_department WHERE role_id = ?", roleId);
        for (RoleAdminService.PermissionGrantWriteRequest grant : permissions) {
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

    /**
     * 授权签名，用于内置角色「策略不可变」的相等判断。
     *
     * <p>部门集合先排序再拼串：{@code Set} 的迭代顺序不稳定，直接 {@code toString} 会让"同一份策略"
     * 在不同请求里比出不等，内置角色就再也保存不了。
     */
    static String grantSignature(String code, String scopeOverride,
                                 java.util.Collection<Long> departmentIds) {
        String departments = departmentIds == null ? "" : departmentIds.stream()
            .sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return code + "|" + (scopeOverride == null ? "" : scopeOverride) + "|" + departments;
    }
}

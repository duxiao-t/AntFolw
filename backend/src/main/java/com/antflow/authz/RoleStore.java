package com.antflow.authz;

import com.antflow.authz.RoleAdminService.PermissionGrantDto;
import com.antflow.authz.RoleAdminService.RoleDto;
import com.antflow.engine.BizException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 角色行的读模型 + 用户授权版本递增（{@code t_user.authz_version}）。
 *
 * <p>从 {@link RoleAdminService} 搬出（2026-10-22 类体积治理第二刀）。搬出的理由是**变化原因不同**：
 * 角色 CRUD 的编排与审计留在门面，这里只回答"角色长什么样""谁绑了它"，以及授权变更后如何让缓存失效。
 *
 * <p><b>两条不能动的语义：</b>
 * <ol>
 *   <li>{@link #bumpUsers} 必须"先递增 {@code authz_version} 再 {@code evict}"，两条都不能省：
 *       只递增不 evict，本机会一直命中旧快照；只 evict 不递增，其它实例/会话没有信号重新装载
 *       （{@code authz_version} 是跨实例的唯一失效信号）。</li>
 *   <li>{@code grantsFor} 一次查完一批角色（两条 IN 查询），不要在循环里按角色查——角色页是
 *       N+1 的老灾区。</li>
 * </ol>
 *
 * <p>返回值里的 {@link RoleDto} 等值类型仍声明在门面 {@link RoleAdminService} 上：这是本包的既有约定
 * （见 {@code AuthorizationService} 末尾"嵌套值类型留在门面"），协作者只当不可变数据载体用。
 */
@Component
@RequiredArgsConstructor
class RoleStore {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;

    private static final String BASE_SQL = """
        SELECT role.id, role.code, role.name, role.description,
               role.enabled, role.builtin, role.version,
               (SELECT COUNT(*) FROM t_user_role user_role
                 WHERE user_role.role_id = role.id) AS user_count
        FROM t_role role
        """;

    List<RoleDto> all() {
        List<RoleBase> bases = jdbcTemplate.query(BASE_SQL + " ORDER BY role.builtin DESC, role.id",
            (rs, row) -> roleBase(rs));
        Map<Long, List<PermissionGrantDto>> grants = grantsFor(
            bases.stream().map(RoleBase::id).toList());
        return bases.stream().map(base -> toDto(base,
            grants.getOrDefault(base.id(), List.of()))).toList();
    }

    RoleDto roleOrNull(long id) {
        RoleBase base = jdbcTemplate.query(BASE_SQL + " WHERE role.id = ?",
            rs -> rs.next() ? roleBase(rs) : null, id);
        if (base == null) return null;
        return toDto(base, grantsFor(List.of(id)).getOrDefault(id, List.of()));
    }

    RoleDto requiredRole(long id) {
        RoleDto result = roleOrNull(id);
        if (result == null) throw new BizException("ROLE_NOT_FOUND", "role not found");
        return result;
    }

    List<Long> usersWithRole(long roleId) {
        return jdbcTemplate.queryForList("SELECT user_id FROM t_user_role WHERE role_id = ?",
            Long.class, roleId);
    }

    /**
     * 角色授权变更后让所有绑定用户的快照失效。顺序（递增 → evict）见类注释，不要调换。
     */
    void bumpUsers(List<Long> userIds) {
        userIds.forEach(userId -> {
            jdbcTemplate.update("UPDATE t_user SET authz_version = authz_version + 1 WHERE id = ?",
                userId);
            authorizationService.evict(userId);
        });
    }

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

    private record RoleBase(long id, String code, String name, String description,
                            boolean enabled, boolean builtin, int version,
                            long userCount) { }
}

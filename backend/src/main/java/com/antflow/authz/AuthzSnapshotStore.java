package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService.AuthzSnapshot;
import com.antflow.authz.AuthorizationService.RoleGrant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 授权快照的唯一装载点：读库 → 计算 → 按用户缓存 → 失效。
 *
 * <p>从 {@link AuthorizationService} 搬出（2026-10-22 类体积治理第一刀）。搬出的理由是
 * **变化原因不同**：能力判定/数据范围/资源可见性改的是"规则"，本类改的是"装载与缓存的
 * 时机与失效语义"。判定类只消费 {@link AuthorizationService.AuthzSnapshot}，不需要知道它
 * 是查库算出来的还是缓存里的。
 *
 * <p>三条不能动的语义（都在下面各自的位置有注释）：
 * <ol>
 *   <li>缓存按用户分桶（{@code ConcurrentHashMap.compute}），**不是**全局锁；</li>
 *   <li>{@code evictAll} 先递增代次再 clear，否则装载中的请求会把失效前的快照回填并长期命中；</li>
 *   <li>连续失效后退回"不缓存装载"——宁可多查一次库，也不返回过期权限（fail-closed）。</li>
 * </ol>
 */
@Component
public class AuthzSnapshotStore {
    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, CachedSnapshot> cache = new ConcurrentHashMap<>();
    /** 全局授权失效代次：evictAll 递增，用来识破"清空与回填"的竞态。 */
    private final java.util.concurrent.atomic.AtomicLong epoch =
        new java.util.concurrent.atomic.AtomicLong();

    AuthzSnapshotStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<PrincipalHolder.Principal> principalForRequest(long userId, UUID sessionId) {
        UserState state = userState(userId);
        if (state == null || !"ACTIVE".equals(state.status())) {
            cache.remove(userId);
            return Optional.empty();
        }
        AuthzSnapshot snapshot = cachedSnapshot(state);
        return Optional.of(new PrincipalHolder.Principal(
            userId,
            state.username(),
            state.displayName(),
            snapshot.roleCodes(),
            snapshot.permissions(),
            state.authzVersion(),
            state.departmentId(),
            sessionId
        ));
    }

    public AuthzSnapshot snapshot(long userId) {
        // 快路径：请求主体自己。认证时 principalForRequest 已经查过 t_user 并确认 ACTIVE，
        // 版本也带在 principal 上，所以同一请求内再查一次 t_user 是纯浪费——而 snapshot 会被
        // currentSnapshot / hasPermission(userId,…) / inCurrentDataScope 等入口反复调用
        // （UserService.listAuthorized 就是每行一次），原先等于每行一条 SELECT。
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElse(null);
        if (principal != null && principal.userId() == userId && principal.authzVersion() > 0) {
            return cachedSnapshot(new UserState(userId, principal.username(),
                principal.displayName(), "ACTIVE", principal.authzVersion(),
                principal.departmentId()));
        }
        UserState state = userState(userId);
        if (state == null || !"ACTIVE".equals(state.status())) {
            throw new AccessDeniedException("user is disabled");
        }
        return cachedSnapshot(state);
    }

    public void evict(long userId) {
        cache.remove(userId);
    }

    /**
     * 全局授权变更（菜单编排、能力目录）后一次性失效所有快照。
     *
     * <p>先递增失效代次再清缓存：装载中的请求靠代次变化察觉"我这次装载可能混了失效前的数据"。
     * 全局变更**不改动各用户的 authz_version**，所以只 clear() 挡不住正在进行的装载——
     * 它会把失效前算出来的快照重新插回缓存并长期命中（见 cachedSnapshot）。
     */
    public void evictAll() {
        epoch.incrementAndGet();
        cache.clear();
    }

    private AuthzSnapshot cachedSnapshot(UserState state) {
        // 原来是把 loadSnapshot（内含多次查库）放在一把**全局**锁里，于是任何一次缓存未命中
        // 都会把全站请求串行在数据库往返上。ConcurrentHashMap.compute 只锁住该用户的桶，
        // 同一用户不会重复装载，不同用户互不阻塞。
        //
        // 代价是 clear() 不再与装载互斥，于是要靠 epoch 把"清空与回填的竞态"补回来。
        for (int attempt = 0; attempt < 3; attempt++) {
            long startEpoch = epoch.get();
            CachedSnapshot cached = cache.compute(state.userId(), (userId, existing) -> {
                if (existing != null && existing.version() == state.authzVersion()
                        && existing.epoch() == startEpoch) {
                    return existing;
                }
                return new CachedSnapshot(state.authzVersion(), loadSnapshot(state), startEpoch);
            });
            if (epoch.get() == startEpoch) {
                return cached.snapshot();
            }
            // 装载期间发生过全局失效：这份快照可能混了失效前的数据。只移除"仍是我们刚放进去
            // 的那一份"（两参 remove 比较值），避免误删别的线程已经重算好的新快照。
            cache.remove(state.userId(), cached);
        }
        // 连续失效（极罕见）：退回不缓存的装载，宁可多查一次库也不返回过期权限。
        return loadSnapshot(state);
    }

    private AuthzSnapshot loadSnapshot(UserState user) {
        List<RoleBase> roles = jdbcTemplate.query("""
            SELECT role.id, role.code
            FROM t_user_role ur
            JOIN t_role role ON role.id = ur.role_id
            WHERE ur.user_id = ? AND role.enabled = true
            ORDER BY role.id
            """, (rs, row) -> new RoleBase(rs.getLong("id"), rs.getString("code")),
            user.userId());
        boolean admin = roles.stream().anyMatch(role -> "admin".equals(role.code()));

        // 数据范围按「角色 × 能力」解析：覆盖值优先，否则取能力目录声明的默认范围。
        Map<String, List<RoleGrant>> permissionRoles = new HashMap<>();
        for (RoleBase role : roles) {
            if (admin && "admin".equals(role.code())) {
                jdbcTemplate.queryForList("""
                    SELECT code FROM t_permission WHERE deprecated_at IS NULL
                    ORDER BY sort_order, code
                    """, String.class).forEach(code -> permissionRoles
                    .computeIfAbsent(code, key -> new ArrayList<>())
                    .add(new RoleGrant(
                        role.roleId(), role.code(), DataScope.ALL, Set.of())));
                continue;
            }
            List<GrantRow> grants = jdbcTemplate.query("""
                SELECT granted.permission_code, granted.scope_override
                FROM t_role_permission granted
                JOIN t_permission permission ON permission.code = granted.permission_code
                WHERE granted.role_id = ? AND permission.deprecated_at IS NULL
                  AND permission.admin_only = false
                ORDER BY granted.permission_code
                """, (rs, row) -> new GrantRow(rs.getString("permission_code"),
                rs.getString("scope_override")), role.roleId());
            for (GrantRow grant : grants) {
                DataScope scope = effectiveScope(grant);
                permissionRoles.computeIfAbsent(grant.permissionCode(), key -> new ArrayList<>())
                    .add(new RoleGrant(role.roleId(), role.code(), scope,
                        scope == DataScope.CUSTOM
                            ? customDepartments(role.roleId(), grant.permissionCode()) : Set.of()));
            }
        }
        Set<String> permissions = new LinkedHashSet<>(permissionRoles.keySet());
        Set<String> roleCodes = new LinkedHashSet<>();
        roles.forEach(role -> roleCodes.add(role.code()));
        Map<String, List<RoleGrant>> immutableGrants = new HashMap<>();
        permissionRoles.forEach((key, value) -> immutableGrants.put(key, List.copyOf(value)));
        return new AuthzSnapshot(user.userId(), user.departmentId(), admin,
            Collections.unmodifiableSet(roleCodes), Collections.unmodifiableSet(permissions),
            Collections.unmodifiableMap(immutableGrants));
    }

    /**
     * 覆盖值优先；未覆盖时取能力声明的默认范围；能力不支持范围管理时视为不限制。
     *
     * <p>包内可见（而非 private）是为了让 {@code AuthorizationServiceTest} 直接钉住
     * "defaultScope == null → ALL" 这一分支：它是红线，改动会波及审批人待办可见性。
     */
    static DataScope effectiveScope(GrantRow grant) {
        if (grant.scopeOverride() != null && !grant.scopeOverride().isBlank()) {
            return DataScope.valueOf(grant.scopeOverride());
        }
        PermissionCatalog.Entry entry = PermissionCatalog.require(grant.permissionCode());
        return entry.defaultScope() == null ? DataScope.ALL : entry.defaultScope();
    }

    private Set<Long> customDepartments(long roleId, String permissionCode) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(jdbcTemplate.queryForList(
            """
            SELECT department_id FROM t_role_permission_department
            WHERE role_id = ? AND permission_code = ? ORDER BY department_id
            """, Long.class, roleId, permissionCode)));
    }

    private UserState userState(long userId) {
        return jdbcTemplate.query("""
            SELECT id, username, display_name, status, authz_version, dept_id
            FROM t_user WHERE id = ?
            """, rs -> rs.next() ? new UserState(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getLong("authz_version"),
                nullableLong(rs, "dept_id")) : null, userId);
    }

    static Long nullableLong(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    // GrantRow / UserState 与下面对应的取数方法包内可见，供红线回归测试构造与打桩
    // （AuthorizationServiceTest 直接驱动 effectiveScope 与缓存竞态）。
    private record CachedSnapshot(long version, AuthzSnapshot snapshot, long epoch) { }
    record RoleBase(long roleId, String code) { }
    record GrantRow(String permissionCode, String scopeOverride) { }
    // 与 GrantRow 一样放宽到包内可见：AuthorizationServiceTest 需要构造它们来复现缓存竞态。
    record UserState(long userId, String username, String displayName, String status,
                             long authzVersion, Long departmentId) { }
}

package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService.AuthzSnapshot;
import com.antflow.authz.AuthorizationService.DataScopeFilter;
import com.antflow.authz.AuthorizationService.RoleGrant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 数据范围：某个能力在某个人身上"能看到哪些公司/部门"。
 *
 * <p>从 {@link AuthorizationService} 搬出（2026-10-22 第三刀）。它与能力判定的区别是**粒度**：
 * 能力判定回答"这个功能能不能用"（布尔），这里回答"用了之后哪些行可见"（集合）。
 * 两者混在一起时，改一次范围规则要重读整个鉴权类。
 *
 * <p><b>三条不能动的语义：</b>
 * <ol>
 *   <li>{@link #currentDataScope(String)} 返回 {@code Optional.empty()} 表示"无请求主体（系统内部
 *       任务）"，调用方应**跳过**注入；这不是放行，是"不注入行级条件"的另一条路径。</li>
 *   <li>返回的 filter 若三个范围标记全为 false 且部门集合为空，调用方会拼 {@code 1 = 0}——这是
 *       能力缺失时的 fail-closed 路径，**不要**在这里"兜底"成 ALL。</li>
 *   <li>范围一律先按公司裁剪（{@link #manageableDepartments} 末尾的 {@code retainAll}）：
 *       跨公司的部门 id 即使出现在 CUSTOM 里也不可见。</li>
 * </ol>
 */
@Component
public class DataScopeResolver {
    private final JdbcTemplate jdbcTemplate;
    private final AuthzSnapshotStore snapshots;
    private final CapabilityResolver capabilities;

    DataScopeResolver(JdbcTemplate jdbcTemplate, AuthzSnapshotStore snapshots,
                      CapabilityResolver capabilities) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshots = snapshots;
        this.capabilities = capabilities;
    }

    /**
     * 供数据权限拦截器使用：某能力在当前请求下的行级过滤条件。
     *
     * <p>返回空表示"无请求主体"（系统内部任务），调用方应跳过注入；
     * admin 表示管理员旁路；unrestricted 表示非管理员的 ALL 范围；
     * 既无 selfAllowed 又无 departmentIds 表示该能力缺失 → 调用方应注入 1=0（fail-closed）。
     */
    public Optional<DataScopeFilter> currentDataScope(String permissionCode) {
        if (PrincipalHolder.current().isEmpty()) {
            return Optional.empty();
        }
        AuthzSnapshot snapshot = capabilities.currentSnapshot();
        if (snapshot.admin()) {
            return Optional.of(new DataScopeFilter(true, true, false,
                snapshot.userId(), Set.of(), null));
        }
        List<RoleGrant> grants = snapshot.permissionRoles().getOrDefault(permissionCode, List.of());
        boolean all = grants.stream().anyMatch(grant -> grant.dataScope() == DataScope.ALL);
        boolean self = grants.stream().anyMatch(grant -> grant.dataScope() == DataScope.SELF);
        Set<Long> departments = all ? Set.of() : manageableDepartments(snapshot, permissionCode);
        return Optional.of(new DataScopeFilter(false, all, self,
            snapshot.userId(), departments, companyOf(snapshot)));
    }

    public boolean inCurrentDataScope(String permission, Long ownerId, Long departmentId) {
        capabilities.requirePermission(permission);
        if (capabilities.principal().isAdmin()) {
            return true;
        }
        return inDataScope(capabilities.currentSnapshot(), permission, ownerId, departmentId);
    }

    public void requireCurrentDataScope(String permission, Long ownerId, Long departmentId) {
        if (!inCurrentDataScope(permission, ownerId, departmentId)) {
            throw new AuthorizationFailureException("OUTSIDE_DATA_SCOPE",
                "resource is outside the permitted data scope");
        }
    }

    public Set<Long> visibleDepartments(String permission) {
        capabilities.requirePermission(permission);
        AuthzSnapshot snapshot = capabilities.currentSnapshot();
        Set<Long> result = manageableDepartments(snapshot, permission);
        if (snapshot.departmentId() != null) {
            boolean hasSelfGrant = snapshot.permissionRoles().getOrDefault(permission, List.of())
                .stream().anyMatch(grant -> grant.dataScope() == DataScope.SELF);
            if (hasSelfGrant) {
                result.add(snapshot.departmentId());
            }
        }
        return result;
    }

    public void requireManageableDepartment(String permission, Long departmentId) {
        capabilities.requirePermission(permission);
        if (capabilities.isAdmin()) {
            return;
        }
        if (departmentId == null || !manageableDepartments(capabilities.currentSnapshot(), permission)
            .contains(departmentId)) {
            throw new AccessDeniedException("department is outside the permitted data scope");
        }
    }

    public void requireAllDataScope(String permission) {
        capabilities.requirePermission(permission);
        if (capabilities.isAdmin()) {
            return;
        }
        boolean hasAll = capabilities.currentSnapshot().permissionRoles()
            .getOrDefault(permission, List.of())
            .stream().anyMatch(grant -> grant.dataScope() == DataScope.ALL);
        if (!hasAll) {
            throw new AccessDeniedException("all-department scope is required");
        }
    }

    public boolean inDataScope(AuthzSnapshot snapshot, String permission,
                               Long ownerId, Long departmentId) {
        if (snapshot.admin()) {
            return true;
        }
        List<RoleGrant> grants = snapshot.permissionRoles().getOrDefault(permission, List.of());
        for (RoleGrant grant : grants) {
            if (scopeAllows(snapshot.userId(), snapshot.departmentId(), grant,
                ownerId, departmentId)) {
                return true;
            }
        }
        return false;
    }

    public Set<Long> manageableDepartments(AuthzSnapshot snapshot, String permission) {
        if (snapshot.admin()) {
            return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT id FROM t_department ORDER BY id", Long.class));
        }
        Set<Long> result = new LinkedHashSet<>();
        // 同一个查询在这几个分支里是重复的（多个 ALL 授权、多个"本部门及下级"授权都查同一份），
        // 而 currentDataScope 是数据权限拦截器的热路径——按需算一次再复用，别让查询次数随授权项增长。
        List<Long> allDepartments = null;
        List<Long> departmentSubtree = null;
        for (RoleGrant grant : snapshot.permissionRoles().getOrDefault(permission, List.of())) {
            switch (grant.dataScope()) {
                case ALL -> {
                    if (allDepartments == null) {
                        allDepartments = jdbcTemplate.queryForList(
                            "SELECT id FROM t_department ORDER BY id", Long.class);
                    }
                    result.addAll(allDepartments);
                }
                case DEPARTMENT -> addIfPresent(result, snapshot.departmentId());
                case DEPARTMENT_AND_DESCENDANTS -> {
                    if (snapshot.departmentId() != null) {
                        if (departmentSubtree == null) {
                            departmentSubtree = jdbcTemplate.queryForList("""
                                SELECT child.id FROM t_department child
                                JOIN t_department parent ON parent.id = ?
                                WHERE child.path <@ parent.path
                                ORDER BY child.path
                                """, Long.class, snapshot.departmentId());
                        }
                        result.addAll(departmentSubtree);
                    }
                }
                case CUSTOM -> result.addAll(grant.customDepartmentIds());
                case SELF -> { }
            }
        }
        if (!result.isEmpty()) {
            Long companyId = companyOf(snapshot);
            result.retainAll(companyId == null ? Set.of() : jdbcTemplate.queryForList(
                "SELECT id FROM t_department WHERE company_id = ?", Long.class, companyId));
        }
        return result;
    }

    private boolean scopeAllows(long userId, Long userDepartmentId, RoleGrant grant,
                                Long ownerId, Long resourceDepartmentId) {
        return switch (grant.dataScope()) {
            case SELF -> Objects.equals(ownerId, userId);
            case DEPARTMENT -> userDepartmentId != null
                && Objects.equals(userDepartmentId, resourceDepartmentId);
            case DEPARTMENT_AND_DESCENDANTS -> isDepartmentDescendant(
                resourceDepartmentId, userDepartmentId);
            case CUSTOM -> resourceDepartmentId != null
                && grant.customDepartmentIds().contains(resourceDepartmentId);
            case ALL -> true;
        };
    }

    private boolean isDepartmentDescendant(Long candidateId, Long ancestorId) {
        if (candidateId == null || ancestorId == null) {
            return false;
        }
        Boolean result = jdbcTemplate.queryForObject("""
            SELECT child.path <@ parent.path
            FROM t_department child, t_department parent
            WHERE child.id = ? AND parent.id = ?
            """, Boolean.class, candidateId, ancestorId);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 主体所在部门对应的公司。表单授权（t_form_resource_grant）与实例可见性都要按公司隔离，
     * 而公司不在快照里（部门会被改组），所以每次从 t_department 现取。
     */
    Long companyOf(AuthzSnapshot snapshot) {
        if (snapshot.departmentId() == null) return null;
        return jdbcTemplate.query("SELECT company_id FROM t_department WHERE id = ?",
            rs -> rs.next() ? nullableLong(rs, "company_id") : null, snapshot.departmentId());
    }

    /**
     * 跨公司不可见：资源公司与主体公司不一致时直接判否（不是"退回按部门判"）。
     * 这里的 {@code companyId != null &&} 是 fail-closed 的起点——归属未知的实例一律不可见。
     */
    boolean inCompanyDataScope(AuthzSnapshot snapshot, String permission,
                               Long ownerId, Long departmentId, Long companyId) {
        return companyId != null && companyId.equals(companyOf(snapshot))
            && inDataScope(snapshot, permission, ownerId, departmentId);
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void addIfPresent(Set<Long> target, Long value) {
        if (value != null) {
            target.add(value);
        }
    }
}

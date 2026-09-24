package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthorizationService {
    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, CachedSnapshot> cache = new ConcurrentHashMap<>();
    /** 全局授权失效代次：evictAll 递增，用来识破"清空与回填"的竞态。 */
    private final java.util.concurrent.atomic.AtomicLong epoch =
        new java.util.concurrent.atomic.AtomicLong();

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

    public void requirePermission(String permission) {
        PrincipalHolder.Principal principal = principal();
        if (!principal.isAdmin() && !principal.permissions().contains(permission)) {
            throw new AuthorizationFailureException("MISSING_PERMISSION",
                "missing permission: " + permission);
        }
    }

    /** 多个能力任一即可（例如"表单管理员或流程管理员"都能配置流程）。 */
    public void requireAnyPermission(String... permissions) {
        PrincipalHolder.Principal principal = principal();
        if (principal.isAdmin()) {
            return;
        }
        for (String permission : permissions) {
            if (principal.permissions().contains(permission)) {
                return;
            }
        }
        throw new AuthorizationFailureException("MISSING_PERMISSION",
            "missing permission: " + String.join(" or ", permissions));
    }

    public void requireAdmin() {
        if (!principal().isAdmin()) {
            throw new AccessDeniedException("administrator role required");
        }
    }

    public boolean hasPermission(String permission) {
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElse(null);
        return principal != null
            && (principal.isAdmin() || principal.permissions().contains(permission));
    }

    /**
     * 指定用户（而不是当前请求主体）是否持有该能力。
     * 服务层按 userId 判定时必须用这个——与 canUseForm/hasFormGrant 一样以入参用户为准，
     * 否则"按 A 用户查数据"和"用当前主体的能力判定"会错配。
     */
    public boolean hasPermission(long userId, String permission) {
        AuthzSnapshot snapshot = snapshot(userId);
        return snapshot.admin() || snapshot.permissions().contains(permission);
    }

    public boolean isAdmin() {
        return principal().isAdmin();
    }

    public long currentUserId() {
        return principal().userId();
    }

    public AuthzSnapshot currentSnapshot() {
        PrincipalHolder.Principal principal = principal();
        return snapshot(principal.userId());
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
        AuthzSnapshot snapshot = currentSnapshot();
        if (snapshot.admin()) {
            return Optional.of(new DataScopeFilter(true, true, false,
                snapshot.userId(), Set.of()));
        }
        List<RoleGrant> grants = snapshot.permissionRoles().getOrDefault(permissionCode, List.of());
        boolean all = grants.stream().anyMatch(grant -> grant.dataScope() == DataScope.ALL);
        boolean self = grants.stream().anyMatch(grant -> grant.dataScope() == DataScope.SELF);
        Set<Long> departments = all ? Set.of() : manageableDepartments(snapshot, permissionCode);
        return Optional.of(new DataScopeFilter(false, all, self,
            snapshot.userId(), departments));
    }

    public record DataScopeFilter(boolean admin, boolean unrestricted, boolean selfAllowed,
                                  long userId, Set<Long> departmentIds) {
    }

    public boolean inCurrentDataScope(String permission, Long ownerId, Long departmentId) {
        requirePermission(permission);
        if (principal().isAdmin()) {
            return true;
        }
        return inDataScope(currentSnapshot(), permission, ownerId, departmentId);
    }

    public void requireCurrentDataScope(String permission, Long ownerId, Long departmentId) {
        if (!inCurrentDataScope(permission, ownerId, departmentId)) {
            throw new AuthorizationFailureException("OUTSIDE_DATA_SCOPE",
                "resource is outside the permitted data scope");
        }
    }

    public Set<Long> visibleDepartments(String permission) {
        requirePermission(permission);
        AuthzSnapshot snapshot = currentSnapshot();
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
        requirePermission(permission);
        if (isAdmin()) {
            return;
        }
        if (departmentId == null || !manageableDepartments(currentSnapshot(), permission)
            .contains(departmentId)) {
            throw new AccessDeniedException("department is outside the permitted data scope");
        }
    }

    public void requireAllDataScope(String permission) {
        requirePermission(permission);
        if (isAdmin()) {
            return;
        }
        boolean hasAll = currentSnapshot().permissionRoles().getOrDefault(permission, List.of())
            .stream().anyMatch(grant -> grant.dataScope() == DataScope.ALL);
        if (!hasAll) {
            throw new AccessDeniedException("all-department scope is required");
        }
    }

    public boolean hasFormGrant(long formId, long userId) {
        AuthzSnapshot snapshot = snapshot(userId);
        if (snapshot.admin()) {
            return true;
        }
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM t_form_resource_grant grant_row
            WHERE grant_row.form_def_id = ?
              AND (
                (grant_row.subject_type = 'USER' AND grant_row.subject_id = ?)
                OR (grant_row.subject_type = 'ROLE' AND grant_row.subject_id IN (
                    SELECT ur.role_id
                    FROM t_user_role ur
                    JOIN t_role role ON role.id = ur.role_id AND role.enabled = true
                    WHERE ur.user_id = ?
                ))
                OR (grant_row.subject_type = 'DEPARTMENT' AND EXISTS (
                    SELECT 1
                    FROM t_user grant_user
                    JOIN t_department user_department ON user_department.id = grant_user.dept_id
                    JOIN t_department grant_department ON grant_department.id = grant_row.subject_id
                    WHERE grant_user.id = ?
                      AND user_department.path <@ grant_department.path
                ))
              )
            """, Long.class, formId, userId, userId, userId);
        return count != null && count > 0;
    }

    /**
     * 该用户有使用授权的表单 id 集合，用于列表类的批量过滤。
     * 返回空表示"不需要过滤"（管理员），调用方直接放行——不是"一个都没有"。
     */
    public Optional<Set<Long>> usableFormIds(long userId) {
        if (snapshot(userId).admin()) {
            return Optional.empty();
        }
        // 与 hasFormGrant 同一套判定，只是去掉了 form_def_id 这一维。
        return Optional.of(new java.util.HashSet<>(jdbcTemplate.queryForList("""
            SELECT grant_row.form_def_id
            FROM t_form_resource_grant grant_row
            WHERE (grant_row.subject_type = 'USER' AND grant_row.subject_id = ?)
               OR (grant_row.subject_type = 'ROLE' AND grant_row.subject_id IN (
                    SELECT ur.role_id
                    FROM t_user_role ur
                    JOIN t_role role ON role.id = ur.role_id AND role.enabled = true
                    WHERE ur.user_id = ?))
               OR (grant_row.subject_type = 'DEPARTMENT' AND EXISTS (
                    SELECT 1
                    FROM t_user grant_user
                    JOIN t_department user_department ON user_department.id = grant_user.dept_id
                    JOIN t_department grant_department
                      ON grant_department.id = grant_row.subject_id
                    WHERE grant_user.id = ?
                      AND user_department.path <@ grant_department.path))
            """, Long.class, userId, userId, userId)));
    }

    /** 已发布表单的使用入口：原子能力与使用范围必须同时满足。 */
    public void requireFormUse(long formId) {
        PrincipalHolder.Principal principal = principal();
        if (principal.isAdmin()) {
            return;
        }
        requirePermission(PermissionCodes.FORM_RUNTIME_READ);
        if (!hasFormGrant(formId, principal.userId())) {
            throw new HiddenResourceException("form not found");
        }
    }

    public boolean canUseForm(long formId, long userId) {
        AuthzSnapshot snapshot = snapshot(userId);
        return snapshot.admin()
            || (snapshot.permissions().contains(PermissionCodes.FORM_RUNTIME_READ)
                && hasFormGrant(formId, userId));
    }

    public boolean hasFormMaintainer(long formId, long userId) {
        AuthzSnapshot snapshot = snapshot(userId);
        if (snapshot.admin()) {
            return true;
        }
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM t_form_maintainer maintainer
            JOIN t_form_definition form ON form.id = maintainer.form_def_id
              AND form.deleted = 0
            JOIN t_user user_row ON user_row.id = maintainer.user_id
              AND user_row.status = 'ACTIVE'
            WHERE maintainer.form_def_id = ? AND maintainer.user_id = ?
            """, Long.class, formId, userId);
        return count != null && count > 0;
    }

    /**
     * 该用户维护的表单 id 集合，用于列表类的批量过滤。
     * 返回空表示"不需要过滤"（管理员），调用方直接放行——不是"什么都不维护"。
     */
    public Optional<Set<Long>> maintainableFormIds(long userId) {
        if (snapshot(userId).admin()) {
            return Optional.empty();
        }
        return Optional.of(new java.util.HashSet<>(jdbcTemplate.queryForList("""
            SELECT maintainer.form_def_id
            FROM t_form_maintainer maintainer
            JOIN t_form_definition form ON form.id = maintainer.form_def_id
              AND form.deleted = 0
            JOIN t_user user_row ON user_row.id = maintainer.user_id
              AND user_row.status = 'ACTIVE'
            WHERE maintainer.user_id = ?
            """, Long.class, userId)));
    }

    /** 模板操作入口：非管理员必须同时是维护人并持有对应原子能力。 */
    public void requireFormMaintenance(long formId, String permission) {
        PrincipalHolder.Principal principal = principal();
        if (principal.isAdmin()) {
            return;
        }
        requirePermission(permission);
        if (!hasFormMaintainer(formId, principal.userId())) {
            throw new HiddenResourceException("form not found");
        }
    }

    public void requireFormMaintenanceAny(long formId, String... permissions) {
        PrincipalHolder.Principal principal = principal();
        if (principal.isAdmin()) {
            return;
        }
        requireAnyPermission(permissions);
        if (!hasFormMaintainer(formId, principal.userId())) {
            throw new HiddenResourceException("form not found");
        }
    }

    /**
     * 发起/填报入口统一使用：表单必须已发布且当前用户具备表单使用授权。
     * 未授权时返回资源不存在，避免泄露表单是否存在。
     */
    public void requireFormUseByCode(String code) {
        Long formId = jdbcTemplate.query("""
            SELECT id FROM t_form_definition
            WHERE code = ? AND status = 'PUBLISHED' AND deleted = 0
            """, rs -> rs.next() ? rs.getLong(1) : null, code);
        if (formId == null) {
            throw new HiddenResourceException("form not found");
        }
        requireFormUse(formId);
    }

    public void requireReadableInstance(long instanceId) {
        PrincipalHolder.Principal principal = principal();
        if (instanceVisibility(instanceId, principal.userId()) == InstanceVisibility.NONE) {
            throw new HiddenResourceException("instance not found");
        }
    }

    public boolean canReadInstance(long instanceId, long userId) {
        return instanceVisibility(instanceId, userId) != InstanceVisibility.NONE;
    }

    public boolean canReadFullInstance(long instanceId, long userId) {
        return instanceVisibility(instanceId, userId) == InstanceVisibility.FULL;
    }

    public InstanceVisibility instanceVisibility(long instanceId, long userId) {
        InstanceAccess resource = instanceAccess(instanceId);
        if (resource == null) {
            return InstanceVisibility.NONE;
        }
        AuthzSnapshot snapshot = snapshot(userId);
        if (snapshot.admin() || Objects.equals(resource.startedBy(), userId)) {
            return InstanceVisibility.FULL;
        }
        if (snapshot.permissions().contains(PermissionCodes.WORKFLOW_TASK_READ)
            && isReadableTaskAssignee(instanceId, userId)) {
            return InstanceVisibility.FULL;
        }
        if (snapshot.permissions().contains(PermissionCodes.WORKFLOW_INSTANCE_READ)
            && inDataScope(snapshot, PermissionCodes.WORKFLOW_INSTANCE_READ,
                resource.startedBy(), resource.startedDepartmentId())) {
            return InstanceVisibility.FULL;
        }
        return isParticipant(instanceId, userId)
            ? InstanceVisibility.SUMMARY : InstanceVisibility.NONE;
    }

    public void requireReadableTask(long taskId) {
        Long instanceId = jdbcTemplate.query("SELECT proc_inst_id FROM t_task WHERE id = ?",
            rs -> rs.next() ? rs.getLong(1) : null, taskId);
        if (instanceId == null || !canReadFullInstance(instanceId, principal().userId())) {
            throw new HiddenResourceException("task not found");
        }
    }

    public void requireManageTask(long taskId, String permission) {
        Long instanceId = jdbcTemplate.query("SELECT proc_inst_id FROM t_task WHERE id = ?",
            rs -> rs.next() ? rs.getLong(1) : null, taskId);
        if (instanceId == null || !canReadInstance(instanceId, principal().userId())) {
            throw new HiddenResourceException("task not found");
        }
        requireManageInstance(instanceId, permission);
    }

    public void requireManageInstance(long instanceId, String permission) {
        PrincipalHolder.Principal principal = principal();
        InstanceAccess resource = instanceAccess(instanceId);
        if (resource == null || !canReadInstance(instanceId, principal.userId())) {
            throw new HiddenResourceException("instance not found");
        }
        if (principal.isAdmin()) {
            return;
        }
        requirePermission(permission);
        AuthzSnapshot snapshot = snapshot(principal.userId());
        if (!inDataScope(snapshot, permission, resource.startedBy(),
                resource.startedDepartmentId())) {
            throw new AccessDeniedException("instance is outside your management scope");
        }
    }

    public void requireReadableFormData(long formDataId) {
        PrincipalHolder.Principal principal = principal();
        if (!canReadFormData(formDataId, principal.userId())) {
            throw new HiddenResourceException("form data not found");
        }
    }

    public boolean canReadFormData(long formDataId, long userId) {
        FormDataAccess resource = formDataAccess(formDataId);
        if (resource == null) {
            return false;
        }
        AuthzSnapshot snapshot = snapshot(userId);
        if (snapshot.admin() || Objects.equals(resource.createdBy(), userId)) {
            return true;
        }
        return snapshot.permissions().contains(PermissionCodes.FORM_DATA_READ)
            && inDataScope(snapshot, PermissionCodes.FORM_DATA_READ,
                resource.createdBy(), resource.startedDepartmentId());
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
        for (RoleGrant grant : snapshot.permissionRoles().getOrDefault(permission, List.of())) {
            switch (grant.dataScope()) {
                case ALL -> result.addAll(jdbcTemplate.queryForList(
                    "SELECT id FROM t_department ORDER BY id", Long.class));
                case DEPARTMENT -> addIfPresent(result, snapshot.departmentId());
                case DEPARTMENT_AND_DESCENDANTS -> {
                    if (snapshot.departmentId() != null) {
                        result.addAll(jdbcTemplate.queryForList("""
                            SELECT child.id FROM t_department child
                            JOIN t_department parent ON parent.id = ?
                            WHERE child.path <@ parent.path
                            ORDER BY child.path
                            """, Long.class, snapshot.departmentId()));
                    }
                }
                case CUSTOM -> result.addAll(grant.customDepartmentIds());
                case SELF -> { }
            }
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

    boolean isParticipant(long instanceId, long userId) {
        Long count = jdbcTemplate.queryForObject("""
            SELECT (SELECT COUNT(*) FROM t_task
                    WHERE proc_inst_id = ?
                      AND ((assignee_id = ? AND status NOT IN ('SKIPPED', 'CANCELLED'))
                        OR approved_by = ?))
                 + (SELECT COUNT(*) FROM t_cc_record
                    WHERE proc_inst_id = ? AND recipient_id = ?)
            """, Long.class, instanceId, userId, userId, instanceId, userId);
        return count != null && count > 0;
    }

    boolean isReadableTaskAssignee(long instanceId, long userId) {
        Long count = jdbcTemplate.queryForObject("""
            SELECT (SELECT COUNT(*) FROM t_task
                    WHERE proc_inst_id = ?
                      AND ((assignee_id = ? AND status IN ('PENDING', 'CC'))
                        OR (approved_by = ? AND status IN ('APPROVED', 'REJECTED'))))
                 + (SELECT COUNT(*) FROM t_cc_record
                    WHERE proc_inst_id = ? AND recipient_id = ?)
            """, Long.class, instanceId, userId, userId, instanceId, userId);
        return count != null && count > 0;
    }

    InstanceAccess instanceAccess(long instanceId) {
        return jdbcTemplate.query("""
            SELECT pi.started_by, pi.started_dept_id
            FROM t_process_instance pi
            WHERE pi.id = ?
            """, rs -> rs.next() ? new InstanceAccess(
                nullableLong(rs, "started_by"),
                nullableLong(rs, "started_dept_id")) : null, instanceId);
    }

    private FormDataAccess formDataAccess(long formDataId) {
        return jdbcTemplate.query("""
            SELECT data.created_by, pi.started_dept_id,
                   submitter.dept_id AS submitter_dept_id
            FROM t_form_data data
            LEFT JOIN t_process_instance pi ON pi.form_data_id = data.id
            LEFT JOIN t_user submitter ON submitter.id = data.created_by
            WHERE data.id = ?
            ORDER BY pi.id DESC
            LIMIT 1
            """, rs -> rs.next() ? new FormDataAccess(
                nullableLong(rs, "created_by"),
                nullableLong(rs, "started_dept_id") != null
                    ? nullableLong(rs, "started_dept_id")
                    : nullableLong(rs, "submitter_dept_id")) : null, formDataId);
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
                    .add(new RoleGrant(role.roleId(), role.code(), DataScope.ALL, Set.of())));
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

    private PrincipalHolder.Principal principal() {
        return PrincipalHolder.current().orElseThrow(() ->
            new AccessDeniedException("authentication required"));
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

    // GrantRow / InstanceAccess 与下面对应的取数方法包内可见，供红线回归测试构造与打桩
    // （AuthorizationServiceTest 直接驱动 instanceVisibility 的被指派人分支）。
    private record CachedSnapshot(long version, AuthzSnapshot snapshot, long epoch) { }
    record RoleBase(long roleId, String code) { }
    record GrantRow(String permissionCode, String scopeOverride) { }
    // 与 GrantRow 一样放宽到包内可见：AuthorizationServiceTest 需要构造它们来复现缓存竞态。
    record UserState(long userId, String username, String displayName, String status,
                             long authzVersion, Long departmentId) { }
    record InstanceAccess(Long startedBy, Long startedDepartmentId) { }
    private record FormDataAccess(Long createdBy, Long startedDepartmentId) { }

    public record RoleGrant(long roleId, String code, DataScope dataScope,
                            Set<Long> customDepartmentIds) { }

    public record AuthzSnapshot(long userId, Long departmentId, boolean admin,
                                Set<String> roleCodes, Set<String> permissions,
                                Map<String, List<RoleGrant>> permissionRoles) { }

    public enum InstanceVisibility { NONE, SUMMARY, FULL }
}

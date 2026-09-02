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

    public Optional<PrincipalHolder.Principal> principalForRequest(long userId, UUID sessionId) {
        UserState state = userState(userId);
        if (state == null || !"ACTIVE".equals(state.status())) {
            cache.remove(userId);
            return Optional.empty();
        }
        CachedSnapshot cached = cache.get(userId);
        AuthzSnapshot snapshot;
        if (cached != null && cached.version() == state.authzVersion()) {
            snapshot = cached.snapshot();
        } else {
            snapshot = loadSnapshot(state);
            cache.put(userId, new CachedSnapshot(state.authzVersion(), snapshot));
        }
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
        CachedSnapshot cached = cache.get(userId);
        if (cached != null && cached.version() == state.authzVersion()) {
            return cached.snapshot();
        }
        AuthzSnapshot snapshot = loadSnapshot(state);
        cache.put(userId, new CachedSnapshot(state.authzVersion(), snapshot));
        return snapshot;
    }

    public void evict(long userId) {
        cache.remove(userId);
    }

    public void requirePermission(String permission) {
        PrincipalHolder.Principal principal = principal();
        if (!principal.isAdmin() && !principal.permissions().contains(permission)) {
            throw new AuthorizationFailureException("MISSING_PERMISSION",
                "missing permission: " + permission);
        }
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

    public void requireFormAction(long formId, String permission) {
        PrincipalHolder.Principal principal = principal();
        if (principal.isAdmin()) {
            return;
        }
        requirePermission(permission);
        if (!hasFormGrant(formId, principal.userId())) {
            throw new HiddenResourceException("form not found");
        }
    }

    public void requireFormManagementScope(long formId, String permission) {
        requireFormAction(formId, permission);
        if (isAdmin()) return;
        FormManagementAccess resource = jdbcTemplate.query("""
            SELECT form.created_by, creator.dept_id
            FROM t_form_definition form
            LEFT JOIN t_user creator ON creator.id = form.created_by
            WHERE form.id = ? AND form.deleted = 0
            """, rs -> rs.next() ? new FormManagementAccess(
                nullableLong(rs, "created_by"), nullableLong(rs, "dept_id")) : null, formId);
        if (resource == null) throw new HiddenResourceException("form not found");
        if (!inCurrentDataScope(permission, resource.createdBy(), resource.departmentId())) {
            throw new AuthorizationFailureException("OUTSIDE_DATA_SCOPE",
                "form is outside the permitted data scope");
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
        requireFormAction(formId, PermissionCodes.FORM_RUNTIME_READ);
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
            && hasFormGrant(resource.formDefId(), userId)
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
        if (!hasFormGrant(resource.formDefId(), principal.userId())
            || !inDataScope(snapshot, permission, resource.startedBy(),
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
            && hasFormGrant(resource.formDefId(), userId)
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

    private InstanceAccess instanceAccess(long instanceId) {
        return jdbcTemplate.query("""
            SELECT pi.started_by, pi.started_dept_id, data.form_def_id
            FROM t_process_instance pi
            JOIN t_form_data data ON data.id = pi.form_data_id
            WHERE pi.id = ?
            """, rs -> rs.next() ? new InstanceAccess(
                nullableLong(rs, "started_by"),
                nullableLong(rs, "started_dept_id"),
                rs.getLong("form_def_id")) : null, instanceId);
    }

    private FormDataAccess formDataAccess(long formDataId) {
        return jdbcTemplate.query("""
            SELECT data.created_by, data.form_def_id, pi.started_dept_id,
                   submitter.dept_id AS submitter_dept_id
            FROM t_form_data data
            LEFT JOIN t_process_instance pi ON pi.form_data_id = data.id
            LEFT JOIN t_user submitter ON submitter.id = data.created_by
            WHERE data.id = ?
            ORDER BY pi.id DESC
            LIMIT 1
            """, rs -> rs.next() ? new FormDataAccess(
                nullableLong(rs, "created_by"),
                rs.getLong("form_def_id"),
                nullableLong(rs, "started_dept_id") != null
                    ? nullableLong(rs, "started_dept_id")
                    : nullableLong(rs, "submitter_dept_id")) : null, formDataId);
    }

    private AuthzSnapshot loadSnapshot(UserState user) {
        List<RoleGrant> roles = jdbcTemplate.query("""
            SELECT role.id, role.code, role.data_scope
            FROM t_user_role ur
            JOIN t_role role ON role.id = ur.role_id
            WHERE ur.user_id = ? AND role.enabled = true
            ORDER BY role.id
            """, (rs, row) -> new RoleGrant(
                rs.getLong("id"),
                rs.getString("code"),
                DataScope.valueOf(rs.getString("data_scope")),
                customDepartments(rs.getLong("id"))), user.userId());
        boolean admin = roles.stream().anyMatch(role -> "admin".equals(role.code()));

        Map<String, List<RoleGrant>> permissionRoles = new HashMap<>();
        for (RoleGrant role : roles) {
            List<String> rolePermissions = admin && "admin".equals(role.code())
                ? jdbcTemplate.queryForList("SELECT code FROM t_permission ORDER BY sort_order, code",
                    String.class)
                : jdbcTemplate.queryForList("""
                    SELECT permission_code FROM t_role_permission
                    WHERE role_id = ? ORDER BY permission_code
                    """, String.class, role.roleId());
            for (String permission : rolePermissions) {
                permissionRoles.computeIfAbsent(permission, key -> new ArrayList<>()).add(role);
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

    private Set<Long> customDepartments(long roleId) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(jdbcTemplate.queryForList(
            "SELECT department_id FROM t_role_department WHERE role_id = ? ORDER BY department_id",
            Long.class, roleId)));
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

    private record CachedSnapshot(long version, AuthzSnapshot snapshot) { }
    private record UserState(long userId, String username, String displayName, String status,
                             long authzVersion, Long departmentId) { }
    private record InstanceAccess(Long startedBy, Long startedDepartmentId, Long formDefId) { }
    private record FormDataAccess(Long createdBy, Long formDefId, Long startedDepartmentId) { }
    private record FormManagementAccess(Long createdBy, Long departmentId) { }

    public record RoleGrant(long roleId, String code, DataScope dataScope,
                            Set<Long> customDepartmentIds) { }

    public record AuthzSnapshot(long userId, Long departmentId, boolean admin,
                                Set<String> roleCodes, Set<String> permissions,
                                Map<String, List<RoleGrant>> permissionRoles) { }

    public enum InstanceVisibility { NONE, SUMMARY, FULL }
}

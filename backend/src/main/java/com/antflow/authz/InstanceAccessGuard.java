package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService.AuthzSnapshot;
import com.antflow.authz.AuthorizationService.InstanceVisibility;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 实例级可见性：某个流程实例（或表单数据）对某个用户是"不可见 / 摘要 / 全量"。
 *
 * <p>从 {@link AuthorizationService} 搬出（2026-10-22 第五刀）。它与表单级授权（上一刀）的区别在于
 * **真源是运行时数据**：{@code t_process_instance} 的归属快照、{@code t_task} 的指派/审批记录、
 * {@code t_cc_record} 的抄送。这些行会随流程推进而变化，判定必须是"读当前库"，不能缓存。
 *
 * <p><b>可见性是"并集"，不是"数据范围的子集"——这是最容易改坏的一处：</b>
 * <ol>
 *   <li>{@code workflow:task:read} + 我是被指派人 → 全量可见，<b>与数据范围无关</b>。
 *       审批人靠这条路径看待办；改成"再按数据范围裁一刀"，审批人会立刻看不到自己的单子
 *       （{@code AuthorizationServiceTest} 的红线用例就钉这个）。</li>
 *   <li>发起人看自己的实例 → 全量；参与者/抄送人 → 摘要。顺序不能调换，SUMMARY 分支必须最后。</li>
 *   <li>{@code companyId == null || companyInferred} → 直接 {@code NONE}（fail-closed）。
 *       归属推断出来的实例宁可看不见，也不能按"猜的公司"放行。</li>
 * </ol>
 */
@Component
public class InstanceAccessGuard {
    private final JdbcTemplate jdbcTemplate;
    private final AuthzSnapshotStore snapshots;
    private final CapabilityResolver capabilities;
    private final DataScopeResolver dataScopes;

    InstanceAccessGuard(JdbcTemplate jdbcTemplate, AuthzSnapshotStore snapshots,
                        CapabilityResolver capabilities, DataScopeResolver dataScopes) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshots = snapshots;
        this.capabilities = capabilities;
        this.dataScopes = dataScopes;
    }

    public void requireReadableInstance(long instanceId) {
        PrincipalHolder.Principal principal = capabilities.principal();
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
        AuthzSnapshot snapshot = snapshots.snapshot(userId);
        if (snapshot.admin()) {
            return InstanceVisibility.FULL;
        }
        if (resource.companyId() == null || resource.companyInferred()) {
            return InstanceVisibility.NONE;
        }
        if (Objects.equals(resource.startedBy(), userId)) return InstanceVisibility.FULL;
        if (snapshot.permissions().contains(PermissionCodes.WORKFLOW_TASK_READ)
            && isReadableTaskAssignee(instanceId, userId)) {
            return InstanceVisibility.FULL;
        }
        if (snapshot.permissions().contains(PermissionCodes.WORKFLOW_INSTANCE_READ)
            && dataScopes.inCompanyDataScope(snapshot, PermissionCodes.WORKFLOW_INSTANCE_READ,
                resource.startedBy(), resource.startedDepartmentId(), resource.companyId())) {
            return InstanceVisibility.FULL;
        }
        return isParticipant(instanceId, userId)
            ? InstanceVisibility.SUMMARY : InstanceVisibility.NONE;
    }

    public void requireReadableTask(long taskId) {
        Long instanceId = jdbcTemplate.query("SELECT proc_inst_id FROM t_task WHERE id = ?",
            rs -> rs.next() ? rs.getLong(1) : null, taskId);
        if (instanceId == null || !canReadFullInstance(instanceId, capabilities.principal().userId())) {
            throw new HiddenResourceException("task not found");
        }
    }

    public void requireManageTask(long taskId, String permission) {
        Long instanceId = jdbcTemplate.query("SELECT proc_inst_id FROM t_task WHERE id = ?",
            rs -> rs.next() ? rs.getLong(1) : null, taskId);
        if (instanceId == null || !canReadInstance(instanceId, capabilities.principal().userId())) {
            throw new HiddenResourceException("task not found");
        }
        requireManageInstance(instanceId, permission);
    }

    public void requireManageInstance(long instanceId, String permission) {
        PrincipalHolder.Principal principal = capabilities.principal();
        InstanceAccess resource = instanceAccess(instanceId);
        if (resource == null || !canReadInstance(instanceId, principal.userId())) {
            throw new HiddenResourceException("instance not found");
        }
        if (principal.isAdmin()) {
            return;
        }
        capabilities.requirePermission(permission);
        AuthzSnapshot snapshot = snapshots.snapshot(principal.userId());
        if (!dataScopes.inCompanyDataScope(snapshot, permission, resource.startedBy(),
                resource.startedDepartmentId(), resource.companyId())) {
            throw new AccessDeniedException("instance is outside your management scope");
        }
    }

    public void requireReadableFormData(long formDataId) {
        PrincipalHolder.Principal principal = capabilities.principal();
        if (!canReadFormData(formDataId, principal.userId())) {
            throw new HiddenResourceException("form data not found");
        }
    }

    public boolean canReadFormData(long formDataId, long userId) {
        FormDataAccess resource = formDataAccess(formDataId);
        if (resource == null) {
            return false;
        }
        AuthzSnapshot snapshot = snapshots.snapshot(userId);
        if (snapshot.admin()) {
            return true;
        }
        if (resource.companyId() == null || resource.companyInferred()) return false;
        if (Objects.equals(resource.createdBy(), userId)) return true;
        return snapshot.permissions().contains(PermissionCodes.FORM_DATA_READ)
            && dataScopes.inCompanyDataScope(snapshot, PermissionCodes.FORM_DATA_READ,
                resource.createdBy(), resource.startedDepartmentId(), resource.companyId());
    }

    /** Assigned actions retain their existing actor checks, but cannot operate on uncertain attribution. */
    public void requireConfirmedInstance(long instanceId) {
        if (PrincipalHolder.current().isEmpty()) return; // Explicit internal worker context.
        InstanceAccess access = instanceAccess(instanceId);
        if (access == null || (!capabilities.isAdmin()
            && (access.companyId() == null || access.companyInferred()))) {
            throw new HiddenResourceException("instance not found");
        }
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

    /**
     * 已办过的审批人也算"可读被指派任务"，但只认 PENDING/CC 的指派与 APPROVED/REJECTED 的审批——
     * SKIPPED（被跳过）与 delegated（已转交）不算，否则"我从没处理过"的人也能看到全量。
     */
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
            SELECT pi.started_by, pi.started_dept_id, pi.company_id, pi.company_inferred
            FROM t_process_instance pi
            WHERE pi.id = ?
            """, rs -> rs.next() ? new InstanceAccess(
                nullableLong(rs, "started_by"),
                nullableLong(rs, "started_dept_id"),
                nullableLong(rs, "company_id"), rs.getBoolean("company_inferred")) : null, instanceId);
    }

    private FormDataAccess formDataAccess(long formDataId) {
        return jdbcTemplate.query("""
            SELECT data.created_by, data.company_id, data.company_inferred, pi.started_dept_id,
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
                    : nullableLong(rs, "submitter_dept_id"),
                nullableLong(rs, "company_id"), rs.getBoolean("company_inferred")) : null, formDataId);
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    // InstanceAccess / instanceAccess 与 isParticipant / isReadableTaskAssignee 包内可见：
    // 红线回归测试（AuthorizationServiceTest）直接构造与打桩这几个点，验证"被指派人可见性"。
    record InstanceAccess(Long startedBy, Long startedDepartmentId, Long companyId,
                          boolean companyInferred) { }
    private record FormDataAccess(Long createdBy, Long startedDepartmentId, Long companyId,
                                  boolean companyInferred) { }
}

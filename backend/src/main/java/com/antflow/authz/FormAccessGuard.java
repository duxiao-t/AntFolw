package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService.AuthzSnapshot;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 表单级授权：谁能"用"某张已发布表单、谁能"维护"它的配置。
 *
 * <p>从 {@link AuthorizationService} 搬出（2026-10-22 第四刀）。它与实例可见性（下一刀）虽然都叫
 * "资源授权"，但数据来源与失效语义完全不同：这里的真源是 {@code t_form_resource_grant} 与
 * {@code t_form_maintainer}（人工配置，改一次要 evict），实例可见性则来自流程运行时数据。
 *
 * <p><b>两个高频误解，改之前先读：</b>
 * <ol>
 *   <li>"使用授权"（{@link #hasFormGrant}）与"维护人"（{@link #hasFormMaintainer}）是两条独立路径，
 *       互不蕴含。只有使用授权的人不能改表单配置——{@code AuthorizationServiceTest} 有专门用例。</li>
 *   <li>{@link #usableFormIds} / {@link #maintainableFormIds} 返回 {@code Optional.empty()} 表示
 *       "**不需要过滤**"（管理员），不是"一个都没有"。调用方若把它当空集处理，管理员会看到空列表。</li>
 * </ol>
 *
 * <p>未授权一律抛 {@link HiddenResourceException}（"not found"）而不是 403：对外不泄露表单是否存在。
 */
@Component
public class FormAccessGuard {
    private static final String FORM_SUBJECT_MATCH = """
        ((grant_row.subject_type = 'USER' AND grant_row.subject_id = ?)
         OR (grant_row.subject_type = 'ROLE' AND grant_row.subject_id IN (
             SELECT ur.role_id FROM t_user_role ur
             JOIN t_role role ON role.id = ur.role_id AND role.enabled = true
             WHERE ur.user_id = ?))
         OR (grant_row.subject_type = 'DEPARTMENT' AND EXISTS (
             SELECT 1 FROM t_user grant_user
             JOIN t_department user_department ON user_department.id = grant_user.dept_id
             JOIN t_department grant_department ON grant_department.id = grant_row.subject_id
             WHERE grant_user.id = ?
               AND user_department.path <@ grant_department.path)))
        """;

    private final JdbcTemplate jdbcTemplate;
    private final AuthzSnapshotStore snapshots;
    private final CapabilityResolver capabilities;

    FormAccessGuard(JdbcTemplate jdbcTemplate, AuthzSnapshotStore snapshots,
                    CapabilityResolver capabilities) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshots = snapshots;
        this.capabilities = capabilities;
    }

    /**
     * 单公司模式下的表单使用授权：只要有一条命中本人的授权行即可。
     *
     * <p>以前还要叠加"同一家公司也有授权"这一层（company_grant EXISTS），
     * 公司不再是隔离维度后这层恒真，已移除。
     */
    public boolean hasFormGrant(long formId, long userId) {
        if (snapshots.snapshot(userId).admin()) return true;
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM t_form_resource_grant grant_row
            WHERE grant_row.form_def_id = ? AND
            """ + FORM_SUBJECT_MATCH + """
            """, Long.class, formId, userId, userId, userId);
        return count != null && count > 0;
    }


    /**
     * 该用户有使用授权的表单 id 集合，用于列表类的批量过滤。
     * 返回空表示"不需要过滤"（管理员），调用方直接放行——不是"一个都没有"。
     */
    public Optional<Set<Long>> usableFormIds(long userId) {
        AuthzSnapshot snapshot = snapshots.snapshot(userId);
        if (snapshot.admin()) {
            return Optional.empty();
        }
        return Optional.of(new java.util.HashSet<>(jdbcTemplate.queryForList("""
            SELECT DISTINCT grant_row.form_def_id FROM t_form_resource_grant grant_row
            WHERE
            """ + FORM_SUBJECT_MATCH, Long.class, userId, userId, userId)));
    }


    /** 已发布表单的使用入口：原子能力与使用范围必须同时满足。 */
    public void requireFormUse(long formId) {
        PrincipalHolder.Principal principal = capabilities.principal();
        if (principal.isAdmin()) {
            return;
        }
        capabilities.requirePermission(PermissionCodes.FORM_RUNTIME_READ);
        if (!hasFormGrant(formId, principal.userId())) {
            throw new HiddenResourceException("form not found");
        }
    }


    public boolean canUseForm(long formId, long userId) {
        AuthzSnapshot snapshot = snapshots.snapshot(userId);
        return snapshot.admin()
            || (snapshot.permissions().contains(PermissionCodes.FORM_RUNTIME_READ)
                && hasFormGrant(formId, userId));
    }

    public boolean hasFormMaintainer(long formId, long userId) {
        AuthzSnapshot snapshot = snapshots.snapshot(userId);
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
        if (snapshots.snapshot(userId).admin()) {
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
        PrincipalHolder.Principal principal = capabilities.principal();
        if (principal.isAdmin()) {
            return;
        }
        capabilities.requirePermission(permission);
        if (!hasFormMaintainer(formId, principal.userId())) {
            throw new HiddenResourceException("form not found");
        }
    }

    public void requireFormMaintenanceAny(long formId, String... permissions) {
        PrincipalHolder.Principal principal = capabilities.principal();
        if (principal.isAdmin()) {
            return;
        }
        capabilities.requireAnyPermission(permissions);
        if (!hasFormMaintainer(formId, principal.userId())) {
            throw new HiddenResourceException("form not found");
        }
    }

    /**
     * 修正已提交表单数据的表单级校验：管理员放行，其余必须"持有修正能力 **且** 是维护者"。
     *
     * <p>这里**刻意不叠加** {@code form:data:read} 的 SELF 数据范围：数据范围只对读生效，
     * 而维护者的职责恰恰是替别人收拾数据，叠加 SELF 会让它只能改自己提交的单据，功能等于不可用。
     * 数据级只剩"记录存在"校验（{@code InstanceAccessGuard.canCorrectFormData}）——
     * 单公司改造后公司不再是隔离维度，横向风险靠 CRITICAL 审计 + 修正台账 + 通知提交人兜。
     *
     * <p>未授权时抛 HiddenResourceException（资源不存在）而不是 403，
     * 与 {@link #requireFormMaintenance} 保持一致：不泄露表单是否存在。
     */
    public void requireFormCorrection(long formId) {
        PrincipalHolder.Principal principal = capabilities.principal();
        if (principal.isAdmin()) {
            return;
        }
        capabilities.requirePermission(PermissionCodes.FORM_DATA_CORRECT);
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
}

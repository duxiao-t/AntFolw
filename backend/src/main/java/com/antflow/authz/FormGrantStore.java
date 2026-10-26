package com.antflow.authz;

import static com.antflow.authz.FormGrantSupport.grantUser;
import static com.antflow.authz.FormGrantSupport.nullableLong;

import com.antflow.authz.FormGrantService.FormGrantDto;
import com.antflow.authz.FormGrantService.GrantDepartment;
import com.antflow.authz.FormGrantService.GrantRole;
import com.antflow.authz.FormGrantService.GrantUser;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 「表单使用授权」真源 {@code t_form_resource_grant} 的读写，以及表单级 {@code authz_version} 的锁与递增。
 *
 * <p>从 {@link FormGrantService} 搬出（2026-10-22 类体积治理第三刀）。它与维护人
 * （{@link FormMaintainerStore}，真源 {@code t_form_maintainer}）是**两套独立真源**，
 * 不要为了"少一个类"把它们合并：{@code FormAccessGuard} 读这两张表时走的也是两条独立路径
 * （使用授权 ≠ 维护人，互不蕴含），合并会让"读写口径一致"这件事失去锚点。
 *
 * <p><b>三条不能动的语义：</b>
 * <ol>
 *   <li>{@link #bumpAuthzVersion} 是乐观锁：{@code WHERE authz_version = ?} 更新失败必须由调用方
 *       抛 {@code *_VERSION_CONFLICT}。授权与维护人共用这一个版本号，所以谁改都要递增。</li>
 *   <li>{@link #lockFormAuthorization} 必须在**权限复核之前**拿到行锁，且复核要在锁内再做一次：
 *       否则两次校验之间表单可能被转交，会出现"按旧维护人身份写入新授权"。</li>
 *   <li>非管理员只能替换 {@code USER}/{@code DEPARTMENT}，{@code ROLE}/{@code COMPANY} 的授权行
 *       连删都不能删（DELETE 的 subject_type 集合由 {@code admin} 决定）——否则非管理员可以用
 *       "清空重填"绕过角色授权的管理边界。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class FormGrantStore {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;

    FormGrantDto get(long formId) {
        authorizationService.requireFormMaintenance(formId,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        Integer version = version(formId);
        List<Long> userIds = jdbcTemplate.queryForList("""
                SELECT subject_id FROM t_form_resource_grant
                WHERE form_def_id = ? AND subject_type = 'USER' ORDER BY subject_id
                """, Long.class, formId);
        List<Long> roleIds = jdbcTemplate.queryForList("""
                SELECT subject_id FROM t_form_resource_grant
                WHERE form_def_id = ? AND subject_type = 'ROLE' ORDER BY subject_id
                """, Long.class, formId);
        List<Long> departmentIds = jdbcTemplate.queryForList("""
                SELECT subject_id FROM t_form_resource_grant
                WHERE form_def_id = ? AND subject_type = 'DEPARTMENT' ORDER BY subject_id
                """, Long.class, formId);
        return new FormGrantDto(version, userIds, roleIds, departmentIds,
            selectedUsers(formId), selectedRoles(formId), selectedDepartments(formId));
    }

    int version(long formId) {
        Integer version = jdbcTemplate.query("""
            SELECT authz_version FROM t_form_definition WHERE id = ? AND deleted = 0
            """, rs -> rs.next() ? rs.getInt(1) : null, formId);
        if (version == null) {
            throw new HiddenResourceException("form not found");
        }
        return version;
    }

    void lockFormAuthorization(long formId) {
        Long id = jdbcTemplate.query("""
            SELECT id FROM t_form_definition WHERE id = ? AND deleted = 0 FOR UPDATE
            """, rs -> rs.next() ? rs.getLong(1) : null, formId);
        if (id == null) {
            throw new HiddenResourceException("form not found");
        }
    }

    /** 乐观锁递增：返回 0 表示版本已被别人改过，调用方按各自的错误码报冲突。 */
    int bumpAuthzVersion(long formId, int expectedVersion) {
        return jdbcTemplate.update("""
            UPDATE t_form_definition SET authz_version = authz_version + 1
            WHERE id = ? AND deleted = 0 AND authz_version = ?
            """, formId, expectedVersion);
    }

    void deleteSubjects(long formId, boolean admin) {
        jdbcTemplate.update(admin
            ? "DELETE FROM t_form_resource_grant WHERE form_def_id = ? AND subject_type IN ('USER', 'ROLE', 'DEPARTMENT')"
            : "DELETE FROM t_form_resource_grant WHERE form_def_id = ? AND subject_type IN ('USER', 'DEPARTMENT')",
            formId);
    }



    void insertUser(long formId, long userId, long actorId) {
        jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'USER', ?, ?)
            """, formId, userId, actorId);
    }

    void insertRole(long formId, long roleId, long actorId) {
        jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'ROLE', ?, ?)
            """, formId, roleId, actorId);
    }

    void insertDepartment(long formId, long departmentId, long actorId) {
        jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'DEPARTMENT', ?, ?)
            """, formId, departmentId, actorId);
    }

    /** 非管理员不能改角色授权，写完之后要按**库里的实际值**回报，不能回显请求里的 roleIds。 */
    List<Long> roleSubjectIds(long formId) {
        return jdbcTemplate.queryForList("""
            SELECT subject_id FROM t_form_resource_grant
            WHERE form_def_id = ? AND subject_type = 'ROLE' ORDER BY subject_id
            """, Long.class, formId);
    }

    List<GrantUser> selectedUsers(long formId) {
        return jdbcTemplate.query("""
            SELECT user_row.id, user_row.username, user_row.display_name,
                   user_row.employee_no, user_row.dept_id, department.name AS department_name
            FROM t_form_resource_grant form_grant
            JOIN t_user user_row ON user_row.id = form_grant.subject_id
            LEFT JOIN t_department department ON department.id = user_row.dept_id
            WHERE form_grant.form_def_id = ? AND form_grant.subject_type = 'USER'
            ORDER BY user_row.display_name, user_row.id
            """, (rs, row) -> grantUser(rs), formId);
    }

    List<GrantRole> selectedRoles(long formId) {
        return jdbcTemplate.query("""
            SELECT role.id, role.code, role.name
            FROM t_form_resource_grant form_grant
            JOIN t_role role ON role.id = form_grant.subject_id
            WHERE form_grant.form_def_id = ? AND form_grant.subject_type = 'ROLE'
            ORDER BY role.name, role.id
            """, (rs, row) -> new GrantRole(rs.getLong("id"), rs.getString("code"),
                rs.getString("name")), formId);
    }

    List<GrantDepartment> selectedDepartments(long formId) {
        return jdbcTemplate.query("""
            SELECT department.id, department.parent_id, department.name
            FROM t_form_resource_grant form_grant
            JOIN t_department department ON department.id = form_grant.subject_id
            WHERE form_grant.form_def_id = ? AND form_grant.subject_type = 'DEPARTMENT'
            ORDER BY department.path, department.id
            """, (rs, row) -> new GrantDepartment(rs.getLong("id"),
                nullableLong(rs, "parent_id"), rs.getString("name")), formId);
    }


}

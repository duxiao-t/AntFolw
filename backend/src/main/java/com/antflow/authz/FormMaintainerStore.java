package com.antflow.authz;

import static com.antflow.authz.FormGrantSupport.grantUser;

import com.antflow.authz.FormGrantService.FormMaintainerDto;
import com.antflow.authz.FormGrantService.GrantUser;
import com.antflow.engine.BizException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 「表单维护人」真源 {@code t_form_maintainer} 的读写。
 *
 * <p>从 {@link FormGrantService} 搬出（2026-10-22 类体积治理第三刀）。它与使用授权
 * （{@link FormGrantStore}）是**两套独立真源**，互不蕴含：有使用授权的人能填表，只有维护人能改表单配置。
 * 两条路径唯一共享的是表单行上的 {@code authz_version} 乐观锁（在 {@link FormGrantStore} 里）。
 *
 * <p><b>两条不能动的语义：</b>
 * <ol>
 *   <li>{@link #validateMaintainers} 用 {@code FOR SHARE} 锁用户行：并发停用账号时不能出现
 *       "校验时 ACTIVE、写入后已停用"的维护人。只有 ACTIVE 才能当维护人，错误码
 *       {@code FORM_MAINTAINER_INVALID}。</li>
 *   <li>{@link #selectedMaintainers} 只回 ACTIVE 用户：历史维护人被停用后，列表里不再出现，
 *       但行本身不删（审计要留痕）。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
class FormMaintainerStore {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;
    private final FormGrantStore grantStore;

    FormMaintainerDto getMaintainers(long formId) {
        authorizationService.requireFormMaintenance(formId,
            PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        List<GrantUser> users = selectedMaintainers(formId);
        return new FormMaintainerDto(grantStore.version(formId),
            users.stream().map(GrantUser::id).toList(), users);
    }

    void validateMaintainers(Set<Long> userIds) {
        for (Long userId : userIds) {
            String status = jdbcTemplate.query("""
                SELECT status FROM t_user WHERE id = ? FOR SHARE
                """, rs -> rs.next() ? rs.getString(1) : null, userId);
            if (!"ACTIVE".equals(status)) {
                throw new BizException("FORM_MAINTAINER_INVALID",
                    "maintainer must be an active user");
            }
        }
    }

    void deleteMaintainers(long formId) {
        jdbcTemplate.update("DELETE FROM t_form_maintainer WHERE form_def_id = ?", formId);
    }

    void insertMaintainer(long formId, long userId, long actorId) {
        jdbcTemplate.update("""
            INSERT INTO t_form_maintainer(form_def_id, user_id, granted_by)
            VALUES (?, ?, ?)
            """, formId, userId, actorId);
    }

    List<GrantUser> selectedMaintainers(long formId) {
        return jdbcTemplate.query("""
            SELECT user_row.id, user_row.username, user_row.display_name,
                   user_row.employee_no, user_row.dept_id, department.name AS department_name
            FROM t_form_maintainer maintainer
            JOIN t_user user_row ON user_row.id = maintainer.user_id
            LEFT JOIN t_department department ON department.id = user_row.dept_id
            WHERE maintainer.form_def_id = ? AND user_row.status = 'ACTIVE'
            ORDER BY user_row.display_name, user_row.id
            """, (rs, row) -> grantUser(rs), formId);
    }
}

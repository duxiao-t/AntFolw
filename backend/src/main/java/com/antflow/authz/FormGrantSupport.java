package com.antflow.authz;

import com.antflow.authz.FormGrantService.GrantDepartment;
import com.antflow.authz.FormGrantService.GrantRole;
import com.antflow.authz.FormGrantService.GrantUser;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 表单授权的三个 JDBC 小工具（行映射与占位符）。
 *
 * <p>从 {@link FormGrantService} 搬出（2026-10-22 类体积治理第三刀）。三个协作者都要把
 * {@code t_form_resource_grant}/{@code t_form_maintainer} 的联表结果映射成 {@code GrantUser}，
 * 放在任何一边都会让另外两边反向引用它，所以单独放这里。
 */
final class FormGrantSupport {
    private FormGrantSupport() { }

    static GrantUser grantUser(ResultSet rs) throws SQLException {
        return new GrantUser(rs.getLong("id"), rs.getString("username"),
            rs.getString("display_name"), rs.getString("employee_no"),
            nullableLong(rs, "dept_id"), rs.getString("department_name"));
    }

    static Long nullableLong(ResultSet resultSet, String column)
            throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}

package com.antflow.auth;

import com.antflow.authz.AuthenticatedOnly;
import com.antflow.org.UserService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {
    private final JdbcTemplate jdbc;
    private final UserService userService;

    @GetMapping("/profile")
    @AuthenticatedOnly
    public AccountProfile profile() {
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        return jdbc.query("""
            SELECT user_row.id, user_row.username, user_row.display_name,
                   user_row.employee_no, user_row.position, user_row.email, user_row.phone,
                   user_row.dept_id, department.name AS department_name
            FROM t_user user_row
            LEFT JOIN t_department department ON department.id = user_row.dept_id
            WHERE user_row.id = ? AND user_row.status = 'ACTIVE'
            """, rs -> rs.next() ? new AccountProfile(
                rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("employee_no"), rs.getString("department_name"),
                rs.getObject("dept_id", Long.class), rs.getString("position"),
                rs.getString("email"), rs.getString("phone"), principal.roles()) : null,
            principal.userId());
    }

    public record AccountProfile(long id, String username, String displayName,
                                 String employeeNo, String departmentName, Long departmentId,
                                 String position, String email, String phone, Set<String> roles) { }

    /** 自助修改密码。改成功后其它设备下线，当前会话保留。 */
    @PostMapping("/password")
    @AuthenticatedOnly
    public void changePassword(@RequestBody PasswordChange body) {
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        userService.changeOwnPassword(principal.userId(), principal.sessionId(),
            body == null ? null : body.currentPassword(),
            body == null ? null : body.newPassword());
    }

    public record PasswordChange(String currentPassword, String newPassword) { }
}

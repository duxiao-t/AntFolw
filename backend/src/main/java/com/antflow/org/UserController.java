package com.antflow.org;

import com.antflow.authz.AuthorizationService;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserMapper userMapper;
    private final UserService userService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    @GetMapping
    public List<User> list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) Long deptId) {
        return userService.listAuthorized(keyword, deptId);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_WRITE);
        String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
        if (password == null) {
            throw new BizException("PASSWORD_REQUIRED", "请设置初始密码");
        }
        User u = toUser(body);
        List<Long> roleIds = toLongList(body.get("roleIds"));
        Long id = auditService.execute(() -> userService.create(u, roleIds, password), createdId ->
            auditService.success("org.user.create", "USER", createdId,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", changedFields(body, List.of("employeeNo", "username",
                    "displayName", "email", "phone", "position", "gender", "deptId",
                    "roleIds", "status"))),
                Map.of("roleCount", roleIds.size())));
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    public User update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_WRITE);
        return auditService.execute(() -> userService.update(id, body), user ->
            auditService.success("org.user.update", "USER", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", changedFields(body, List.of("employeeNo", "username",
                    "displayName", "email", "phone", "position", "gender", "deptId",
                    "status"))),
                Map.of("statusChanged", body.containsKey("status"),
                    "departmentChanged", body.containsKey("deptId"))));
    }

    @PutMapping("/{id}/roles")
    public void setRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        authorizationService.requireAdmin();
        userService.setRoles(id, roleIds);
    }

    @PutMapping("/{id}/password")
    public void resetPassword(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        authorizationService.requireAdmin();
        String password = body.get("newPassword") == null
            ? null : String.valueOf(body.get("newPassword"));
        auditService.execute(() -> userService.resetPassword(id, password),
            () -> auditService.success("org.user.password.reset", "USER", id,
                AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("password")), Map.of("sessionsRevoked", true)));
    }

    @PostMapping("/import")
    public ImportResult importUsers(@RequestBody ImportRequest request) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_WRITE);
        List<ImportFailure> failures = new java.util.ArrayList<>();
        int successCount = 0;
        List<Map<String, Object>> rows = request == null || request.users() == null
            ? List.of() : request.users();
        for (int index = 0; index < rows.size(); index++) {
            int rowNumber = index + 2;
            try {
                User user = toUser(rows.get(index));
                Long id = auditService.execute(
                    () -> userService.create(user, List.of(), UserService.DEFAULT_IMPORTED_PASSWORD),
                    createdId -> auditService.success("org.user.import", "USER", createdId,
                        AuditService.RiskLevel.HIGH,
                        Map.of("changedFields", List.of("profile", "password")),
                        Map.of("row", rowNumber)));
                if (id != null) successCount++;
            } catch (RuntimeException exception) {
                failures.add(new ImportFailure(rowNumber, exception.getMessage()));
            }
        }
        return new ImportResult(successCount, failures.size(),
            UserService.DEFAULT_IMPORTED_PASSWORD, failures);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_WRITE);
        auditService.execute(() -> userService.delete(id),
            () -> auditService.success("org.user.delete", "USER", id,
                AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("deleted")), Map.of()));
    }

    @SuppressWarnings("unchecked")
    private static List<Long> toLongList(Object o) {
        if (o == null) return List.of();
        return ((List<Number>) o).stream().map(Number::longValue).toList();
    }

    private static List<String> changedFields(Map<String, Object> body, List<String> allowed) {
        return allowed.stream().filter(body::containsKey).toList();
    }

    private static User toUser(Map<String, Object> body) {
        User user = new User();
        user.setEmployeeNo((String) body.get("employeeNo"));
        user.setUsername((String) body.get("username"));
        user.setDisplayName((String) body.get("displayName"));
        user.setEmail((String) body.get("email"));
        user.setPhone((String) body.get("phone"));
        user.setPosition((String) body.get("position"));
        user.setGender((String) body.get("gender"));
        if (body.get("deptId") != null) {
            user.setDeptId(((Number) body.get("deptId")).longValue());
        }
        return user;
    }

    public record ImportRequest(List<Map<String, Object>> users) { }
    public record ImportFailure(int row, String message) { }
    public record ImportResult(int successCount, int failedCount, String defaultPassword,
                               List<ImportFailure> failures) { }
}

package com.antflow.org;

import com.antflow.authz.AuthorizationService;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import com.antflow.authz.PermissionCodes;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserMapper userMapper;
    private final UserService userService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_USER_READ + "')")
    public List<User> list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) Long deptId,
                           @RequestParam(required = false) Boolean includeDescendants,
                           @RequestParam(required = false) Boolean leaderOnly,
                           @RequestParam(required = false) String position,
                           @RequestParam(required = false) List<Long> userIds,
                           @RequestParam(required = false) String scopeType) {
        // 指名范围但名单为空 = 零候选。空数组会被序列化丢掉，所以靠 scopeType 区分
        // "没配范围"和"配了指定人员但一个人都没选"——判据必须在服务端，不能交给客户端。
        if ("user".equals(scopeType) && (userIds == null || userIds.isEmpty())) return List.of();
        return userService.listAuthorized(UserService.UserQuery.of(
            keyword, deptId, includeDescendants, leaderOnly, position, userIds));
    }

    @GetMapping("/page")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_USER_READ + "')")
    public Page<User> page(@RequestParam(defaultValue = "1") long page,
                           @RequestParam(defaultValue = "20") long size,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) Long deptId,
                           @RequestParam(defaultValue = "false") boolean includeDescendants) {
        return userService.listAuthorizedPage(keyword, deptId, includeDescendants, page, size);
    }

    @GetMapping("/manager-candidates")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_USER_READ + "')")
    public List<ManagerCandidate> managerCandidates(@RequestParam Long deptId,
                                                     @RequestParam(required = false) Long excludeUserId,
                                                     @RequestParam(required = false) String keyword) {
        return userService.managerCandidates(deptId, excludeUserId, keyword).stream()
            .map(user -> new ManagerCandidate(user.getId(), user.getDisplayName(),
                user.getEmployeeNo(), user.getDeptId()))
            .toList();
    }

    @PostMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_USER_MANAGE + "')")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_MANAGE);
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
                    "managerId", "roleIds", "status"))),
                Map.of("roleCount", roleIds.size())));
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_USER_MANAGE + "')")
    public User update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_MANAGE);
        return auditService.execute(() -> userService.update(id, body), user ->
            auditService.success("org.user.update", "USER", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", changedFields(body, List.of("employeeNo", "username",
                    "displayName", "email", "phone", "position", "gender", "deptId",
                    "managerId", "status"))),
                Map.of("statusChanged", body.containsKey("status"),
                    "departmentChanged", body.containsKey("deptId"))));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_USER_ROLE_MANAGE + "')")
    public void setRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.SECURITY_USER_ROLE_MANAGE);
        userService.setRoles(id, roleIds);
    }

    @PutMapping("/{id}/password")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_USER_CREDENTIALS_MANAGE + "')")
    public void resetPassword(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_CREDENTIALS_MANAGE);
        String password = body.get("newPassword") == null
            ? null : String.valueOf(body.get("newPassword"));
        auditService.execute(() -> userService.resetPassword(id, password),
            () -> auditService.success("org.user.password.reset", "USER", id,
                AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("password")), Map.of("sessionsRevoked", true)));
    }

    @PutMapping("/{id}/login-access")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_USER_CREDENTIALS_MANAGE + "')")
    public User setLoginAccess(@PathVariable Long id,
                               @Valid @RequestBody LoginAccessRequest request) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_CREDENTIALS_MANAGE);
        return auditService.execute(
            () -> userService.setWecomLoginAccess(id, request.enabled()),
            user -> auditService.success("org.user.login_access.update", "USER", id,
                AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("loginAccess")),
                Map.of("enabled", request.enabled(), "sessionsRevoked", !request.enabled())));
    }

    @PostMapping("/import")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_USER_MANAGE + "')")
    public ImportResult importUsers(@RequestBody ImportRequest request) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_MANAGE);
        List<ImportFailure> failures = new java.util.ArrayList<>();
        int successCount = 0;
        List<Map<String, Object>> rows = request == null || request.users() == null
            ? List.of() : request.users();
        for (int index = 0; index < rows.size(); index++) {
            int rowNumber = index + 2;
            try {
                User user = toUser(rows.get(index));
                Long id = auditService.execute(
                    () -> userService.create(user, List.of(), UUID.randomUUID().toString()),
                    createdId -> auditService.success("org.user.import", "USER", createdId,
                        AuditService.RiskLevel.HIGH,
                        Map.of("changedFields", List.of("profile", "password")),
                        Map.of("row", rowNumber)));
                if (id != null) successCount++;
            } catch (RuntimeException exception) {
                failures.add(new ImportFailure(rowNumber, exception.getMessage()));
            }
        }
        return new ImportResult(successCount, failures.size(), successCount > 0, failures);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_USER_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_MANAGE);
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
        if (body.get("managerId") != null) {
            user.setManagerId(((Number) body.get("managerId")).longValue());
        }
        return user;
    }

    public record ImportRequest(List<Map<String, Object>> users) { }
    public record ImportFailure(int row, String message) { }
    public record ImportResult(int successCount, int failedCount, boolean passwordResetRequired,
                               List<ImportFailure> failures) { }
    public record ManagerCandidate(Long id, String displayName, String employeeNo, Long deptId) { }
    public record LoginAccessRequest(@NotNull Boolean enabled) { }
}

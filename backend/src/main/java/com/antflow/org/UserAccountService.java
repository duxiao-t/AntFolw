package com.antflow.org;

import com.antflow.audit.AuditService;
import com.antflow.auth.AuthSessionService;
import com.antflow.authz.AuthorizationService;
import com.antflow.common.FormalNumberService;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 用户的增删改：建号、改资料/归属、改角色、删号。
 *
 * <p>从 {@code UserService} 原样搬出（方法体逐字未改）。**这里的异常一律不包装、不吞**：
 * 唯一约束冲突（{@code t_user_username_key} / {@code uk_user_employee_no}）要原样冒到
 * {@code GlobalExceptionHandler}，它靠异常 detail 里的约束名映射成 409/具体文案——在这里
 * catch 一下重新抛个 BizException，用户看到的就会变成"系统错误"。
 *
 * <p>事务注解留在门面（{@code @Transactional} 只有走代理才生效）。
 */
@RequiredArgsConstructor
class UserAccountService {
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final DepartmentMapper departmentMapper;
    private final DepartmentLeaderMapper leaderMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder encoder;
    private final FormalNumberService formalNumberService;
    private final AuthorizationService authorizationService;
    private final AuthSessionService authSessionService;
    private final AuditService auditService;
    private final OrgAccess orgAccess;
    private final UserRoles roles;
    private final UserGuards guards;
    private final UserStatusService status;
    private final UserAuthzVersion authzVersion;

    Long create(User u, List<Long> roleIds, String rawPassword) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_MANAGE);
        List<Long> normalizedRoleIds = new LinkedHashSet<>(roleIds == null ? List.of() : roleIds)
            .stream().toList();
        if (!normalizedRoleIds.isEmpty() && !authorizationService.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                "only administrators can assign roles while creating a user");
        }
        roles.validateUserRoles(normalizedRoleIds);
        guards.validateUsernameAvailable(u.getUsername(), null);
        guards.validateDisplayName(u.getDisplayName());
        guards.validateDepartment(u.getDeptId());
        orgAccess.requireDepartment(u.getDeptId());
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_USER_MANAGE, u.getDeptId());
        guards.validateManager(null, u.getManagerId(), u.getDeptId());
        guards.validatePassword(rawPassword);
        // 工号由 FormalNumberService 统一分配：这里**不要**自己判空/拼前缀，唯一约束靠它避让。
        u.setEmployeeNo(formalNumberService.employeeNo(u.getEmployeeNo(), null));
        u.setUsername(u.getUsername().trim());
        u.setDisplayName(u.getDisplayName().trim());
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setStatus("ACTIVE");
        userMapper.insert(u);
        roles.setRolesInternal(u.getId(), normalizedRoleIds);
        return u.getId();
    }

    void setRoles(Long userId, List<Long> roleIds) {
        // 与端点能力对齐（原来是 requireAdmin()，导致拿到 security:user_role:manage 的委派管理员
        // 每个操作都吃 403）。但"能改角色"不能等于"能给自己发管理员"，所以下面单加一道：
        authorizationService.requirePermission(
            com.antflow.authz.PermissionCodes.SECURITY_USER_ROLE_MANAGE);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        List<Long> normalizedRoleIds = new LinkedHashSet<>(roleIds == null ? List.of() : roleIds)
            .stream().toList();
        roles.validateUserRoles(normalizedRoleIds);
        List<String> currentRoles = roles.rolesOf(userId);
        boolean assigningAdmin = normalizedRoleIds.stream().anyMatch(roles::isAdminRole);
        // 动到 admin 角色（授出去或收回来）仍然必须是超管。
        if (assigningAdmin || currentRoles.contains("admin")) {
            authorizationService.requireAdmin();
        }
        if (currentRoles.contains("admin") || assigningAdmin) {
            roles.lockAdminRole();
            user = userMapper.selectById(userId);
            if (user == null) {
                throw new BizException("NOT_FOUND", "用户不存在");
            }
            currentRoles = roles.rolesOf(userId);
        }
        boolean removingAdmin = currentRoles.contains("admin")
            && normalizedRoleIds.stream().noneMatch(roles::isAdminRole);
        if (removingAdmin && "ACTIVE".equals(user.getStatus()) && roles.activeAdminCount() <= 1) {
            throw new BizException("LAST_ADMIN_PROTECTED", "至少保留一个启用的管理员");
        }
        userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", userId));
        roles.setRolesInternal(userId, normalizedRoleIds);
        authzVersion.authorizationChanged(userId);
        auditService.success("security.user_role.update", "USER", userId,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", List.of("roleIds")),
            java.util.Map.of("roleCount", normalizedRoleIds.size()));
    }

    void delete(Long userId) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_MANAGE);
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        orgAccess.requireUserSource(u.getDeptId());
        List<String> currentRoles = roles.rolesOf(userId);
        if (!authorizationService.isAdmin() && currentRoles.contains("admin")) {
            throw new BizException("ADMIN_USER_PROTECTED", "管理员用户只能由管理员操作");
        }
        authorizationService.requireCurrentDataScope(
            com.antflow.authz.PermissionCodes.ORG_USER_MANAGE, userId, u.getDeptId());
        if (!authorizationService.isAdmin() && authorizationService.currentUserId() == userId) {
            throw new BizException("SELF_USER_PROTECTED", "不能删除当前登录账号");
        }
        if (currentRoles.contains("admin")) {
            roles.lockAdminRole();
            u = userMapper.selectById(userId);
            if (u == null) {
                throw new BizException("NOT_FOUND", "用户不存在");
            }
            currentRoles = roles.rolesOf(userId);
        }
        if (currentRoles.contains("admin") && "ACTIVE".equals(u.getStatus())
            && roles.activeAdminCount() <= 1) {
            throw new BizException("LAST_ADMIN_PROTECTED", "至少保留一个启用的管理员");
        }
        if (hasWorkflowReferences(userId)) {
            throw new BizException("USER_IN_USE", "用户已被流程、任务或表单历史引用，不能删除");
        }
        // 顺序是行为的一部分：先清从属关系再删主表，否则外键会把删除挡下来。
        userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", userId));
        leaderMapper.delete(new QueryWrapper<DepartmentLeader>().eq("user_id", userId));
        departmentMapper.update(null, new UpdateWrapper<Department>().eq("leader_id", userId).set("leader_id", null));
        userMapper.deleteById(userId);
        authSessionService.revokeAll(userId);
        authorizationService.evict(userId);
    }

    User update(Long userId, Map<String, Object> body) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_MANAGE);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        orgAccess.requireUserSource(user.getDeptId());
        if (body.containsKey("status") && status.hasWecomMapping(userId)) {
            throw new BizException("WECOM_LOGIN_ACCESS_REQUIRED", "企业微信成员请使用登录权限操作");
        }
        List<String> targetRoles = roles.rolesOf(userId);
        if (!authorizationService.isAdmin() && targetRoles.contains("admin")) {
            throw new BizException("ADMIN_USER_PROTECTED", "管理员用户只能由管理员操作");
        }
        authorizationService.requireCurrentDataScope(
            com.antflow.authz.PermissionCodes.ORG_USER_MANAGE, userId, user.getDeptId());
        Long originalDepartmentId = user.getDeptId();
        if (body.containsKey("status") && roles.rolesOf(userId).contains("admin")) {
            roles.lockAdminRole();
            user = userMapper.selectById(userId);
            if (user == null) {
                throw new BizException("NOT_FOUND", "用户不存在");
            }
        }
        if (body.containsKey("displayName")) user.setDisplayName((String) body.get("displayName"));
        if (body.containsKey("email")) user.setEmail((String) body.get("email"));
        if (body.containsKey("phone")) user.setPhone((String) body.get("phone"));
        if (body.containsKey("position")) user.setPosition((String) body.get("position"));
        if (body.containsKey("gender")) user.setGender((String) body.get("gender"));
        if (body.containsKey("employeeNo")) {
            user.setEmployeeNo(guards.normalizeEmployeeNo((String) body.get("employeeNo"), userId));
        }
        if (body.containsKey("deptId")) {
            Long departmentId = body.get("deptId") == null
                ? null : ((Number) body.get("deptId")).longValue();
            guards.validateDepartment(departmentId);
            orgAccess.requireDepartment(departmentId);
            authorizationService.requireManageableDepartment(
                com.antflow.authz.PermissionCodes.ORG_USER_MANAGE, departmentId);
            user.setDeptId(departmentId);
        }
        if (body.containsKey("managerId")) {
            user.setManagerId(body.get("managerId") == null
                ? null : ((Number) body.get("managerId")).longValue());
        }
        if (body.containsKey("username")) {
            String username = (String) body.get("username");
            guards.validateUsernameAvailable(username, userId);
            user.setUsername(username.trim());
        }
        if (body.containsKey("status")) {
            if (!authorizationService.isAdmin()
                && authorizationService.currentUserId() == userId
                && "DISABLED".equals(String.valueOf(body.get("status")))) {
                throw new BizException("SELF_USER_PROTECTED", "不能停用当前登录账号");
            }
            status.changeStatus(user, String.valueOf(body.get("status")));
        }
        if (body.containsKey("managerId") || body.containsKey("deptId")) {
            guards.lockReportingRelations(originalDepartmentId, user.getDeptId());
            guards.validateManager(userId, user.getManagerId(), user.getDeptId());
        }
        userMapper.updateById(user);
        // UpdateWrapper 显式置 NULL：MyBatis-Plus 的 updateById 默认忽略 null 字段，
        // 不补这一刀的话"清空直属上级"会静默失败（前端看到保存成功、列表里还在）。
        if (body.containsKey("managerId") && user.getManagerId() == null) {
            userMapper.update(null, new UpdateWrapper<User>().eq("id", userId).set("manager_id", null));
        }
        authzVersion.authorizationChanged(userId);
        return user;
    }

    private boolean hasWorkflowReferences(Long userId) {
        return countUserReferences("SELECT COUNT(*) FROM t_form_definition WHERE created_by = ?", userId) > 0
            || countUserReferences("SELECT COUNT(*) FROM t_form_maintainer WHERE user_id = ?", userId) > 0
            || countUserReferences("SELECT COUNT(*) FROM t_form_data WHERE created_by = ?", userId) > 0
            || countUserReferences("SELECT COUNT(*) FROM t_process_definition WHERE created_by = ?", userId) > 0
            || countUserReferences("SELECT COUNT(*) FROM t_process_instance WHERE started_by = ?", userId) > 0
            || countUserReferences("SELECT COUNT(*) FROM t_task WHERE assignee_id = ?", userId) > 0
            || countUserReferences("SELECT COUNT(*) FROM t_task WHERE approved_by = ?", userId) > 0
            || countUserReferences("SELECT COUNT(*) FROM t_task_history WHERE operator_id = ?", userId) > 0;
    }

    private long countUserReferences(String sql, Long userId) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, userId);
        return count == null ? 0L : count;
    }
}

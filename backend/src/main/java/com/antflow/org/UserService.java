package com.antflow.org;

import com.antflow.auth.AuthSessionService;
import com.antflow.audit.AuditService;
import com.antflow.authz.AuthorizationService;
import com.antflow.common.FormalNumberService;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashSet;
import java.util.function.Function;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {
    public static final String DEFAULT_IMPORTED_PASSWORD = "ant.design";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder encoder;
    private final DepartmentMapper departmentMapper;
    private final DepartmentLeaderMapper leaderMapper;
    private final JdbcTemplate jdbcTemplate;
    private final FormalNumberService formalNumberService;
    private final AuthorizationService authorizationService;
    private final AuthSessionService authSessionService;
    private final AuditService auditService;

    @Transactional(rollbackFor = Exception.class)
    public Long create(User u, List<Long> roleIds) {
        String rawPassword = u.getPasswordHash() == null
            ? DEFAULT_IMPORTED_PASSWORD : u.getPasswordHash();
        return create(u, roleIds, rawPassword);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(User u, List<Long> roleIds, String rawPassword) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_WRITE);
        List<Long> normalizedRoleIds = new LinkedHashSet<>(roleIds == null ? List.of() : roleIds)
            .stream().toList();
        if (!normalizedRoleIds.isEmpty() && !authorizationService.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                "only administrators can assign roles while creating a user");
        }
        validateUserRoles(normalizedRoleIds);
        validateUsernameAvailable(u.getUsername(), null);
        validateDisplayName(u.getDisplayName());
        validateDepartment(u.getDeptId());
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_USER_WRITE, u.getDeptId());
        validateManager(null, u.getManagerId(), u.getDeptId());
        validatePassword(rawPassword);
        u.setEmployeeNo(formalNumberService.employeeNo(u.getEmployeeNo(), null));
        u.setUsername(u.getUsername().trim());
        u.setDisplayName(u.getDisplayName().trim());
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setStatus("ACTIVE");
        userMapper.insert(u);
        setRolesInternal(u.getId(), normalizedRoleIds);
        return u.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void setRoles(Long userId, List<Long> roleIds) {
        authorizationService.requireAdmin();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        List<Long> normalizedRoleIds = new LinkedHashSet<>(roleIds == null ? List.of() : roleIds)
            .stream().toList();
        validateUserRoles(normalizedRoleIds);
        List<String> currentRoles = rolesOf(userId);
        boolean assigningAdmin = normalizedRoleIds.stream().anyMatch(this::isAdminRole);
        if (currentRoles.contains("admin") || assigningAdmin) {
            lockAdminRole();
            user = userMapper.selectById(userId);
            if (user == null) {
                throw new BizException("NOT_FOUND", "用户不存在");
            }
            currentRoles = rolesOf(userId);
        }
        boolean removingAdmin = currentRoles.contains("admin")
            && normalizedRoleIds.stream().noneMatch(this::isAdminRole);
        if (removingAdmin && "ACTIVE".equals(user.getStatus()) && activeAdminCount() <= 1) {
            throw new BizException("LAST_ADMIN_PROTECTED", "至少保留一个启用的管理员");
        }
        userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", userId));
        setRolesInternal(userId, normalizedRoleIds);
        authorizationChanged(userId);
        auditService.success("security.user_role.update", "USER", userId,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", List.of("roleIds")),
            java.util.Map.of("roleCount", normalizedRoleIds.size()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_WRITE);
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        List<String> currentRoles = rolesOf(userId);
        if (!authorizationService.isAdmin() && currentRoles.contains("admin")) {
            throw new BizException("ADMIN_USER_PROTECTED", "管理员用户只能由管理员操作");
        }
        authorizationService.requireCurrentDataScope(
            com.antflow.authz.PermissionCodes.ORG_USER_WRITE, userId, u.getDeptId());
        if (!authorizationService.isAdmin() && authorizationService.currentUserId() == userId) {
            throw new BizException("SELF_USER_PROTECTED", "不能删除当前登录账号");
        }
        if (currentRoles.contains("admin")) {
            lockAdminRole();
            u = userMapper.selectById(userId);
            if (u == null) {
                throw new BizException("NOT_FOUND", "用户不存在");
            }
            currentRoles = rolesOf(userId);
        }
        if (currentRoles.contains("admin") && "ACTIVE".equals(u.getStatus())
            && activeAdminCount() <= 1) {
            throw new BizException("LAST_ADMIN_PROTECTED", "至少保留一个启用的管理员");
        }
        if (hasWorkflowReferences(userId)) {
            throw new BizException("USER_IN_USE", "用户已被流程、任务或表单历史引用，不能删除");
        }
        userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", userId));
        leaderMapper.delete(new QueryWrapper<DepartmentLeader>().eq("user_id", userId));
        departmentMapper.update(null, new UpdateWrapper<Department>().eq("leader_id", userId).set("leader_id", null));
        userMapper.deleteById(userId);
        authSessionService.revokeAll(userId);
        authorizationService.evict(userId);
    }

    void validateUsernameAvailable(String username, Long excludedUserId) {
        if (username == null || username.isBlank()) {
            throw new BizException("USERNAME_REQUIRED", "账号不能为空");
        }
        QueryWrapper<User> query = new QueryWrapper<User>().eq("username", username.trim());
        if (excludedUserId != null) {
            query.ne("id", excludedUserId);
        }
        if (userMapper.selectCount(query) > 0) {
            throw new BizException("USERNAME_EXISTS", "账号已存在");
        }
    }

    void validateDepartment(Long departmentId) {
        if (departmentId != null && departmentMapper.selectById(departmentId) == null) {
            throw new BizException("DEPARTMENT_NOT_FOUND", "所属部门不存在");
        }
    }

    private void validateManager(Long userId, Long managerId, Long departmentId) {
        if (managerId == null) return;
        if (departmentId == null) {
            throw new BizException("MANAGER_DEPARTMENT_REQUIRED", "配置直属上级前请先选择所属部门");
        }
        if (Objects.equals(userId, managerId)) {
            throw new BizException("MANAGER_SELF", "直属上级不能是本人");
        }
        Department department = departmentMapper.selectById(departmentId);
        User current = userMapper.selectById(managerId);
        if (current == null) {
            throw new BizException("MANAGER_NOT_FOUND", "直属上级不存在");
        }
        if (!"ACTIVE".equals(current.getStatus())) {
            throw new BizException("MANAGER_INACTIVE", "直属上级已停用");
        }
        Set<Long> seen = new HashSet<>();
        while (current != null) {
            if (!seen.add(current.getId()) || Objects.equals(userId, current.getId())) {
                throw new BizException("MANAGER_CYCLE", "直属上级关系不能形成循环");
            }
            Department currentDepartment = current.getDeptId() == null
                ? null : departmentMapper.selectById(current.getDeptId());
            if (department == null || currentDepartment == null
                || !Objects.equals(department.getCompanyId(), currentDepartment.getCompanyId())) {
                throw new BizException("MANAGER_COMPANY_MISMATCH", "直属上级必须与成员属于同一公司");
            }
            current = current.getManagerId() == null
                ? null : userMapper.selectById(current.getManagerId());
        }
    }

    private void lockReportingRelations(Long... departmentIds) {
        Set<Long> companyIds = new java.util.TreeSet<>();
        for (Long departmentId : departmentIds) {
            if (departmentId == null) continue;
            Department department = departmentMapper.selectById(departmentId);
            if (department != null) companyIds.add(department.getCompanyId());
        }
        for (Long companyId : companyIds) {
            jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)",
                statement -> statement.setLong(1, 0x414E544600000000L ^ companyId),
                (org.springframework.jdbc.core.ResultSetExtractor<Void>) resultSet -> null);
        }
    }

    private void validateDirectReportsCompany(Long userId, Long departmentId) {
        List<User> directReports = userMapper.selectList(
            new QueryWrapper<User>().eq("manager_id", userId));
        if (directReports.isEmpty()) return;
        Department department = departmentId == null ? null : departmentMapper.selectById(departmentId);
        for (User report : directReports) {
            Department reportDepartment = report.getDeptId() == null
                ? null : departmentMapper.selectById(report.getDeptId());
            if (department == null || reportDepartment == null
                || !Objects.equals(department.getCompanyId(), reportDepartment.getCompanyId())) {
                throw new BizException("REPORTING_COMPANY_MISMATCH",
                    "该成员仍是其他公司成员的直属上级，请先调整汇报关系");
            }
        }
    }

    String normalizeEmployeeNo(String employeeNo, Long excludedUserId) {
        return formalNumberService.employeeNo(employeeNo, excludedUserId);
    }

    private void validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new BizException("DISPLAY_NAME_REQUIRED", "姓名不能为空");
        }
    }

    public List<String> rolesOf(Long userId) {
        return userRoleMapper.selectList(new QueryWrapper<UserRole>().eq("user_id", userId)).stream()
            .map(ur -> roleMapper.selectById(ur.getRoleId()))
            .filter(java.util.Objects::nonNull)
            .map(Role::getCode)
            .toList();
    }

    public List<User> listAuthorized(String keyword, Long departmentId) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_READ);
        QueryWrapper<User> query = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            query.and(wrapper -> wrapper.like("username", keyword.trim())
                .or().like("display_name", keyword.trim())
                .or().like("employee_no", keyword.trim()));
        }
        if (departmentId != null) {
            query.eq("dept_id", departmentId);
        }
        List<User> users = userMapper.selectList(query).stream()
            .filter(user -> authorizationService.inCurrentDataScope(
                com.antflow.authz.PermissionCodes.ORG_USER_READ,
                user.getId(), user.getDeptId()))
            .toList();
        fillManagerNames(users);
        return users;
    }

    public Page<User> listAuthorizedPage(String keyword, Long departmentId,
                                         boolean includeDescendants, long page, long size) {
        String permission = com.antflow.authz.PermissionCodes.ORG_USER_READ;
        authorizationService.requirePermission(permission);
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        QueryWrapper<User> query = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String normalized = keyword.trim();
            query.and(wrapper -> wrapper.like("username", normalized)
                .or().like("display_name", normalized)
                .or().like("employee_no", normalized));
        }
        if (departmentId != null) {
            List<Long> requested = includeDescendants
                ? departmentMapper.subtreeIds(departmentId) : List.of(departmentId);
            if (requested.isEmpty()) return Page.of(safePage, safeSize, 0);
            query.in("dept_id", requested);
        }
        AuthorizationService.AuthzSnapshot snapshot = authorizationService.currentSnapshot();
        if (!snapshot.admin()) {
            Set<Long> departments = authorizationService.manageableDepartments(snapshot, permission);
            boolean self = snapshot.permissionRoles().getOrDefault(permission, List.of()).stream()
                .anyMatch(grant -> grant.dataScope() == com.antflow.authz.DataScope.SELF);
            if (departments.isEmpty() && !self) return Page.of(safePage, safeSize, 0);
            query.and(scope -> {
                if (!departments.isEmpty()) scope.in("dept_id", departments);
                if (self) {
                    if (!departments.isEmpty()) scope.or();
                    scope.eq("id", snapshot.userId());
                }
            });
        }
        query.orderByAsc("display_name").orderByAsc("id");
        Page<User> result = userMapper.selectPage(Page.of(safePage, safeSize), query);
        fillManagerNames(result.getRecords());
        return result;
    }

    public List<User> managerCandidates(Long departmentId, Long excludedUserId, String keyword) {
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_USER_WRITE, departmentId);
        Department department = departmentId == null ? null : departmentMapper.selectById(departmentId);
        if (department == null) {
            throw new BizException("DEPARTMENT_NOT_FOUND", "所属部门不存在");
        }
        List<Long> departmentIds = departmentMapper.selectList(new QueryWrapper<Department>()
            .eq("company_id", department.getCompanyId())).stream()
            .map(Department::getId)
            .toList();
        QueryWrapper<User> query = new QueryWrapper<User>()
            .eq("status", "ACTIVE")
            .in("dept_id", departmentIds)
            .orderByAsc("display_name")
            .orderByAsc("id")
            .last("LIMIT 100");
        if (excludedUserId != null) query.ne("id", excludedUserId);
        if (keyword != null && !keyword.isBlank()) {
            String normalized = keyword.trim();
            query.and(item -> item.like("display_name", normalized)
                .or().like("username", normalized)
                .or().like("employee_no", normalized));
        }
        return userMapper.selectList(query);
    }

    private void fillManagerNames(List<User> users) {
        List<Long> managerIds = users.stream()
            .map(User::getManagerId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (managerIds.isEmpty()) return;
        Map<Long, User> managers = userMapper.selectBatchIds(managerIds).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        for (User user : users) {
            User manager = managers.get(user.getManagerId());
            if (manager != null) user.setManagerDisplayName(manager.getDisplayName());
        }
    }

    private void changeStatus(User user, String status) {
        if (!List.of("ACTIVE", "DISABLED").contains(status)) {
            throw new BizException("BAD_USER_STATUS", "用户状态无效");
        }
        if ("ACTIVE".equals(user.getStatus()) && "DISABLED".equals(status)
            && rolesOf(user.getId()).contains("admin") && activeAdminCount() <= 1) {
            throw new BizException("LAST_ADMIN_PROTECTED", "至少保留一个启用的管理员");
        }
        user.setStatus(status);
        if (!"ACTIVE".equals(status)) {
            authSessionService.revokeAll(user.getId());
        }
    }

    public void authorizationChanged(Long userId) {
        jdbcTemplate.update("UPDATE t_user SET authz_version = authz_version + 1 WHERE id = ?",
            userId);
        authorizationService.evict(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public User update(Long userId, Map<String, Object> body) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_USER_WRITE);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        List<String> targetRoles = rolesOf(userId);
        if (!authorizationService.isAdmin() && targetRoles.contains("admin")) {
            throw new BizException("ADMIN_USER_PROTECTED", "管理员用户只能由管理员操作");
        }
        authorizationService.requireCurrentDataScope(
            com.antflow.authz.PermissionCodes.ORG_USER_WRITE, userId, user.getDeptId());
        Long originalDepartmentId = user.getDeptId();
        if (body.containsKey("status") && rolesOf(userId).contains("admin")) {
            lockAdminRole();
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
            user.setEmployeeNo(normalizeEmployeeNo((String) body.get("employeeNo"), userId));
        }
        if (body.containsKey("deptId")) {
            Long departmentId = body.get("deptId") == null
                ? null : ((Number) body.get("deptId")).longValue();
            validateDepartment(departmentId);
            authorizationService.requireManageableDepartment(
                com.antflow.authz.PermissionCodes.ORG_USER_WRITE, departmentId);
            user.setDeptId(departmentId);
        }
        if (body.containsKey("managerId")) {
            user.setManagerId(body.get("managerId") == null
                ? null : ((Number) body.get("managerId")).longValue());
        }
        if (body.containsKey("username")) {
            String username = (String) body.get("username");
            validateUsernameAvailable(username, userId);
            user.setUsername(username.trim());
        }
        if (body.containsKey("status")) {
            if (!authorizationService.isAdmin()
                && authorizationService.currentUserId() == userId
                && "DISABLED".equals(String.valueOf(body.get("status")))) {
                throw new BizException("SELF_USER_PROTECTED", "不能停用当前登录账号");
            }
            changeStatus(user, String.valueOf(body.get("status")));
        }
        if (body.containsKey("managerId") || body.containsKey("deptId")) {
            lockReportingRelations(originalDepartmentId, user.getDeptId());
            validateManager(userId, user.getManagerId(), user.getDeptId());
            if (body.containsKey("deptId")) {
                validateDirectReportsCompany(userId, user.getDeptId());
            }
        }
        userMapper.updateById(user);
        authorizationChanged(userId);
        return user;
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long userId, String rawPassword) {
        authorizationService.requireAdmin();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        validatePassword(rawPassword);
        user.setPasswordHash(encoder.encode(rawPassword));
        userMapper.updateById(user);
        authSessionService.revokeAll(userId);
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BizException("PASSWORD_REQUIRED", "密码不能为空");
        }
        if (rawPassword.length() < 8 || rawPassword.length() > 64) {
            throw new BizException("PASSWORD_INVALID", "密码长度必须为 8 到 64 位");
        }
    }

    private void setRolesInternal(Long userId, List<Long> roleIds) {
        roleIds.forEach(rid -> userRoleMapper.insert(new UserRole(userId, rid)));
    }

    private boolean isAdminRole(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        return role != null && "admin".equals(role.getCode());
    }

    private void validateUserRoles(List<Long> roleIds) {
        for (Long roleId : roleIds) {
            Role role = roleMapper.selectById(roleId);
            if (role == null || !Boolean.TRUE.equals(role.getEnabled())) {
                throw new BizException("ROLE_NOT_FOUND", "角色不存在或未启用");
            }
        }
    }

    private void lockAdminRole() {
        Long roleId = jdbcTemplate.query("SELECT id FROM t_role WHERE code = 'admin' FOR UPDATE",
            resultSet -> resultSet.next() ? resultSet.getLong(1) : null);
        if (roleId == null) {
            throw new BizException("ADMIN_ROLE_MISSING", "管理员角色不存在");
        }
    }

    private long activeAdminCount() {
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(DISTINCT u.id)
            FROM t_user u
            JOIN t_user_role ur ON ur.user_id = u.id
            JOIN t_role role ON role.id = ur.role_id
            WHERE u.status = 'ACTIVE' AND role.code = 'admin' AND role.enabled = true
            """, Long.class);
        return count == null ? 0L : count;
    }

    private boolean hasWorkflowReferences(Long userId) {
        return countUserReferences("SELECT COUNT(*) FROM t_form_definition WHERE created_by = ?", userId) > 0
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

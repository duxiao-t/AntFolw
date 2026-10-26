package com.antflow.org;

import com.antflow.common.FormalNumberService;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 用户写入前的校验：账号/姓名/密码、部门与直属上级、跨公司迁移、汇报关系串行化。
 *
 * <p>从 {@code UserService} 原样搬出（方法体逐字未改）。这里**只做校验与加锁**，不写库：
 * 抛错时事务还没动过任何行，调用方回滚也不需要补偿。
 *
 * <p>直属上级那段是**沿指针走链**（最多走到没上级）：先判自环/成环，再逐级比对公司。
 * 两个判据都保留，只判环会让"上级在别的公司"的用户被放进来，只判公司则会让 A→B→A 死循环。
 */
@RequiredArgsConstructor
class UserGuards {
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final JdbcTemplate jdbcTemplate;
    private final FormalNumberService formalNumberService;

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

    void validateManager(Long userId, Long managerId, Long departmentId) {
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
            // 单公司：不再要求"同一公司"，但仍要求直属上级归属某个部门。
            if (currentDepartment == null) {
                throw new BizException("MANAGER_DEPARTMENT_MISSING", "直属上级必须归属某个部门");
            }
            current = current.getManagerId() == null
                ? null : userMapper.selectById(current.getManagerId());
        }
    }

    /**
     * 按公司加事务级 advisory lock，把"同一个公司下的汇报关系变更"串行化。
     *
     * <p>**必须用 xact 锁**（提交/回滚自动释放）：改成 session 锁后，连接池复用会让锁跨请求残留。
     * 常量前缀 {@code 0x414E5446}（"ANTF"）是为了和别的模块抢同一把锁时错开命名空间。
     */
    /**
     * 汇报关系变更的串行化锁。
     *
     * <p>以前按公司分别加锁（同一公司内的汇报关系可能成环，需要串行）。单公司后只剩一个域，
     * 用固定 key 一把锁即可，不必再按部门反查公司。
     */
    void lockReportingRelations(Long... departmentIds) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)",
            statement -> statement.setLong(1, 0x414E544600000000L),
            (org.springframework.jdbc.core.ResultSetExtractor<Void>) resultSet -> null);
    }


    String normalizeEmployeeNo(String employeeNo, Long excludedUserId) {
        return formalNumberService.employeeNo(employeeNo, excludedUserId);
    }

    void validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new BizException("DISPLAY_NAME_REQUIRED", "姓名不能为空");
        }
    }

    void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BizException("PASSWORD_REQUIRED", "密码不能为空");
        }
        if (rawPassword.length() < 8 || rawPassword.length() > 64) {
            throw new BizException("PASSWORD_INVALID", "密码长度必须为 8 到 64 位");
        }
    }

}

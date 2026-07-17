package com.antflow.org;

import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder encoder;
    private final DepartmentMapper departmentMapper;
    private final DepartmentLeaderMapper leaderMapper;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public Long create(User u, List<Long> roleIds) {
        // MVP demo default — production must require explicit password
        // and force first-login change.
        String raw = u.getPasswordHash() == null ? "ant.design" : u.getPasswordHash();
        u.setPasswordHash(encoder.encode(raw));
        u.setStatus("ACTIVE");
        userMapper.insert(u);
        setRolesInternal(u.getId(), roleIds);
        return u.getId();
    }

    @Transactional
    public void setRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", userId));
        setRolesInternal(userId, roleIds);
    }

    @Transactional
    public void delete(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw new BizException("NOT_FOUND", "用户不存在");
        }
        if (rolesOf(userId).contains("admin")) {
            throw new BizException("ADMIN_USER_PROTECTED", "管理员用户不能删除");
        }
        if (hasWorkflowReferences(userId)) {
            throw new BizException("USER_IN_USE", "用户已被流程、任务或表单历史引用，不能删除");
        }
        userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", userId));
        leaderMapper.delete(new QueryWrapper<DepartmentLeader>().eq("user_id", userId));
        departmentMapper.update(null, new UpdateWrapper<Department>().eq("leader_id", userId).set("leader_id", null));
        userMapper.deleteById(userId);
    }

    public List<String> rolesOf(Long userId) {
        return userRoleMapper.selectList(new QueryWrapper<UserRole>().eq("user_id", userId)).stream()
            .map(ur -> roleMapper.selectById(ur.getRoleId()))
            .filter(java.util.Objects::nonNull)
            .map(Role::getCode)
            .toList();
    }

    private void setRolesInternal(Long userId, List<Long> roleIds) {
        roleIds.forEach(rid -> userRoleMapper.insert(new UserRole(userId, rid)));
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

package com.antflow.org;

import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 用户-角色关系的读写，以及「至少一个启用管理员」这条不变量的加锁与计数。
 *
 * <p>从 {@code UserService} 原样搬出（方法体逐字未改）。**两条锁语义不能动**：
 * <ul>
 *   <li>{@link #lockAdminRole()} 用 {@code SELECT ... FOR UPDATE} 把 admin 角色行锁住，所有
 *       "可能改到管理员集合"的路径都要先调它，否则并发两次降权会各自读到"还有 2 个管理员"；</li>
 *   <li>{@link #activeAdminCount()} 只数 {@code status='ACTIVE' AND role.enabled}，停用的管理员不算。</li>
 * </ul>
 */
@RequiredArgsConstructor
class UserRoles {
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final JdbcTemplate jdbcTemplate;

    List<String> rolesOf(Long userId) {
        return userRoleMapper.selectList(new QueryWrapper<UserRole>().eq("user_id", userId)).stream()
            .map(ur -> roleMapper.selectById(ur.getRoleId()))
            .filter(java.util.Objects::nonNull)
            .map(Role::getCode)
            .toList();
    }

    void setRolesInternal(Long userId, List<Long> roleIds) {
        roleIds.forEach(rid -> userRoleMapper.insert(new UserRole(userId, rid)));
    }

    boolean isAdminRole(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        return role != null && "admin".equals(role.getCode());
    }

    void validateUserRoles(List<Long> roleIds) {
        for (Long roleId : roleIds) {
            Role role = roleMapper.selectById(roleId);
            if (role == null || !Boolean.TRUE.equals(role.getEnabled())) {
                throw new BizException("ROLE_NOT_FOUND", "角色不存在或未启用");
            }
        }
    }

    void lockAdminRole() {
        Long roleId = jdbcTemplate.query("SELECT id FROM t_role WHERE code = 'admin' FOR UPDATE",
            resultSet -> resultSet.next() ? resultSet.getLong(1) : null);
        if (roleId == null) {
            throw new BizException("ADMIN_ROLE_MISSING", "管理员角色不存在");
        }
    }

    long activeAdminCount() {
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(DISTINCT u.id)
            FROM t_user u
            JOIN t_user_role ur ON ur.user_id = u.id
            JOIN t_role role ON role.id = ur.role_id
            WHERE u.status = 'ACTIVE' AND role.code = 'admin' AND role.enabled = true
            """, Long.class);
        return count == null ? 0L : count;
    }
}

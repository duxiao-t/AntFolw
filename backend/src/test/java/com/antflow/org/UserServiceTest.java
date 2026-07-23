package com.antflow.org;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.antflow.engine.BizException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {
    @Test
    void createRejectsDuplicateUsernameBeforeInsert() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        RoleMapper roleMapper = Mockito.mock(RoleMapper.class);
        PasswordEncoder encoder = Mockito.mock(PasswordEncoder.class);
        DepartmentMapper departmentMapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        UserService service = new UserService(userMapper, userRoleMapper, roleMapper, encoder, departmentMapper, leaderMapper, jdbcTemplate);
        User user = new User();
        user.setUsername("duplicate");
        user.setDisplayName("Duplicate User");
        when(userMapper.selectCount(any())).thenReturn(1L);

        BizException error = assertThrows(BizException.class, () -> service.create(user, List.of()));

        assertEquals("USERNAME_EXISTS", error.getCode());
        assertEquals("账号已存在", error.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void createRejectsUnknownDepartmentBeforeInsert() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        RoleMapper roleMapper = Mockito.mock(RoleMapper.class);
        PasswordEncoder encoder = Mockito.mock(PasswordEncoder.class);
        DepartmentMapper departmentMapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        UserService service = new UserService(userMapper, userRoleMapper, roleMapper, encoder, departmentMapper, leaderMapper, jdbcTemplate);
        User user = new User();
        user.setUsername("new-user");
        user.setDisplayName("New User");
        user.setDeptId(999L);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(departmentMapper.selectById(999L)).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.create(user, List.of()));

        assertEquals("DEPARTMENT_NOT_FOUND", error.getCode());
        assertEquals("所属部门不存在", error.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }
    @Test
    void deleteClearsUserAssociationsBeforeRemovingUser() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        RoleMapper roleMapper = Mockito.mock(RoleMapper.class);
        PasswordEncoder encoder = Mockito.mock(PasswordEncoder.class);
        DepartmentMapper departmentMapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        UserService service = new UserService(userMapper, userRoleMapper, roleMapper, encoder, departmentMapper, leaderMapper, jdbcTemplate);
        User user = new User();
        user.setId(9L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(9L))).thenReturn(0L);

        service.delete(9L);

        verify(userRoleMapper).delete(any(QueryWrapper.class));
        verify(leaderMapper).delete(any(QueryWrapper.class));
        verify(departmentMapper).update(isNull(), any(UpdateWrapper.class));
        InOrder order = Mockito.inOrder(userRoleMapper, leaderMapper, departmentMapper, userMapper);
        order.verify(userRoleMapper).delete(any(QueryWrapper.class));
        order.verify(leaderMapper).delete(any(QueryWrapper.class));
        order.verify(departmentMapper).update(isNull(), any(UpdateWrapper.class));
        order.verify(userMapper).deleteById(9L);
    }

    @Test
    void deleteRejectsAdminUsers() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        RoleMapper roleMapper = Mockito.mock(RoleMapper.class);
        PasswordEncoder encoder = Mockito.mock(PasswordEncoder.class);
        DepartmentMapper departmentMapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        UserService service = new UserService(userMapper, userRoleMapper, roleMapper, encoder, departmentMapper, leaderMapper, jdbcTemplate);
        User user = new User();
        user.setId(1L);
        Role admin = new Role();
        admin.setId(2L);
        admin.setCode("admin");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new UserRole(1L, 2L)));
        when(roleMapper.selectById(2L)).thenReturn(admin);

        assertThrows(BizException.class, () -> service.delete(1L));

        verify(userMapper, never()).deleteById(1L);
    }

    @Test
    void deleteRejectsUsersReferencedByWorkflowData() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        RoleMapper roleMapper = Mockito.mock(RoleMapper.class);
        PasswordEncoder encoder = Mockito.mock(PasswordEncoder.class);
        DepartmentMapper departmentMapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        UserService service = new UserService(userMapper, userRoleMapper, roleMapper, encoder, departmentMapper, leaderMapper, jdbcTemplate);
        User user = new User();
        user.setId(9L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(9L))).thenReturn(1L);

        assertThrows(BizException.class, () -> service.delete(9L));

        verify(userMapper, never()).deleteById(9L);
    }
}

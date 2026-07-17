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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {
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

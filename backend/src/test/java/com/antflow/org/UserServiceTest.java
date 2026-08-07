package com.antflow.org;

import com.antflow.auth.AuthSessionService;
import com.antflow.audit.AuditService;
import com.antflow.authz.AuthorizationService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.antflow.engine.BizException;
import com.antflow.common.FormalNumberService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.contains;
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
        UserService service = newService(userMapper, userRoleMapper, roleMapper, encoder,
            departmentMapper, leaderMapper, jdbcTemplate);
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
        UserService service = newService(userMapper, userRoleMapper, roleMapper, encoder,
            departmentMapper, leaderMapper, jdbcTemplate);
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
    void createRejectsPasswordOutsidePolicy() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        RoleMapper roleMapper = Mockito.mock(RoleMapper.class);
        DepartmentMapper departmentMapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        UserService service = newService(userMapper, userRoleMapper, roleMapper,
            Mockito.mock(PasswordEncoder.class), departmentMapper, leaderMapper,
            Mockito.mock(JdbcTemplate.class));
        User user = new User();
        user.setUsername("short-password");
        user.setDisplayName("Short Password");
        when(userMapper.selectCount(any())).thenReturn(0L);

        BizException error = assertThrows(BizException.class,
            () -> service.create(user, List.of(), "short"));

        assertEquals("PASSWORD_INVALID", error.getCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void resetPasswordUpdatesHashAndRevokesSessions() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        RoleMapper roleMapper = Mockito.mock(RoleMapper.class);
        PasswordEncoder encoder = Mockito.mock(PasswordEncoder.class);
        AuthSessionService sessions = Mockito.mock(AuthSessionService.class);
        UserService service = new UserService(userMapper, userRoleMapper, roleMapper, encoder,
            Mockito.mock(DepartmentMapper.class), Mockito.mock(DepartmentLeaderMapper.class),
            Mockito.mock(JdbcTemplate.class), Mockito.mock(FormalNumberService.class),
            Mockito.mock(AuthorizationService.class), sessions, Mockito.mock(AuditService.class));
        User user = new User();
        user.setId(9L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(encoder.encode("new-pass-1")).thenReturn("encoded");

        service.resetPassword(9L, "new-pass-1");

        assertEquals("encoded", user.getPasswordHash());
        verify(userMapper).updateById(user);
        verify(sessions).revokeAll(9L);
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
        UserService service = newService(userMapper, userRoleMapper, roleMapper, encoder,
            departmentMapper, leaderMapper, jdbcTemplate);
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
        UserService service = newService(userMapper, userRoleMapper, roleMapper, encoder,
            departmentMapper, leaderMapper, jdbcTemplate);
        User user = new User();
        user.setId(1L);
        user.setStatus("ACTIVE");
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
        UserService service = newService(userMapper, userRoleMapper, roleMapper, encoder,
            departmentMapper, leaderMapper, jdbcTemplate);
        User user = new User();
        user.setId(9L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(9L))).thenReturn(1L);

        assertThrows(BizException.class, () -> service.delete(9L));

        verify(userMapper, never()).deleteById(9L);
    }

    @Test
    void removingLastAdminLocksInvariantRowBeforeRejectingChange() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        RoleMapper roleMapper = Mockito.mock(RoleMapper.class);
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        UserService service = newService(userMapper, userRoleMapper, roleMapper,
            Mockito.mock(PasswordEncoder.class), Mockito.mock(DepartmentMapper.class),
            Mockito.mock(DepartmentLeaderMapper.class), jdbcTemplate);
        User user = new User();
        user.setId(1L);
        user.setStatus("ACTIVE");
        Role admin = new Role();
        admin.setId(2L);
        admin.setCode("admin");
        admin.setEnabled(true);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new UserRole(1L, 2L)));
        when(roleMapper.selectById(2L)).thenReturn(admin);
        when(jdbcTemplate.query(contains("FOR UPDATE"),
            Mockito.<ResultSetExtractor<Long>>any())).thenReturn(2L);
        when(jdbcTemplate.queryForObject(contains("COUNT(DISTINCT u.id)"), eq(Long.class)))
            .thenReturn(1L);

        BizException error = assertThrows(BizException.class,
            () -> service.setRoles(1L, List.of()));

        assertEquals("LAST_ADMIN_PROTECTED", error.getCode());
        verify(jdbcTemplate).query(contains("FOR UPDATE"),
            Mockito.<ResultSetExtractor<Long>>any());
        verify(userRoleMapper, never()).delete(any(QueryWrapper.class));
    }

    private static UserService newService(UserMapper userMapper, UserRoleMapper userRoleMapper,
                                          RoleMapper roleMapper, PasswordEncoder encoder,
                                          DepartmentMapper departmentMapper,
                                          DepartmentLeaderMapper leaderMapper,
                                          JdbcTemplate jdbcTemplate) {
        return new UserService(userMapper, userRoleMapper, roleMapper, encoder, departmentMapper,
            leaderMapper, jdbcTemplate, Mockito.mock(FormalNumberService.class),
            Mockito.mock(AuthorizationService.class),
            Mockito.mock(AuthSessionService.class), Mockito.mock(AuditService.class));
    }
}

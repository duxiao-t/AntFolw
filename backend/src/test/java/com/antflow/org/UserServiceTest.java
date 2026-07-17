package com.antflow.org;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
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
        UserService service = new UserService(userMapper, userRoleMapper, roleMapper, encoder, departmentMapper, leaderMapper);
        User user = new User();
        user.setId(9L);
        when(userMapper.selectById(9L)).thenReturn(user);

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
}

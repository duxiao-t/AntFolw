package com.antflow.engine;

import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.engine.resolver.AssigneeSpec;
import com.antflow.org.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * Resolver tests do NOT need a real DB or PG — mappers are mocked.
 */
class AssigneeResolverTest {

    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private RoleMapper roleMapper;

    @BeforeEach void setup() {
        userMapper = Mockito.mock(UserMapper.class);
        userRoleMapper = Mockito.mock(UserRoleMapper.class);
        roleMapper = Mockito.mock(RoleMapper.class);
    }

    private User user(long id, String status) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setDisplayName("User " + id);
        u.setPasswordHash("x");
        u.setStatus(status);
        return u;
    }

    @Test void disabledUsersAreSilentlyDropped() {
        Mockito.when(userMapper.selectBatchIds(any())).thenReturn(List.of(
            user(1L, "ACTIVE"),
            user(2L, "DISABLED")
        ));
        var r = new AssigneeResolver(userMapper, userRoleMapper, roleMapper);
        var resolved = r.resolve("n1", AssigneeSpec.user(List.of(1L, 2L)));
        assertThat(resolved).containsExactly(1L);
    }

    @Test void allDisabledUsersThrowNoAssignee() {
        Mockito.when(userMapper.selectBatchIds(any())).thenReturn(List.of(
            user(1L, "DISABLED"),
            user(2L, "DISABLED")
        ));
        var r = new AssigneeResolver(userMapper, userRoleMapper, roleMapper);
        assertThatThrownBy(() -> r.resolve("n1", AssigneeSpec.user(List.of(1L, 2L))))
            .isInstanceOf(NoAssigneeFoundException.class);
    }

    @Test void emptyUserListThrowsNoAssignee() {
        var r = new AssigneeResolver(userMapper, userRoleMapper, roleMapper);
        assertThatThrownBy(() -> r.resolve("n1", AssigneeSpec.user(List.of())))
            .isInstanceOf(NoAssigneeFoundException.class);
    }

    @Test void emptyRoleMembersThrowNoAssignee() {
        Mockito.when(userRoleMapper.selectList(any())).thenReturn(List.of());
        var r = new AssigneeResolver(userMapper, userRoleMapper, roleMapper);
        assertThatThrownBy(() -> r.resolve("n1", AssigneeSpec.role(List.of(99L))))
            .isInstanceOf(NoAssigneeFoundException.class);
    }

    @Test void deptLeaderNotSupportedYet() {
        var r = new AssigneeResolver(userMapper, userRoleMapper, roleMapper);
        assertThatThrownBy(() -> r.resolve("n1", AssigneeSpec.deptLeader()))
            .isInstanceOf(IllegalStateException.class);
    }
}

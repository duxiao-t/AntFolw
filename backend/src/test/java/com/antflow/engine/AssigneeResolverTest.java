package com.antflow.engine;

import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.engine.resolver.AssigneeSpec;
import com.antflow.engine.resolver.AssignUserStrategy;
import com.antflow.engine.resolver.LeaderStrategy;
import com.antflow.engine.resolver.RoleStrategy;
import com.antflow.engine.resolver.SelfSelectStrategy;
import com.antflow.engine.resolver.SelfStrategy;
import com.antflow.org.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * Sprint 2 C1 重构后：AssigneeResolver 不再是 switch-case；通过 Spring 注入
 * List&lt;AssigneeStrategy&gt; 路由到第一个 supports() 匹配的实现。
 * 测试手工构造 strategies 列表（mappers mocked）。
 */
class AssigneeResolverTest {

    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private RoleMapper roleMapper;
    private DepartmentMapper deptMapper;

    @BeforeEach void setup() {
        userMapper = Mockito.mock(UserMapper.class);
        userRoleMapper = Mockito.mock(UserRoleMapper.class);
        roleMapper = Mockito.mock(RoleMapper.class);
        deptMapper = Mockito.mock(DepartmentMapper.class);
    }

    private AssigneeResolver resolver() {
        return new AssigneeResolver(List.of(
            new AssignUserStrategy(userMapper),
            new RoleStrategy(userMapper, userRoleMapper),
            new LeaderStrategy(userMapper, deptMapper),
            new SelfStrategy(),
            new SelfSelectStrategy()
        ));
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

    private User user(long id, String status, Long deptId) {
        User u = user(id, status);
        u.setDeptId(deptId);
        return u;
    }

    private Department dept(long id, Long leaderId, Long parentId) {
        Department d = new Department();
        d.setId(id);
        d.setName("d" + id);
        d.setLeaderId(leaderId);
        d.setParentId(parentId);
        return d;
    }

    @Test void disabledUsersAreSilentlyDropped() {
        Mockito.when(userMapper.selectBatchIds(any())).thenReturn(List.of(
            user(1L, "ACTIVE"),
            user(2L, "DISABLED")
        ));
        var resolved = resolver().resolve("n1", AssigneeSpec.of("ASSIGN_USER", List.of(1L, 2L)));
        assertThat(resolved).containsExactly(1L);
    }

    @Test void allDisabledUsersThrowNoAssignee() {
        Mockito.when(userMapper.selectBatchIds(any())).thenReturn(List.of(
            user(1L, "DISABLED"),
            user(2L, "DISABLED")
        ));
        assertThatThrownBy(() -> resolver().resolve("n1", AssigneeSpec.of("ASSIGN_USER", List.of(1L, 2L))))
            .isInstanceOf(NoAssigneeFoundException.class);
    }

    @Test void emptyUserListThrowsNoAssignee() {
        assertThatThrownBy(() -> resolver().resolve("n1", AssigneeSpec.of("ASSIGN_USER", List.of())))
            .isInstanceOf(NoAssigneeFoundException.class);
    }

    @Test void emptyRoleMembersThrowNoAssignee() {
        Mockito.when(userRoleMapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> resolver().resolve("n1", AssigneeSpec.of("ROLE", List.of(99L))))
            .isInstanceOf(NoAssigneeFoundException.class);
    }

    @Test void resolve_self_returnsStarter() {
        var spec = new AssigneeSpec("SELF", List.of(), 1, 42L, List.of());
        assertThat(resolver().resolve("n1", spec)).containsExactly(42L);
    }

    @Test void resolve_selfSelect_returnsChosen() {
        var spec = new AssigneeSpec("SELF_SELECT", List.of(), 1, null, List.of(7L, 8L));
        assertThat(resolver().resolve("n1", spec)).containsExactly(7L, 8L);
    }

    @Test void resolve_selfSelect_emptyThrows() {
        var spec = new AssigneeSpec("SELF_SELECT", List.of(), 1, null, List.of());
        assertThatThrownBy(() -> resolver().resolve("n1", spec))
            .isInstanceOf(NoAssigneeFoundException.class);
    }

    @Test void resolve_leader_level1_usesDeptLeader() {
        Mockito.when(userMapper.selectById(42L)).thenReturn(user(42L, "ACTIVE", 10L));
        Mockito.when(deptMapper.selectById(10L)).thenReturn(dept(10L, 99L, null));
        Mockito.when(userMapper.selectById(99L)).thenReturn(user(99L, "ACTIVE", 10L));
        var spec = new AssigneeSpec("LEADER", List.of(), 1, 42L, List.of());
        assertThat(resolver().resolve("n1", spec)).containsExactly(99L);
    }
}

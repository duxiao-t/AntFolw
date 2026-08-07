package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AuthorizationServiceTest {
    private final JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
    private final AuthorizationService service = new AuthorizationService(jdbcTemplate);

    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
    }

    @Test
    void requireAdminDoesNotAcceptDelegatedPermissions() {
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "manager", "Manager",
            Set.of("manager"), Set.of("org.user.write", "security.user_role.write"),
            1L, 10L, null));

        assertThatThrownBy(service::requireAdmin)
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        PrincipalHolder.set(new PrincipalHolder.Principal(1L, "admin", List.of("admin")));
        assertThatCode(service::requireAdmin).doesNotThrowAnyException();
    }

    @Test
    void unrelatedAllScopeRoleDoesNotExpandCurrentAction() {
        var selfRole = new AuthorizationService.RoleGrant(1L, "operator",
            DataScope.SELF, Set.of());
        var unrelatedAllRole = new AuthorizationService.RoleGrant(2L, "reporter",
            DataScope.ALL, Set.of());
        var snapshot = new AuthorizationService.AuthzSnapshot(7L, 10L, false,
            Set.of("operator", "reporter"), Set.of("workflow.instance.read", "form.data.export"),
            Map.of(
                "workflow.instance.read", List.of(selfRole),
                "form.data.export", List.of(unrelatedAllRole)
            ));

        assertThat(service.inDataScope(snapshot, "workflow.instance.read", 8L, 10L)).isFalse();
        assertThat(service.inDataScope(snapshot, "workflow.instance.read", 7L, 99L)).isTrue();
    }

    @Test
    void customScopeOnlyAllowsConfiguredDepartment() {
        var role = new AuthorizationService.RoleGrant(3L, "custom-manager",
            DataScope.CUSTOM, Set.of(20L, 30L));
        var snapshot = new AuthorizationService.AuthzSnapshot(7L, 10L, false,
            Set.of("custom-manager"), Set.of("form.data.read"),
            Map.of("form.data.read", List.of(role)));

        assertThat(service.inDataScope(snapshot, "form.data.read", 8L, 20L)).isTrue();
        assertThat(service.inDataScope(snapshot, "form.data.read", 8L, 21L)).isFalse();
    }

    @Test
    void departmentScopeRequiresTheUsersCurrentDepartment() {
        var role = new AuthorizationService.RoleGrant(4L, "department-manager",
            DataScope.DEPARTMENT, Set.of());
        var snapshot = snapshot(role, 10L, false);

        assertThat(service.inDataScope(snapshot, "workflow.instance.read", 8L, 10L)).isTrue();
        assertThat(service.inDataScope(snapshot, "workflow.instance.read", 8L, 11L)).isFalse();
    }

    @Test
    void descendantScopeUsesTheDepartmentHierarchy() {
        var role = new AuthorizationService.RoleGrant(5L, "regional-manager",
            DataScope.DEPARTMENT_AND_DESCENDANTS, Set.of());
        var snapshot = snapshot(role, 10L, false);
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(20L), eq(10L)))
            .thenReturn(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(30L), eq(10L)))
            .thenReturn(false);

        assertThat(service.inDataScope(snapshot, "workflow.instance.read", 8L, 20L)).isTrue();
        assertThat(service.inDataScope(snapshot, "workflow.instance.read", 8L, 30L)).isFalse();
    }

    @Test
    void matchingAllScopeRoleExpandsTheCurrentAction() {
        var role = new AuthorizationService.RoleGrant(6L, "global-reader",
            DataScope.ALL, Set.of());
        var snapshot = snapshot(role, 10L, false);

        assertThat(service.inDataScope(snapshot, "workflow.instance.read", 99L, 999L)).isTrue();
    }

    @Test
    void administratorBypassesDataScopeChecks() {
        var snapshot = new AuthorizationService.AuthzSnapshot(7L, null, true,
            Set.of("admin"), Set.of(), Map.of());

        assertThat(service.inDataScope(snapshot, "workflow.instance.read", 99L, null)).isTrue();
    }

    private static AuthorizationService.AuthzSnapshot snapshot(
            AuthorizationService.RoleGrant role, Long departmentId, boolean admin) {
        return new AuthorizationService.AuthzSnapshot(7L, departmentId, admin,
            Set.of(role.code()), Set.of("workflow.instance.read"),
            Map.of("workflow.instance.read", List.of(role)));
    }
}

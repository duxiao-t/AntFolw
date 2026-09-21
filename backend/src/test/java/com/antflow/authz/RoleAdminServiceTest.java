package com.antflow.authz;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 授权天花板：非管理员不得授予自己不具备的能力，也不得授予比自己更宽的数据范围。
 *
 * <p>该规则在 9e76add 被删除后长期缺失，今天对非管理员不可达
 * （角色配置入口由超管专属的 security:role:manage 把守），因此这里是防御纵深而不是活漏洞。
 */
class RoleAdminServiceTest {
    private final JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
    private final AuthorizationService authorizationService = Mockito.mock(AuthorizationService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final RoleAdminService service =
        new RoleAdminService(jdbcTemplate, authorizationService, auditService);

    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
    }

    private static void asNonAdminHolding(String... permissions) {
        PrincipalHolder.set(new PrincipalHolder.Principal(9L, "manager", "Manager",
            Set.of("manager"), Set.of(permissions), 1L, 10L, null));
    }

    private static RoleAdminService.PermissionGrantWriteRequest grant(String code, String scope) {
        return new RoleAdminService.PermissionGrantWriteRequest(code, scope, Set.of());
    }

    @Test
    void rejectsGrantingAPermissionTheGrantorDoesNotHold() {
        asNonAdminHolding(PermissionCodes.SECURITY_ROLE_MANAGE);

        assertThatThrownBy(() -> service.validateGrantCeiling(
            grant(PermissionCodes.ORG_USER_MANAGE, null)))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("cannot grant permissions you do not hold");
    }

    @Test
    void rejectsGrantingAWiderScopeThanTheGrantorHolds() {
        asNonAdminHolding(PermissionCodes.FORM_DATA_READ);
        var heldSelf = new AuthorizationService.RoleGrant(1L, "manager", DataScope.SELF, Set.of());
        when(authorizationService.snapshot(9L)).thenReturn(new AuthorizationService.AuthzSnapshot(
            9L, 10L, false, Set.of("manager"), Set.of(PermissionCodes.FORM_DATA_READ),
            Map.of(PermissionCodes.FORM_DATA_READ, List.of(heldSelf))));

        assertThatThrownBy(() -> service.validateGrantCeiling(
            grant(PermissionCodes.FORM_DATA_READ, "ALL")))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("cannot grant a wider data scope than you hold");
    }

    @Test
    void allowsEqualOrNarrowerScopeAndTheRecommendedDefault() {
        asNonAdminHolding(PermissionCodes.FORM_DATA_READ);
        var heldDepartmentAndBelow = new AuthorizationService.RoleGrant(
            1L, "manager", DataScope.DEPARTMENT_AND_DESCENDANTS, Set.of());
        when(authorizationService.snapshot(9L)).thenReturn(new AuthorizationService.AuthzSnapshot(
            9L, 10L, false, Set.of("manager"), Set.of(PermissionCodes.FORM_DATA_READ),
            Map.of(PermissionCodes.FORM_DATA_READ, List.of(heldDepartmentAndBelow))));

        // 同宽与更窄都放行
        assertThatCode(() -> service.validateGrantCeiling(
            grant(PermissionCodes.FORM_DATA_READ, "DEPARTMENT_AND_DESCENDANTS")))
            .doesNotThrowAnyException();
        assertThatCode(() -> service.validateGrantCeiling(
            grant(PermissionCodes.FORM_DATA_READ, "SELF")))
            .doesNotThrowAnyException();
        // 不写覆盖值即"使用能力默认范围"，是推荐用法，不应被拦
        assertThatCode(() -> service.validateGrantCeiling(
            grant(PermissionCodes.FORM_DATA_READ, null)))
            .doesNotThrowAnyException();
    }

    @Test
    void administratorBypassesTheCeiling() {
        PrincipalHolder.set(new PrincipalHolder.Principal(1L, "admin", List.of("admin")));

        assertThatCode(() -> service.validateGrantCeiling(
            grant(PermissionCodes.SYSTEM_BACKUP_MANAGE, "ALL")))
            .doesNotThrowAnyException();
    }
}

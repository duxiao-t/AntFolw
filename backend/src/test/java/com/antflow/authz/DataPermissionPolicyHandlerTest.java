package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 数据权限执行层的行为约束：admin/无主体不注入、无能力 fail-closed、四种范围生成正确片段。
 */
class DataPermissionPolicyHandlerTest {
    private final AuthorizationService authorizationService =
        Mockito.mock(AuthorizationService.class);
    private final DataPermissionPolicyHandler handler =
        new DataPermissionPolicyHandler(authorizationService);
    private static final String FORM_DATA_STATEMENT =
        "com.antflow.form.runtime.FormDataMapper.selectList";
    private static final Table FORM_DATA_TABLE = new Table("t_form_data");
    private static final Table FORM_DEFINITION_TABLE = new Table("t_form_definition");

    @AfterEach
    void clear() {
        PrincipalHolder.clear();
    }

    @Test
    void skipsTablesAndStatementsOutsideTheRules() {
        assertThat(handler.getSqlSegment(new Table("t_task"), null, FORM_DATA_STATEMENT))
            .as("未配置的表不注入").isNull();
        assertThat(handler.getSqlSegment(FORM_DATA_TABLE, null,
            "com.antflow.form.runtime.FormDataMapper.selectById"))
            .as("未开启的语句不注入").isNull();
    }

    @Test
    void skipsSystemContextWithoutPrincipal() {
        when(authorizationService.currentDataScope(anyString())).thenReturn(Optional.empty());
        assertThat(handler.getSqlSegment(FORM_DATA_TABLE, null, FORM_DATA_STATEMENT)).isNull();
    }

    @Test
    void administratorInjectsNothing() {
        when(authorizationService.currentDataScope(PermissionCodes.FORM_DATA_READ))
            .thenReturn(Optional.of(new AuthorizationService.DataScopeFilter(
                true, true, false, 7L, Set.of())));

        assertThat(handler.getSqlSegment(FORM_DATA_TABLE, null, FORM_DATA_STATEMENT)).isNull();
    }

    @Test
    void nonAdminAllScopeDoesNotDependOnFormUsageGrant() {
        when(authorizationService.currentDataScope(PermissionCodes.FORM_DATA_READ))
            .thenReturn(Optional.of(new AuthorizationService.DataScopeFilter(
                false, true, false, 7L, Set.of())));

        String segment = handler.getSqlSegment(FORM_DATA_TABLE, null, FORM_DATA_STATEMENT)
            .toString();

        assertThat(segment).isEqualTo("1 = 1").doesNotContain("t_form_resource_grant");
    }

    @Test
    void missingCapabilityFailsClosed() {
        when(authorizationService.currentDataScope(PermissionCodes.FORM_DATA_READ))
            .thenReturn(Optional.of(new AuthorizationService.DataScopeFilter(
                false, false, false, 7L, Set.of())));

        String segment = DataPermissionPolicyHandler.buildSegment(FORM_DATA_TABLE,
            DataPermissionRules.FORM_DATA,
            new AuthorizationService.DataScopeFilter(false, false, false, 7L, Set.of()));

        assertThat(segment).contains("1 = 0");
    }

    @Test
    void selfScopeUsesOwnerColumnWithoutFormUsageGrant() {
        String segment = DataPermissionPolicyHandler.buildSegment(FORM_DATA_TABLE,
            DataPermissionRules.FORM_DATA,
            new AuthorizationService.DataScopeFilter(false, false, true, 7L, Set.of()));

        assertThat(segment).contains("t_form_data.created_by = 7");
        assertThat(segment).doesNotContain("t_form_resource_grant");
        assertThatCode(() -> CCJSqlParserUtil.parseCondExpression(segment))
            .doesNotThrowAnyException();
    }

    @Test
    void departmentScopeUsesDepartmentListAndAlias() {
        Table aliased = new Table("t_form_data");
        aliased.setAlias(new net.sf.jsqlparser.expression.Alias("fd"));
        String segment = DataPermissionPolicyHandler.buildSegment(aliased,
            DataPermissionRules.FORM_DATA,
            new AuthorizationService.DataScopeFilter(false, false, false, 7L, Set.of(3L, 5L)));

        assertThat(segment).contains("scope_pi.form_data_id = fd.id");
        assertThat(segment).contains("scope_pi.started_dept_id IN (3,5)");
        assertThatCode(() -> CCJSqlParserUtil.parseCondExpression(segment))
            .doesNotThrowAnyException();
    }

    @Test
    void selfAndDepartmentScopesAreUnioned() {
        String segment = DataPermissionPolicyHandler.buildSegment(FORM_DATA_TABLE,
            DataPermissionRules.FORM_DATA,
            new AuthorizationService.DataScopeFilter(false, false, true, 7L, Set.of(3L)));

        assertThat(segment).contains(" OR ");
        assertThat(segment).contains("t_form_data.created_by = 7");
        assertThat(segment).contains("IN (3)");
    }

    @Test
    void formDefinitionListRequiresMaintainerAndIgnoresDepartmentScope() {
        String segment = DataPermissionPolicyHandler.buildSegment(FORM_DEFINITION_TABLE,
            DataPermissionRules.FORM_DEFINITION,
            new AuthorizationService.DataScopeFilter(false, false, false, 5L, Set.of(5L)));

        assertThat(segment).contains("t_form_maintainer");
        assertThat(segment).contains("maintainer.user_id = 5");
        assertThat(segment).doesNotContain("t_form_resource_grant", "scope_owner");
        assertThatCode(() -> CCJSqlParserUtil.parseCondExpression(segment))
            .doesNotThrowAnyException();
    }

    @Test
    void formDataListRequiresOnlyCapabilityScope() {
        String segment = DataPermissionPolicyHandler.buildSegment(FORM_DATA_TABLE,
            DataPermissionRules.FORM_DATA,
            new AuthorizationService.DataScopeFilter(false, false, false, 5L, Set.of(5L)));

        assertThat(segment).startsWith("(EXISTS (SELECT 1 FROM t_process_instance");
        assertThat(segment).doesNotContain("t_form_resource_grant");
    }

    @Test
    void scopeRegistrationMatchesSnapshotInsteadOfAllowingEverything() {
        // 证明 handler 依赖的是 AuthorizationService 的有效范围，而不是自行猜测
        var role = new AuthorizationService.RoleGrant(1L, "dept_manager",
            DataScope.DEPARTMENT, Set.of());
        var snapshot = new AuthorizationService.AuthzSnapshot(7L, 3L, false,
            Set.of("dept_manager"), Set.of(PermissionCodes.FORM_DATA_READ),
            Map.of(PermissionCodes.FORM_DATA_READ, List.of(role)));
        when(authorizationService.currentSnapshot()).thenReturn(snapshot);
        when(authorizationService.manageableDepartments(snapshot, PermissionCodes.FORM_DATA_READ))
            .thenReturn(Set.of(3L));
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "u", "U", Set.of("dept_manager"),
            Set.of(PermissionCodes.FORM_DATA_READ), 1L, 3L, null));
        when(authorizationService.currentDataScope(any())).thenCallRealMethod();

        var scope = authorizationService.currentDataScope(PermissionCodes.FORM_DATA_READ);
        assertThat(scope).isPresent();
        assertThat(scope.get().unrestricted()).isFalse();
        assertThat(scope.get().departmentIds()).containsExactly(3L);
    }

    @Test
    void controlledStatementRegistryChangesExplicitly() {
        assertThat(DataPermissionRules.RULES)
            .extracting(DataPermissionRules.Rule::table, DataPermissionRules.Rule::statements)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("t_form_data",
                    Set.of("com.antflow.form.runtime.FormDataMapper.selectList")),
                org.assertj.core.groups.Tuple.tuple("t_form_definition",
                    Set.of("com.antflow.form.FormDefinitionMapper.selectSummaryPage")));
    }
}

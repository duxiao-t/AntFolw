package com.antflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code workflow:monitor:read} 是可配范围的能力，但控制器跑的是裸 JdbcTemplate，
 * 范围得每条查询自己注入——漏一条就是 fail-open。这里不逐条写期望，而是把所有下发的 SQL
 * 抓出来统一断言：将来加第六条查询却忘了带谓词，这个用例会失败。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowMonitoringControllerTest {
    @Mock private JdbcTemplate jdbc;
    @Mock private AuthorizationService authorization;

    private WorkflowMonitoringController controller() {
        lenient().when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        lenient().when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of());
        return new WorkflowMonitoringController(jdbc, authorization);
    }

    private List<String> issuedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).queryForList(sql.capture(), any(Object[].class));
        verify(jdbc, atLeastOnce()).queryForMap(sql.capture(), any(Object[].class));
        return sql.getAllValues();
    }

    @Test
    void everyQueryIsScopedToTheCallersOwnOrDepartmentInstances() {
        when(authorization.currentDataScope(PermissionCodes.WORKFLOW_MONITOR_READ))
            .thenReturn(Optional.of(new AuthorizationService.DataScopeFilter(
                false, false, true, 7L, Set.of(10L, 11L))));

        controller().overview(50);

        assertThat(issuedSql()).isNotEmpty().allSatisfy(sql ->
            assertThat(sql).contains("started_by = ?").contains("started_dept_id IN (?,?)"));
    }

    @Test
    void aScopeWithNeitherSelfNorDepartmentsBecomesAFalsePredicate() {
        when(authorization.currentDataScope(PermissionCodes.WORKFLOW_MONITOR_READ))
            .thenReturn(Optional.of(new AuthorizationService.DataScopeFilter(
                false, false, false, 7L, Set.of())));

        controller().overview(50);

        // fail-closed：不能退化成"不过滤"
        assertThat(issuedSql()).isNotEmpty().allSatisfy(sql ->
            assertThat(sql).contains("1 = 0").doesNotContain("started_by = ?"));
    }

    @Test
    void adminAndAllScopeAreNotFiltered() {
        when(authorization.currentDataScope(PermissionCodes.WORKFLOW_MONITOR_READ))
            .thenReturn(Optional.of(new AuthorizationService.DataScopeFilter(
                true, true, false, 7L, Set.of())));

        controller().overview(50);

        assertThat(issuedSql()).isNotEmpty().allSatisfy(sql ->
            assertThat(sql).contains("1 = 1").doesNotContain("1 = 0"));
    }

    @Test
    void outboxIsJoinedToInstancesAndOtherAggregateTypesAreExcluded() {
        when(authorization.currentDataScope(PermissionCodes.WORKFLOW_MONITOR_READ))
            .thenReturn(Optional.of(new AuthorizationService.DataScopeFilter(
                false, false, true, 7L, Set.of())));

        controller().overview(50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForMap(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
            .contains("t_workflow_outbox")
            .contains("outbox.aggregate_type = 'PROCESS_INSTANCE'")
            .contains("instance.id = outbox.aggregate_id");
    }
}

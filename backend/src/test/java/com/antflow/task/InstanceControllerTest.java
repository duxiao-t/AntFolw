package com.antflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.automation.WorkflowJobService;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormDataMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InstanceControllerTest {
    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
    }

    @Test
    void listRejectsEqualOrReversedDateBoundsBeforeQuerying() {
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "reviewer", List.of("user")));
        var instances = mock(ProcessInstanceMapper.class);
        var controller = listController(instances, mock(AuthorizationService.class));
        OffsetDateTime from = OffsetDateTime.parse("2026-09-18T00:00:00+08:00");

        for (OffsetDateTime to : List.of(from, from.minusDays(1))) {
            assertThatThrownBy(() -> controller.list(
                "authorized", 1, 20, null, null, null, from, to))
                .isInstanceOfSatisfying(BizException.class, error ->
                    assertThat(error.getCode()).isEqualTo("BAD_QUERY"));
        }
        verifyNoInteractions(instances);
    }

    @Test
    void listMapsBusinessFieldsAndPassesTheSameFiltersToPageAndCount() throws Exception {
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "reviewer", List.of("user")));
        var instances = mock(ProcessInstanceMapper.class);
        var authorization = mock(AuthorizationService.class);
        when(authorization.hasPermission("workflow:instance:read")).thenReturn(true);
        OffsetDateTime from = OffsetDateTime.parse("2026-09-01T00:00:00+08:00");
        OffsetDateTime to = from.plusDays(1);
        var instance = new ProcessInstance();
        instance.setId(41L);
        instance.setFormCode("purchase");
        instance.setFormName("采购申请");
        instance.setBusinessNo("000000000041");
        instance.setApplicantName("林晓");
        instance.setApplicantEmployeeNo("000009");
        instance.setApplicantDepartment("采购部");
        when(instances.selectInstancePage(7L, false, false, true, "authorized",
            "RUNNING", null, "采购", from, to, 20, 20)).thenReturn(List.of(instance));
        when(instances.countInstancePage(7L, false, false, true, "authorized",
            "RUNNING", null, "采购", from, to)).thenReturn(1L);

        MockMvcBuilders.standaloneSetup(listController(instances, authorization)).build()
            .perform(get("/api/instances").param("page", "2").param("status", "RUNNING")
                .param("keyword", " 采购 ").param("from", from.toString()).param("to", to.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.records[0].formName").value("采购申请"))
            .andExpect(jsonPath("$.records[0].formCode").value("purchase"))
            .andExpect(jsonPath("$.records[0].businessNo").value("000000000041"))
            .andExpect(jsonPath("$.records[0].applicantName").value("林晓"))
            .andExpect(jsonPath("$.records[0].applicantEmployeeNo").value("000009"))
            .andExpect(jsonPath("$.records[0].applicantDepartment").value("采购部"))
            .andExpect(jsonPath("$.total").value(1));
        verify(instances).selectInstancePage(7L, false, false, true, "authorized",
            "RUNNING", null, "采购", from, to, 20, 20);
        verify(instances).countInstancePage(7L, false, false, true, "authorized",
            "RUNNING", null, "采购", from, to);
    }

    private InstanceController listController(ProcessInstanceMapper instances,
                                              AuthorizationService authorization) {
        return new InstanceController(mock(ProcessEngine.class), instances, mock(TaskMapper.class),
            mock(TaskHistoryMapper.class), mock(WorkflowJobService.class), authorization,
            mock(AuditService.class), mock(FormDefinitionService.class), mock(FormDataMapper.class));
    }

    @Test
    void historicalParticipantReceivesSummaryWithoutSensitivePayload() {
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "reviewer", List.of("user")));
        ProcessInstanceMapper instances = mock(ProcessInstanceMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TaskHistoryMapper history = mock(TaskHistoryMapper.class);
        WorkflowJobService jobs = mock(WorkflowJobService.class);
        AuthorizationService authorization = mock(AuthorizationService.class);
        FormDefinitionService forms = mock(FormDefinitionService.class);
        FormDataMapper formData = mock(FormDataMapper.class);

        ProcessInstance instance = new ProcessInstance();
        instance.setId(41L);
        instance.setFormDataId(51L);
        instance.setProcessSnapshot("{\"secret\":true}");
        instance.setStatus("RUNNING");
        instance.setCurrentNodeId("manager");
        instance.setStartedBy(8L);
        instance.setStartedAt(OffsetDateTime.parse("2026-08-24T09:00:00+08:00"));
        TaskEntity task = new TaskEntity();
        task.setId(61L);
        task.setNodeId("manager");
        task.setAssigneeId(7L);
        task.setStatus("APPROVED");
        task.setComment("sensitive comment outside summary task fields");

        when(authorization.instanceVisibility(41L, 7L))
            .thenReturn(AuthorizationService.InstanceVisibility.SUMMARY);
        when(instances.selectById(41L)).thenReturn(instance);
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(history.selectList(any())).thenReturn(List.of());

        InstanceController controller = new InstanceController(mock(ProcessEngine.class),
            instances, tasks, history, jobs, authorization, mock(AuditService.class), forms,
            formData);

        Map<String, Object> result = controller.detail(41L);

        assertThat(result).containsEntry("visibility", "SUMMARY")
            .doesNotContainKeys("schema", "formData", "automationJobs");
        Map<?, ?> summaryInstance = (Map<?, ?>) result.get("instance");
        assertThat(summaryInstance.containsKey("formDataId")).isFalse();
        assertThat(summaryInstance.containsKey("processSnapshot")).isFalse();
        Map<?, ?> summaryTask = (Map<?, ?>) ((List<?>) result.get("tasks")).get(0);
        assertThat(summaryTask.containsKey("comment")).isFalse();
        assertThat(summaryTask.containsKey("procInstId")).isFalse();
        var taskQuery = org.mockito.ArgumentCaptor.forClass(
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(tasks).selectList(taskQuery.capture());
        assertThat(taskQuery.getValue().getSqlSegment().toUpperCase()).contains("STATUS <>");
        var historyQuery = org.mockito.ArgumentCaptor.forClass(
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(history).selectList(historyQuery.capture());
        assertThat(historyQuery.getValue().getSqlSegment().toUpperCase()).contains("ACTION <>");
        verifyNoInteractions(forms, formData, jobs);
    }
}

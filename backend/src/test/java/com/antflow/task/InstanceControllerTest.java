package com.antflow.task;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormDataMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InstanceControllerTest {
    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
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

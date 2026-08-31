package com.antflow.workplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionMapper;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskOperationService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkplaceControllerTest {
    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
    }

    @Test
    void overviewReturnsOnlyVisibleWorkflowData() {
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "operator", List.of("operator")));
        var authorization = mock(AuthorizationService.class);
        doNothing().when(authorization).requirePermission("page.workplace");
        when(authorization.hasPermission("workflow.instance.read")).thenReturn(true);
        when(authorization.hasPermission("workflow.task.read")).thenReturn(true);

        var task = new TaskEntity();
        task.setId(51L);
        task.setProcInstId(41L);
        task.setNodeId("manager");
        task.setStatus("PENDING");
        task.setCreatedAt(OffsetDateTime.now());

        var instance = new ProcessInstance();
        instance.setId(41L);
        instance.setProcDefId(31L);
        instance.setStartedBy(7L);
        instance.setStatus("RUNNING");
        instance.setCurrentNodeId("manager");
        instance.setStartedAt(OffsetDateTime.now());

        var process = new ProcessDefinition();
        process.setFormDefId(21L);
        var form = new FormDefinition();
        form.setName("采购申请");
        var user = new User();
        user.setDisplayName("运营员");

        var tasks = mock(TaskOperationService.class);
        when(tasks.listMyInbox(7L, "PENDING", 8)).thenReturn(List.of(task));
        when(tasks.countMyInbox(7L, "PENDING")).thenReturn(1L);
        var instances = mock(ProcessInstanceMapper.class);
        when(instances.selectWorkplaceRecent(eq(7L), eq(false), eq(true), eq(true), eq(8)))
            .thenReturn(List.of(instance));
        when(instances.selectBatchIds(any())).thenReturn(List.of(instance));
        when(instances.selectWorkplaceStatusCounts(eq(7L), eq(false), eq(true), eq(true),
            any(), any()))
            .thenReturn(List.of(Map.of("status", "RUNNING", "total", 1L,
                "finished_today", 0L)));
        var processes = mock(ProcessDefinitionMapper.class);
        process.setId(31L);
        when(processes.selectBatchIds(any())).thenReturn(List.of(process));
        var forms = mock(FormDefinitionMapper.class);
        form.setId(21L);
        when(forms.selectBatchIds(any())).thenReturn(List.of(form));
        var users = mock(UserMapper.class);
        user.setId(7L);
        when(users.selectBatchIds(any())).thenReturn(List.of(user));

        var controller = new WorkplaceController(authorization, tasks, instances, processes, forms, users);

        var result = controller.overview();

        assertThat(result.pendingTasks()).isEqualTo(1);
        assertThat(result.runningInstances()).isEqualTo(1);
        assertThat(result.pendingTaskItems()).singleElement()
            .extracting(WorkplaceController.PendingTaskItem::formName, WorkplaceController.PendingTaskItem::applicantName)
            .containsExactly("采购申请", "运营员");
        assertThat(result.statusBreakdown()).containsEntry("RUNNING", 1L);
    }

    @Test
    void overviewDoesNotReadWorkflowDataWithoutWorkflowPermission() {
        PrincipalHolder.set(new PrincipalHolder.Principal(8L, "viewer", List.of("viewer")));
        var authorization = mock(AuthorizationService.class);
        doNothing().when(authorization).requirePermission("page.workplace");
        when(authorization.hasPermission("workflow.instance.read")).thenReturn(false);
        when(authorization.hasPermission("workflow.task.read")).thenReturn(false);
        var tasks = mock(TaskOperationService.class);

        var controller = new WorkplaceController(authorization, tasks, mock(ProcessInstanceMapper.class),
            mock(ProcessDefinitionMapper.class), mock(FormDefinitionMapper.class), mock(UserMapper.class));

        var result = controller.overview();

        assertThat(result.pendingTasks()).isZero();
        assertThat(result.recentInstanceItems()).isEmpty();
        verify(tasks, never()).listMyInbox(8L, "PENDING", 8);
        verify(tasks, never()).countMyInbox(8L, "PENDING");
    }
}

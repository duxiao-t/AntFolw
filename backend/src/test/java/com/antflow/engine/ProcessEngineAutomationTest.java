package com.antflow.engine;

import com.antflow.automation.WorkflowJob;
import com.antflow.automation.WorkflowJobMapper;
import com.antflow.common.FormalNumberService;
import com.antflow.engine.handler.ApprovalHandler;
import com.antflow.engine.handler.EmptyHandler;
import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.notify.NotificationPublisher;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskHistoryEntity;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.antflow.task.TaskMapperExt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessEngineAutomationTest {
    @Test
    void blockingCompletionAdvancesExactlyOnce() {
        ObjectMapper json = new ObjectMapper();
        TaskMapper tasks = mock(TaskMapper.class);
        TaskHistoryMapper histories = mock(TaskHistoryMapper.class);
        ProcessInstanceMapper instances = mock(ProcessInstanceMapper.class);
        FormDataMapper formDataMapper = mock(FormDataMapper.class);
        WorkflowJobMapper jobs = mock(WorkflowJobMapper.class);
        AssigneeResolver resolver = mock(AssigneeResolver.class);
        when(resolver.resolve(eq("approval-1"), any())).thenReturn(List.of(42L));

        String snapshot = """
            {"id":"root","type":"ROOT","children":{"id":"delay-1","type":"DELAY",
              "props":{"mode":"DURATION","amount":1,"unit":"MINUTES"},"children":{
              "id":"approval-1","type":"APPROVAL","props":{"assignedType":"ASSIGN_USER",
              "assignedUser":[42],"mode":"OR"}}}}
            """;
        ProcessInstance instance = new ProcessInstance();
        instance.setId(9L);
        instance.setStatus("RUNNING");
        instance.setStartedBy(7L);
        instance.setFormDataId(3L);
        instance.setProcessSnapshot(snapshot);
        instance.setCurrentNodeId("delay-1");
        when(instances.selectForUpdate(9L)).thenReturn(instance);
        FormData data = new FormData();
        data.setData("{}");
        when(formDataMapper.selectById(3L)).thenReturn(data);

        WorkflowJob job = new WorkflowJob();
        job.setId(5L);
        job.setProcInstId(9L);
        job.setNodeId("delay-1");
        job.setJobType("DELAY");
        job.setStatus("RUNNING");
        job.setBlocking(true);
        job.setDeliveryId(UUID.randomUUID());
        when(jobs.selectForUpdate(5L)).thenReturn(job);
        when(tasks.insert(any(TaskEntity.class))).thenAnswer(invocation -> {
            ((TaskEntity) invocation.getArgument(0)).setId(11L);
            return 1;
        });

        var handlers = List.of(
            new EmptyHandler(),
            new ApprovalHandler(resolver, tasks, histories)
        );
        ProcessEngine engine = new ProcessEngine(
            mock(FormDefinitionService.class), formDataMapper,
            mock(ProcessDefinitionService.class), tasks, instances,
            new TaskMapperExt(instances), histories, handlers,
            mock(NotificationPublisher.class), json, mock(FormalNumberService.class), jobs
        );

        assertThat(engine.completeAutomation(5L)).isTrue();
        assertThat(engine.completeAutomation(5L)).isFalse();

        verify(tasks, times(1)).insert(any(TaskEntity.class));
        ArgumentCaptor<TaskHistoryEntity> captor = ArgumentCaptor.forClass(TaskHistoryEntity.class);
        verify(histories, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(TaskHistoryEntity::getAction)
            .containsExactly("DELAY_COMPLETED", "ARRIVE");
        assertThat(instance.getCurrentNodeId()).isEqualTo("approval-1");
    }

    @Test
    void nonBlockingTriggerCanSucceedAfterInstanceCompletion() {
        WorkflowJobMapper jobs = mock(WorkflowJobMapper.class);
        ProcessInstanceMapper instances = mock(ProcessInstanceMapper.class);
        WorkflowJob job = new WorkflowJob();
        job.setId(6L);
        job.setProcInstId(9L);
        job.setNodeId("trigger-1");
        job.setJobType("TRIGGER");
        job.setStatus("RUNNING");
        job.setBlocking(false);
        job.setDeliveryId(UUID.randomUUID());
        when(jobs.selectForUpdate(6L)).thenReturn(job);
        ProcessInstance instance = new ProcessInstance();
        instance.setId(9L);
        instance.setStatus("APPROVED");
        when(instances.selectForUpdate(9L)).thenReturn(instance);
        TaskHistoryMapper histories = mock(TaskHistoryMapper.class);

        ProcessEngine engine = new ProcessEngine(
            mock(FormDefinitionService.class), mock(FormDataMapper.class),
            mock(ProcessDefinitionService.class), mock(TaskMapper.class), instances,
            new TaskMapperExt(instances), histories, List.of(),
            mock(NotificationPublisher.class), new ObjectMapper(),
            mock(FormalNumberService.class), jobs
        );

        assertThat(engine.completeAutomation(6L)).isTrue();
        assertThat(job.getStatus()).isEqualTo("SUCCEEDED");
        verify(histories).insert(any(TaskHistoryEntity.class));
    }
}

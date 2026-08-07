package com.antflow.automation;

import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskHistoryEntity;
import com.antflow.task.TaskHistoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowJobServiceTest {
    private final WorkflowJobMapper mapper = mock(WorkflowJobMapper.class);
    private final TaskHistoryMapper histories = mock(TaskHistoryMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AutomationProperties properties = new AutomationProperties();
    private final WebhookSecurityPolicy security = mock(WebhookSecurityPolicy.class);
    private final WorkflowJobService service =
        new WorkflowJobService(mapper, histories, json, properties, security);

    @Test
    void resolvesFormFieldBindingsWhenQueueingTrigger() throws Exception {
        ProcessInstance instance = new ProcessInstance();
        instance.setId(9L);
        var node = json.readTree("""
            {"id":"trigger-1","type":"TRIGGER","props":{
              "method":"POST","url":"https://hooks.example.com/flow",
              "contentType":"application/json","secret":"12345678",
              "continueMode":"ON_SUCCESS","headers":[],"parameters":[
                {"key":"amount","source":"FIELD","fieldId":"total"},
                {"key":"source","source":"FIXED","value":"antflow"}
              ]}}
            """);
        var formData = json.readTree("{\"total\":42}");

        service.queueTrigger(instance, node, formData, 7L);

        ArgumentCaptor<WorkflowJob> captor = ArgumentCaptor.forClass(WorkflowJob.class);
        verify(mapper).insert(captor.capture());
        var payload = json.readTree(captor.getValue().getPayload());
        assertThat(payload.path("parameters").path("amount").asInt()).isEqualTo(42);
        assertThat(payload.path("parameters").path("source").asText()).isEqualTo("antflow");
        assertThat(captor.getValue().getBlocking()).isTrue();
    }

    @Test
    void retriesWithBackoffThenFailsOnEighthAttempt() {
        WorkflowJob retrying = runningJob(1);
        when(mapper.selectForUpdate(1L)).thenReturn(retrying);
        service.recordFailure(1L, "HTTP 503");
        assertThat(retrying.getStatus()).isEqualTo("SCHEDULED");
        assertThat(retrying.getScheduledAt()).isAfter(OffsetDateTime.now().minusSeconds(1));

        WorkflowJob exhausted = runningJob(8);
        when(mapper.selectForUpdate(2L)).thenReturn(exhausted);
        service.recordFailure(2L, "HTTP 500");
        assertThat(exhausted.getStatus()).isEqualTo("FAILED");
        assertThat(exhausted.getCompletedAt()).isNotNull();
        ArgumentCaptor<TaskHistoryEntity> history = ArgumentCaptor.forClass(TaskHistoryEntity.class);
        verify(histories).insert(history.capture());
        assertThat(history.getValue().getAction()).isEqualTo("TRIGGER_FAILED");
    }

    private WorkflowJob runningJob(int attempts) {
        WorkflowJob job = new WorkflowJob();
        job.setId((long) attempts);
        job.setProcInstId(9L);
        job.setNodeId("trigger-1");
        job.setJobType("TRIGGER");
        job.setStatus("RUNNING");
        job.setAttempts(attempts);
        job.setMaxAttempts(8);
        return job;
    }
}

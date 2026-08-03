package com.antflow.engine.handler;

import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.engine.resolver.AssigneeSpec;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

class ApprovalHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void selfSelectUsesProcessNodeIdForSubmittedSelection() throws Exception {
        AssigneeResolver resolver = Mockito.mock(AssigneeResolver.class);
        TaskMapper taskMapper = Mockito.mock(TaskMapper.class);
        TaskHistoryMapper historyMapper = Mockito.mock(TaskHistoryMapper.class);
        ApprovalHandler handler = new ApprovalHandler(resolver, taskMapper, historyMapper);
        Mockito.when(resolver.resolve(Mockito.eq("manager"), any(AssigneeSpec.class)))
            .thenReturn(List.of(42L));
        ProcessInstance instance = new ProcessInstance();
        instance.setId(501L);
        var node = objectMapper.readTree("""
            {"id":"manager","type":"APPROVAL","props":{"assignedType":"SELF_SELECT","mode":"OR"}}
            """);
        NodeContext context = new NodeContext(7L, objectMapper.createObjectNode(),
            Map.of("manager", List.of(42L)), "root");

        handler.handle(objectMapper.createObjectNode(), node, instance, context);

        ArgumentCaptor<AssigneeSpec> captor = ArgumentCaptor.forClass(AssigneeSpec.class);
        Mockito.verify(resolver).resolve(Mockito.eq("manager"), captor.capture());
        assertThat(captor.getValue().selfSelected()).containsExactly(42L);
    }
}

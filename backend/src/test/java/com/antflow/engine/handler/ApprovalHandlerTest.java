package com.antflow.engine.handler;

import com.antflow.engine.BizException;
import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.engine.WorkflowRuntimeV2;
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
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
            Map.of("manager", List.of(42L)), "root", null, null);

        handler.handle(objectMapper.createObjectNode(), node, instance, context);

        ArgumentCaptor<AssigneeSpec> captor = ArgumentCaptor.forClass(AssigneeSpec.class);
        Mockito.verify(resolver).resolve(Mockito.eq("manager"), captor.capture());
        assertThat(captor.getValue().selfSelected()).containsExactly(42L);
    }

    @Test
    void selfSelectDefaultsToSingleAndRejectsEmptyOrMultipleSelection() throws Exception {
        AssigneeResolver resolver = Mockito.mock(AssigneeResolver.class);
        TaskMapper taskMapper = Mockito.mock(TaskMapper.class);
        TaskHistoryMapper historyMapper = Mockito.mock(TaskHistoryMapper.class);
        ApprovalHandler handler = new ApprovalHandler(resolver, taskMapper, historyMapper);
        ProcessInstance instance = new ProcessInstance();
        instance.setId(501L);
        var node = objectMapper.readTree("""
            {"id":"manager","type":"APPROVAL","props":{"assignedType":"SELF_SELECT"}}
            """);

        assertThatThrownBy(() -> handler.handle(objectMapper.createObjectNode(), node, instance,
            new NodeContext(7L, objectMapper.createObjectNode(), Map.of(), "root", null, null)))
            .isInstanceOf(BizException.class)
            .satisfies(error -> assertThat(((BizException) error).getCode())
                .isEqualTo("SELF_SELECT_REQUIRED"));
        assertThatThrownBy(() -> handler.handle(objectMapper.createObjectNode(), node, instance,
            new NodeContext(7L, objectMapper.createObjectNode(),
                Map.of("manager", List.of(41L, 42L)), "root", null, null)))
            .isInstanceOf(BizException.class)
            .satisfies(error -> assertThat(((BizException) error).getCode())
                .isEqualTo("SELF_SELECT_MULTIPLE_NOT_ALLOWED"));
        Mockito.verifyNoInteractions(resolver, taskMapper, historyMapper);
    }

    @Test
    void selfSelectMultipleAllowsMoreThanOneDistinctSelection() throws Exception {
        AssigneeResolver resolver = Mockito.mock(AssigneeResolver.class);
        TaskMapper taskMapper = Mockito.mock(TaskMapper.class);
        TaskHistoryMapper historyMapper = Mockito.mock(TaskHistoryMapper.class);
        ApprovalHandler handler = new ApprovalHandler(resolver, taskMapper, historyMapper);
        Mockito.when(resolver.resolve(Mockito.eq("manager"), any(AssigneeSpec.class)))
            .thenReturn(List.of(41L, 42L));
        ProcessInstance instance = new ProcessInstance();
        instance.setId(501L);
        var node = objectMapper.readTree("""
            {"id":"manager","type":"APPROVAL","props":{"assignedType":"SELF_SELECT",
              "selfSelect":{"multiple":true}}}
            """);

        handler.handle(objectMapper.createObjectNode(), node, instance,
            new NodeContext(7L, objectMapper.createObjectNode(),
                Map.of("manager", List.of(41L, 42L, 42L)), "root", null, null));

        ArgumentCaptor<AssigneeSpec> captor = ArgumentCaptor.forClass(AssigneeSpec.class);
        Mockito.verify(resolver).resolve(Mockito.eq("manager"), captor.capture());
        assertThat(captor.getValue().selfSelected()).containsExactly(41L, 42L, 42L);
    }

    @Test
    void directManagerMissingAlwaysPropagatesInsteadOfAutoPassing() throws Exception {
        AssigneeResolver resolver = Mockito.mock(AssigneeResolver.class);
        TaskMapper taskMapper = Mockito.mock(TaskMapper.class);
        TaskHistoryMapper historyMapper = Mockito.mock(TaskHistoryMapper.class);
        ApprovalHandler handler = new ApprovalHandler(resolver, taskMapper, historyMapper);
        Mockito.when(resolver.resolve(Mockito.eq("manager"), any(AssigneeSpec.class)))
            .thenThrow(new NoAssigneeFoundException("manager", "制单人缺少第 2 级直属上级"));
        ProcessInstance instance = new ProcessInstance();
        instance.setId(501L);
        var node = objectMapper.readTree("""
            {"id":"manager","type":"APPROVAL","props":{
              "assignedType":"DIRECT_MANAGER","manager":{"level":2},
              "nobody":{"handler":"TO_PASS"}}}
            """);
        NodeContext context = new NodeContext(7L, objectMapper.createObjectNode(),
            Map.of(), "previous", null, null);

        assertThatThrownBy(() -> handler.handle(
            objectMapper.createObjectNode(), node, instance, context))
            .isInstanceOf(NoAssigneeFoundException.class)
            .hasMessageContaining("第 2 级直属上级");

        Mockito.verifyNoInteractions(taskMapper, historyMapper);
    }

    @Test
    void explicitFallbackPolicy_rejects_an_unavailable_fallback_without_auto_passing() throws Exception {
        AssigneeResolver resolver = Mockito.mock(AssigneeResolver.class);
        TaskMapper taskMapper = Mockito.mock(TaskMapper.class);
        TaskHistoryMapper historyMapper = Mockito.mock(TaskHistoryMapper.class);
        WorkflowRuntimeV2 runtime = Mockito.mock(WorkflowRuntimeV2.class);
        ApprovalHandler handler = new ApprovalHandler(resolver, taskMapper, historyMapper);
        ReflectionTestUtils.setField(handler, "runtimeV2", runtime);
        ProcessInstance instance = new ProcessInstance();
        instance.setId(501L);
        instance.setEngineVersion(2);
        var node = objectMapper.readTree("""
            {"id":"a1","type":"APPROVAL","props":{"assignedType":"SELF",
             "fallbackAssignee":{"type":"ROLE","ids":[7]}}}
            """);
        Mockito.when(runtime.active(instance)).thenReturn(true);
        Mockito.when(runtime.fallbackUsers(Mockito.any(), Mockito.any())).thenReturn(List.of());
        Mockito.when(runtime.requiresNodeFallbackPolicy(Mockito.any())).thenReturn(true);
        Mockito.when(runtime.fallbackUnavailable(Mockito.any())).thenReturn(
            new BizException("FALLBACK_UNAVAILABLE", "兜底不可用"));
        Mockito.when(resolver.resolve(Mockito.eq("a1"), any(AssigneeSpec.class)))
            .thenThrow(new NoAssigneeFoundException("a1", "no assignee"));

        assertThatThrownBy(() -> handler.handle(objectMapper.readTree("""
                {"id":"root","type":"ROOT","props":{"fallbackPolicy":"NODE_REQUIRED"}}
                """), node, instance,
            new NodeContext(7L, objectMapper.createObjectNode(), Map.of(), "root", null, null)))
            .isInstanceOf(BizException.class)
            .satisfies(error -> assertThat(((BizException) error).getCode())
                .isEqualTo("FALLBACK_UNAVAILABLE"));
        Mockito.verifyNoInteractions(taskMapper, historyMapper);
    }

    @Test
    void runtimeAutoPassStillSkipsTaskCreationAndAdvances() throws Exception {
        AssigneeResolver resolver = Mockito.mock(AssigneeResolver.class);
        TaskMapper taskMapper = Mockito.mock(TaskMapper.class);
        TaskHistoryMapper historyMapper = Mockito.mock(TaskHistoryMapper.class);
        WorkflowRuntimeV2 runtime = Mockito.mock(WorkflowRuntimeV2.class);
        ApprovalHandler handler = new ApprovalHandler(resolver, taskMapper, historyMapper);
        ReflectionTestUtils.setField(handler, "runtimeV2", runtime);
        ProcessInstance instance = new ProcessInstance();
        instance.setId(501L);
        instance.setEngineVersion(2);
        var root = objectMapper.readTree("""
            {"id":"root","type":"ROOT","children":{"id":"self","type":"APPROVAL",
             "props":{"assignedType":"SELF"},"children":{"id":"manager","type":"APPROVAL"}}}
            """);
        var node = root.path("children");
        NodeContext context = new NodeContext(7L, objectMapper.createObjectNode(),
            Map.of(), "root", null, null, 99L);
        Mockito.when(resolver.resolve(Mockito.eq("self"), any(AssigneeSpec.class)))
            .thenReturn(List.of(7L));
        Mockito.when(runtime.shouldAutoPass(root, instance, List.of(7L))).thenReturn(true);

        NodeOutcome result = handler.handle(root, node, instance, context);

        assertThat(result.type()).isEqualTo(NodeOutcome.Type.NEXT);
        assertThat(result.node().path("id").asText()).isEqualTo("manager");
        Mockito.verify(runtime).completeNode(99L, "AUTO_PASSED");
        Mockito.verify(taskMapper, Mockito.never()).insert(any(com.antflow.task.TaskEntity.class));
        ArgumentCaptor<com.antflow.task.TaskHistoryEntity> history =
            ArgumentCaptor.forClass(com.antflow.task.TaskHistoryEntity.class);
        Mockito.verify(historyMapper).insert(history.capture());
        assertThat(history.getValue().getAction()).isEqualTo("AUTO_PASS");
    }
}

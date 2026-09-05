package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.engine.WorkflowRuntimeV2;
import com.antflow.engine.condition.ConditionEvaluator;
import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.engine.resolver.AssigneeSpec;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalPreviewServiceTest {
    private final FormDefinitionService forms = Mockito.mock(FormDefinitionService.class);
    private final ProcessDefinitionService processes = Mockito.mock(ProcessDefinitionService.class);
    private final FormDataMapper formDataMapper = Mockito.mock(FormDataMapper.class);
    private final ProcessInstanceMapper instanceMapper = Mockito.mock(ProcessInstanceMapper.class);
    private final TaskMapper taskMapper = Mockito.mock(TaskMapper.class);
    private final UserMapper userMapper = Mockito.mock(UserMapper.class);
    private final AssigneeResolver assignees = Mockito.mock(AssigneeResolver.class);
    private final WorkflowRuntimeV2 runtime = Mockito.mock(WorkflowRuntimeV2.class);
    private final AuthorizationService authorization = Mockito.mock(AuthorizationService.class);
    private final ObjectMapper json = new ObjectMapper();
    private ApprovalPreviewService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalPreviewService(forms, processes, formDataMapper, instanceMapper,
            taskMapper, userMapper, assignees, runtime, new ConditionEvaluator(), json,
            authorization);
        when(processes.normalizeConditionValues(anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(forms.canonicalizeStarterSubmission(anyString(), any(), any()))
            .thenAnswer(invocation -> json.convertValue(invocation.getArgument(1), Map.class));
        when(forms.canonicalizeStarterRevision(anyString(), any(), any(), any()))
            .thenAnswer(invocation -> json.convertValue(invocation.getArgument(1), Map.class));
        when(assignees.resolve(anyString(), any(AssigneeSpec.class))).thenAnswer(invocation -> {
            AssigneeSpec spec = invocation.getArgument(1);
            return switch (spec.type()) {
                case "SELF_SELECT" -> spec.selfSelected();
                case "SELF" -> List.of(spec.starterId());
                default -> spec.ids();
            };
        });
        when(runtime.fallbackUsers(any(JsonNode.class), any(JsonNode.class)))
            .thenReturn(List.of());
        when(runtime.previewAssignments(anyLong(), any(JsonNode.class), anyList()))
            .thenAnswer(invocation -> {
                JsonNode node = invocation.getArgument(1);
                List<Long> ids = invocation.getArgument(2);
                List<WorkflowRuntimeV2.Assignment> result = new ArrayList<>();
                for (int index = 0; index < ids.size(); index++) {
                    result.add(new WorkflowRuntimeV2.Assignment(
                        ids.get(index), ids.get(index), index + 1));
                }
                return "SEQUENTIAL".equals(WorkflowRuntimeV2.mode(node))
                    ? result.subList(0, 1) : result;
            });
        when(userMapper.selectBatchIds(anyCollection())).thenAnswer(invocation ->
            ((Collection<?>) invocation.getArgument(0)).stream()
                .map(value -> user(((Number) value).longValue()))
                .toList());
    }

    @Test
    void previewsMatchedConditionAndOnlyFirstSequentialRecipient() throws Exception {
        stubNewFlow("""
            {"id":"root","type":"ROOT","children":{"id":"route","type":"CONDITIONS",
             "branchs":[
              {"id":"high","type":"CONDITION","props":{"groups":[{"conditions":[
               {"field":"amount","operator":">=","value":100}]}]},"children":
               {"id":"finance","name":"财务审批","type":"APPROVAL","props":
                {"mode":"SEQUENTIAL","assignedType":"ASSIGN_USER","assignedUser":[8,9]}}},
              {"id":"default","type":"CONDITION","props":{"isDefault":true},"children":
               {"id":"manager","name":"主管审批","type":"APPROVAL","props":
                {"mode":"OR","assignedType":"ASSIGN_USER","assignedUser":[10]}}}
             ]}}""");

        ApprovalPreviewDto result = service.preview("leave",
            new ApprovalPreviewRequest(json.readTree("{\"amount\":200}"), Map.of(), null), 7L);

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().get(0).nodeId()).isEqualTo("finance");
        assertThat(result.nodes().get(0).nodeName()).isEqualTo("财务审批");
        assertThat(result.nodes().get(0).approvalMode()).isEqualTo("SEQUENTIAL");
        assertThat(result.nodes().get(0).assignees())
            .extracting(ApprovalPreviewAssigneeDto::displayName)
            .containsExactly("用户8");
        verifyNoInteractions(formDataMapper, instanceMapper, taskMapper);
    }

    @Test
    void previewsAllParallelFirstWaveNodesAndMarksDelayedBranch() throws Exception {
        stubNewFlow("""
            {"id":"root","type":"ROOT","children":{"id":"parallel","type":"PARALLEL",
             "branchs":[
              {"id":"b1","type":"BRANCH","props":{"conditionMode":"ALWAYS"},"children":
               {"id":"quality","name":"质量审批","type":"APPROVAL","props":
                {"mode":"OR","assignedType":"ASSIGN_USER","assignedUser":[8]}}},
              {"id":"b2","type":"BRANCH","props":{"conditionMode":"ALWAYS"},"children":
               {"id":"delay","type":"DELAY","children":
                {"id":"safety","name":"安全审批","type":"APPROVAL","props":
                 {"mode":"AND","assignedType":"ASSIGN_USER","assignedUser":[9]}}}}
             ]}}""");

        ApprovalPreviewDto result = service.preview("leave",
            new ApprovalPreviewRequest(json.createObjectNode(), Map.of(), null), 7L);

        assertThat(result.nodes()).extracting(ApprovalPreviewNodeDto::nodeId)
            .containsExactly("quality", "safety");
        assertThat(result.nodes()).extracting(ApprovalPreviewNodeDto::deferred)
            .containsExactly(false, true);
    }

    @Test
    void skipsStarterSelfApprovalAndContinuesToNextNode() throws Exception {
        stubNewFlow("""
            {"id":"root","type":"ROOT","props":{"settings":{"skipStarterAsApprover":true}},
             "children":{"id":"self","name":"本人确认","type":"APPROVAL","props":
              {"mode":"OR","assignedType":"SELF_SELECT","selfSelect":{"multiple":false}},
             "children":{"id":"manager","name":"主管审批","type":"APPROVAL","props":
              {"mode":"OR","assignedType":"ASSIGN_USER","assignedUser":[8]}}}}""");
        when(runtime.shouldAutoPassPreview(any(JsonNode.class), anyLong(), anyList()))
            .thenAnswer(invocation -> ((List<?>) invocation.getArgument(2)).equals(List.of(7L)));

        ApprovalPreviewDto result = service.preview("leave", new ApprovalPreviewRequest(
            json.createObjectNode(), Map.of("self", List.of(7L)), null), 7L);

        assertThat(result.nodes()).extracting(ApprovalPreviewNodeDto::nodeId)
            .containsExactly("manager");
    }

    @Test
    void reworkUsesSnapshotAndPreviousSelfSelection() throws Exception {
        TaskEntity rework = new TaskEntity();
        rework.setId(401L);
        rework.setProcInstId(501L);
        rework.setAssigneeId(7L);
        rework.setTaskType("REWORK");
        rework.setStatus("PENDING");
        ProcessInstance instance = new ProcessInstance();
        instance.setId(501L);
        instance.setFormDataId(301L);
        instance.setStartedBy(7L);
        instance.setEngineVersion(2);
        instance.setRoundNo(1);
        FormData formData = new FormData();
        formData.setId(301L);
        formData.setFormDefId(10L);
        formData.setData("{}");
        FormDefinition form = publishedForm();
        TaskEntity previous = new TaskEntity();
        previous.setNodeId("chosen");
        previous.setAssigneeId(8L);
        when(taskMapper.selectById(401L)).thenReturn(rework);
        when(instanceMapper.selectById(501L)).thenReturn(instance);
        when(formDataMapper.selectById(301L)).thenReturn(formData);
        when(forms.getById(10L)).thenReturn(form);
        when(runtime.active(instance)).thenReturn(true);
        when(runtime.formSchema(instance)).thenReturn("[]");
        when(runtime.processTree(instance)).thenReturn("""
            {"id":"root","type":"ROOT","children":{"id":"chosen","name":"原审批人",
             "type":"APPROVAL","props":{"mode":"OR","assignedType":"SELF_SELECT",
             "selfSelect":{"multiple":false}}}}""");
        when(taskMapper.selectList(any())).thenReturn(List.of(previous));

        ApprovalPreviewDto result = service.preview("leave", new ApprovalPreviewRequest(
            json.createObjectNode(), Map.of(), 401L), 7L);

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().get(0).assignees())
            .extracting(ApprovalPreviewAssigneeDto::userId)
            .containsExactly(8L);
    }

    private void stubNewFlow(String processJson) {
        FormDefinition form = publishedForm();
        ProcessDefinition process = new ProcessDefinition();
        process.setProcess(processJson);
        when(forms.getByCode("leave")).thenReturn(form);
        when(processes.latestPublishedForForm(10L)).thenReturn(process);
    }

    private static FormDefinition publishedForm() {
        FormDefinition form = new FormDefinition();
        form.setId(10L);
        form.setCode("leave");
        form.setStatus("PUBLISHED");
        form.setSchema("[]");
        return form;
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        user.setDisplayName("用户" + id);
        user.setStatus("ACTIVE");
        return user;
    }
}

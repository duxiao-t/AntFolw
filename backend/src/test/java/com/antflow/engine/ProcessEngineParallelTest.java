package com.antflow.engine;

import com.antflow.common.FormalNumberService;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
import com.antflow.engine.handler.ApprovalHandler;
import com.antflow.engine.handler.CcHandler;
import com.antflow.engine.handler.ConditionsHandler;
import com.antflow.engine.handler.EmptyHandler;
import com.antflow.engine.handler.ParallelHandler;
import com.antflow.engine.condition.ConditionEvaluator;
import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskHistoryEntity;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.antflow.task.TaskMapperExt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

/** Parallel gateway coverage for {@link ProcessEngine}. */
class ProcessEngineParallelTest {

    private FormDefinitionService formDefinitionService;
    private FormDataMapper formDataMapper;
    private ProcessDefinitionService processDefinitionService;
    private TaskMapper taskMapper;
    private ProcessInstanceMapper processInstanceMapper;
    private TaskHistoryMapper historyMapper;
    private AssigneeResolver assigneeResolver;
    private ObjectMapper json;
    private FormalNumberService formalNumberService;

    private final ConditionEvaluator evaluator = new ConditionEvaluator();
    private final AtomicLong fakeTaskId = new AtomicLong(0L);
    private final AtomicLong fakeFormDataId = new AtomicLong(0L);
    private final AtomicLong fakeInstanceId = new AtomicLong(0L);
    private final AtomicLong fakeHistoryId = new AtomicLong(0L);

    private static final String PARALLEL_FLOW = """
        {"id":"root","type":"ROOT","children":{
          "id":"p1","type":"PARALLEL","name":"并行",
          "branchs":[
            {"id":"b1","type":"BRANCH","name":"财务","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[42],"mode":"OR"},"children":null}},
            {"id":"b2","type":"BRANCH","name":"人事","children":{"id":"a2","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[43],"mode":"OR"},"children":null}}
          ],
          "children":{"id":"a3","type":"APPROVAL",
            "props":{"assignedType":"ASSIGN_USER","assignedUser":[44],"mode":"OR"},"children":null}
        }}
        """;

    private ProcessEngine engine() {
        var handlers = List.of(
            new EmptyHandler(),
            new ApprovalHandler(assigneeResolver, taskMapper, historyMapper),
            new CcHandler(taskMapper, historyMapper),
            new ConditionsHandler(evaluator),
            new ParallelHandler(handlersList(), evaluator)
        );
        return new ProcessEngine(
            formDefinitionService, formDataMapper, processDefinitionService,
            taskMapper, processInstanceMapper, new TaskMapperExt(processInstanceMapper),
            historyMapper, handlers, Mockito.mock(com.antflow.notify.NotificationPublisher.class),
            json, formalNumberService, Mockito.mock(com.antflow.automation.WorkflowJobMapper.class)
        );
    }

    // ParallelHandler needs the full handler list; build it standalone to avoid recursion in the lambda.
    private List<com.antflow.engine.handler.NodeHandler> handlersList() {
        return List.of(
            new EmptyHandler(),
            new ApprovalHandler(assigneeResolver, taskMapper, historyMapper),
            new CcHandler(taskMapper, historyMapper),
            new ConditionsHandler(evaluator),
            new ParallelHandler(List.of(), evaluator)
        );
    }

    @BeforeEach void setup() {
        formDefinitionService = Mockito.mock(FormDefinitionService.class);
        formDataMapper = Mockito.mock(FormDataMapper.class);
        processDefinitionService = Mockito.mock(ProcessDefinitionService.class);
        taskMapper = Mockito.mock(TaskMapper.class);
        processInstanceMapper = Mockito.mock(ProcessInstanceMapper.class);
        Mockito.when(processInstanceMapper.selectForUpdate(Mockito.anyLong()))
            .thenAnswer(invocation -> processInstanceMapper.selectById(invocation.getArgument(0)));
        historyMapper = Mockito.mock(TaskHistoryMapper.class);
        assigneeResolver = Mockito.mock(AssigneeResolver.class);
        json = new ObjectMapper();
        formalNumberService = Mockito.mock(FormalNumberService.class);
        Mockito.when(formalNumberService.businessNo()).thenReturn("000000000001");

        Mockito.doAnswer(inv -> {
            FormData fd = inv.getArgument(0);
            fd.setId(fakeFormDataId.incrementAndGet());
            return 1;
        }).when(formDataMapper).insert(any(FormData.class));
        Mockito.doAnswer(inv -> {
            ProcessInstance pi = inv.getArgument(0);
            pi.setId(fakeInstanceId.incrementAndGet());
            return 1;
        }).when(processInstanceMapper).insert(any(ProcessInstance.class));
        Mockito.doAnswer(inv -> {
            TaskEntity t = inv.getArgument(0);
            t.setId(fakeTaskId.incrementAndGet());
            return 1;
        }).when(taskMapper).insert(any(TaskEntity.class));
        Mockito.when(historyMapper.insert(any(TaskHistoryEntity.class))).thenAnswer(inv -> {
            TaskHistoryEntity h = inv.getArgument(0);
            if (h.getId() == null) h.setId(fakeHistoryId.incrementAndGet());
            return 1;
        });
        Mockito.when(assigneeResolver.resolve(Mockito.anyString(), any()))
            .thenReturn(List.of());
    }

    private void stubFormAndPd(String code, String processJson) {
        FormDefinition fd = new FormDefinition();
        fd.setId(1L); fd.setCode(code); fd.setName("Test Form"); fd.setVersion(1);
        fd.setSchema("[]"); fd.setSettings("{}"); fd.setStatus("PUBLISHED");
        Mockito.when(formDefinitionService.getByCode(code)).thenReturn(fd);
        ProcessDefinition pd = new ProcessDefinition();
        pd.setId(10L); pd.setFormDefId(1L); pd.setVersion(1);
        pd.setProcess(processJson); pd.setStatus("PUBLISHED");
        Mockito.when(processDefinitionService.latestPublishedForForm(1L)).thenReturn(pd);
    }

    private ProcessInstance runningInstance(String processJson) {
        ProcessInstance pi = new ProcessInstance();
        pi.setId(1L); pi.setProcDefId(10L); pi.setFormDataId(1L);
        pi.setProcessSnapshot(processJson); pi.setProcessDefVersion(1);
        pi.setStatus("RUNNING"); pi.setStartedBy(7L);
        return pi;
    }

    private TaskEntity taskWith(long id, String nodeId, long assignee, String status,
                                String parallelId, String branchId) {
        TaskEntity t = new TaskEntity();
        t.setId(id); t.setProcInstId(1L); t.setNodeId(nodeId);
        t.setAssigneeId(assignee); t.setStatus(status);
        t.setTaskType("APPROVAL"); t.setApprovalMode("OR");
        t.setParallelId(parallelId); t.setBranchId(branchId);
        t.setCreatedAt(OffsetDateTime.now());
        return t;
    }

    private List<TaskEntity> insertedTasks() {
        return Mockito.mockingDetails(taskMapper).getInvocations().stream()
            .filter(inv -> "insert".equals(inv.getMethod().getName()))
            .flatMap(inv -> Arrays.stream(inv.getArguments()))
            .filter(a -> a instanceof TaskEntity)
            .map(a -> (TaskEntity) a)
            .collect(Collectors.toList());
    }

    // ---------- 1. start creates tasks for every branch ----------
    @Test
    void start_parallel_createsTasksForAllBranchesWithGatewayMarks() {
        stubFormAndPd("F1", PARALLEL_FLOW);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));
        Mockito.when(assigneeResolver.resolve(eq("a2"), any())).thenReturn(List.of(43L));

        Map<String, Object> res = engine().start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        assertThat((Iterable<?>) res.get("firstTaskIds")).hasSize(2);
        List<TaskEntity> tasks = insertedTasks();
        TaskEntity t1 = tasks.stream().filter(t -> "a1".equals(t.getNodeId())).findFirst().orElseThrow();
        TaskEntity t2 = tasks.stream().filter(t -> "a2".equals(t.getNodeId())).findFirst().orElseThrow();
        assertThat(t1.getParallelId()).isEqualTo("p1");
        assertThat(t1.getBranchId()).isEqualTo("b1");
        assertThat(t2.getParallelId()).isEqualTo("p1");
        assertThat(t2.getBranchId()).isEqualTo("b2");
        // a3 (join target) must NOT be created yet.
        assertThat(tasks).noneMatch(t -> "a3".equals(t.getNodeId()));
    }

    @Test
    void start_parallel_skipsConditionalBranchWhenConditionDoesNotMatch() {
        String flow = conditionalParallelFlow();
        stubFormAndPd("F1", flow);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));
        Mockito.when(assigneeResolver.resolve(eq("a2"), any())).thenReturn(List.of(43L));

        Map<String, Object> result = engine().start(
            new StartCmd("F1", Map.of("amount", 50), null), 7L);

        assertThat((Iterable<?>) result.get("firstTaskIds")).hasSize(1);
        assertThat(insertedTasks()).extracting(TaskEntity::getNodeId)
            .containsExactly("a2");
    }

    @Test
    void start_parallel_runsConditionalBranchWhenConditionMatches() {
        String flow = conditionalParallelFlow();
        stubFormAndPd("F1", flow);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));
        Mockito.when(assigneeResolver.resolve(eq("a2"), any())).thenReturn(List.of(43L));

        Map<String, Object> result = engine().start(
            new StartCmd("F1", Map.of("amount", 150), null), 7L);

        assertThat((Iterable<?>) result.get("firstTaskIds")).hasSize(2);
        assertThat(insertedTasks()).extracting(TaskEntity::getNodeId)
            .containsExactly("a1", "a2");
    }

    // ---------- 2. first branch done -> wait for the other ----------
    @Test
    void approve_parallel_firstBranch_waitsForSecond() {
        stubFormAndPd("F1", PARALLEL_FLOW);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));
        Mockito.when(assigneeResolver.resolve(eq("a2"), any())).thenReturn(List.of(43L));
        ProcessEngine eng = engine();
        eng.start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        // Task id 1 = a1/42; task id 2 = a2/43 (insert order).
        Mockito.when(taskMapper.selectById(1L))
            .thenReturn(taskWith(1L, "a1", 42L, "PENDING", "p1", "b1"));
        Mockito.when(taskMapper.selectById(2L))
            .thenReturn(taskWith(2L, "a2", 43L, "PENDING", "p1", "b2"));
        Mockito.when(processInstanceMapper.selectById(1L))
            .thenReturn(runningInstance(PARALLEL_FLOW));
        Mockito.when(formDataMapper.selectById(1L)).thenAnswer(inv -> {
            FormData fd = new FormData();
            fd.setId(1L); fd.setFormDefId(1L); fd.setData("{\"k\":\"v\"}");
            fd.setStatus("SUBMITTED");
            return fd;
        });
        // OR-sibling query (node_id=a1) -> no other pending on a1
        Mockito.when(taskMapper.selectList(argThat(qw -> qw != null && ((QueryWrapper<TaskEntity>) qw).getParamNameValuePairs().containsKey("node_id"))))
            .thenReturn(List.of());
        // Parallel join query (parallel_id=p1) -> branch b2 still pending
        Mockito.when(taskMapper.selectList(argThat(qw -> qw != null && ((QueryWrapper<TaskEntity>) qw).getParamNameValuePairs().containsKey("parallel_id"))))
            .thenReturn(List.of(taskWith(2L, "a2", 43L, "PENDING", "p1", "b2")));

        Mockito.when(taskMapper.selectCount(any())).thenReturn(1L);

        eng.approve(new CompleteCmd(1L, "approve", "ok", null), 42L);

        // No new tasks (a3 must not be created), instance still RUNNING.
        assertThat(insertedTasks()).noneMatch(t -> "a3".equals(t.getNodeId()));
        ProcessInstance pi = lastInstance();
        assertThat(pi.getStatus()).isEqualTo("RUNNING");
    }

    // ---------- 3. all branches done -> join to next node ----------
    @Test
    void approve_parallel_allBranchesDone_joinsToNextNode() {
        stubFormAndPd("F1", PARALLEL_FLOW);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));
        Mockito.when(assigneeResolver.resolve(eq("a2"), any())).thenReturn(List.of(43L));
        Mockito.when(assigneeResolver.resolve(eq("a3"), any())).thenReturn(List.of(44L));
        ProcessEngine eng = engine();
        eng.start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        Mockito.when(taskMapper.selectById(1L))
            .thenReturn(taskWith(1L, "a1", 42L, "PENDING", "p1", "b1"));
        Mockito.when(taskMapper.selectById(2L))
            .thenReturn(taskWith(2L, "a2", 43L, "PENDING", "p1", "b2"));
        Mockito.when(processInstanceMapper.selectById(1L))
            .thenReturn(runningInstance(PARALLEL_FLOW));
        Mockito.when(formDataMapper.selectById(1L)).thenAnswer(inv -> {
            FormData fd = new FormData();
            fd.setId(1L); fd.setFormDefId(1L); fd.setData("{\"k\":\"v\"}");
            fd.setStatus("SUBMITTED");
            return fd;
        });
        Mockito.when(taskMapper.selectList(argThat(qw -> qw != null && ((QueryWrapper<TaskEntity>) qw).getParamNameValuePairs().containsKey("node_id"))))
            .thenReturn(List.of());
        // Branch b2 pending while approving a1, then empty for a2.
        Mockito.when(taskMapper.selectList(argThat(qw -> qw != null && ((QueryWrapper<TaskEntity>) qw).getParamNameValuePairs().containsKey("parallel_id"))))
            .thenReturn(List.of(taskWith(2L, "a2", 43L, "PENDING", "p1", "b2")));

        Mockito.when(taskMapper.selectCount(any())).thenReturn(1L);
        eng.approve(new CompleteCmd(1L, "approve", "ok", null), 42L);
        assertThat(insertedTasks()).noneMatch(t -> "a3".equals(t.getNodeId()));

        // Now both branches done: a3 join task is created.
        Mockito.when(taskMapper.selectCount(any())).thenReturn(0L);
        Mockito.when(taskMapper.selectList(argThat(qw -> qw != null && ((QueryWrapper<TaskEntity>) qw).getParamNameValuePairs().containsKey("parallel_id"))))
            .thenReturn(List.of());
        eng.approve(new CompleteCmd(2L, "approve", "ok", null), 43L);

        List<TaskEntity> tasks = insertedTasks();
        TaskEntity join = tasks.stream().filter(t -> "a3".equals(t.getNodeId()))
            .findFirst().orElseThrow();
        assertThat(join.getParallelId()).isNull();
        assertThat(join.getBranchId()).isNull();
        ProcessInstance pi = lastInstance();
        assertThat(pi.getStatus()).isEqualTo("RUNNING");
        assertThat(pi.getCurrentNodeId()).isEqualTo("a3");
    }

    // ---------- 4. reject inside a branch -> whole gateway rejected back ----------
    @Test
    void reject_parallel_skipsOtherBranchesAndReturnsToPrevious() {
        String flow = """
            {"id":"root","type":"ROOT","children":{
              "id":"a0","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[40],"mode":"OR"},
              "children":{"id":"p1","type":"PARALLEL","branchs":[
                {"id":"b1","type":"BRANCH","children":{"id":"a1","type":"APPROVAL",
                  "props":{"assignedType":"ASSIGN_USER","assignedUser":[42],"mode":"OR"},"children":null}},
                {"id":"b2","type":"BRANCH","children":{"id":"a2","type":"APPROVAL",
                  "props":{"assignedType":"ASSIGN_USER","assignedUser":[43],"mode":"OR"},"children":null}}
              ],"children":{"id":"a3","type":"APPROVAL",
                "props":{"assignedType":"ASSIGN_USER","assignedUser":[44],"mode":"OR"},"children":null}}
            }}
            """;
        stubFormAndPd("F1", flow);
        Mockito.when(assigneeResolver.resolve(eq("a0"), any())).thenReturn(List.of(40L));
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));
        Mockito.when(assigneeResolver.resolve(eq("a2"), any())).thenReturn(List.of(43L));
        ProcessEngine eng = engine();
        eng.start(new StartCmd("F1", Map.of("k", "v"), null), 7L);
        // Task 1 = a0/40; approve it to enter the parallel gateway.
        Mockito.when(taskMapper.selectById(1L))
            .thenReturn(taskWith(1L, "a0", 40L, "PENDING", null, null));
        Mockito.when(processInstanceMapper.selectById(1L))
            .thenReturn(runningInstance(flow));
        Mockito.when(formDataMapper.selectById(1L)).thenAnswer(inv -> {
            FormData fd = new FormData();
            fd.setId(1L); fd.setFormDefId(1L); fd.setData("{\"k\":\"v\"}");
            fd.setStatus("SUBMITTED");
            return fd;
        });
        Mockito.when(taskMapper.selectList(any()))
            .thenReturn(List.of());
        Mockito.when(taskMapper.selectList(any()))
            .thenReturn(List.of());
        eng.approve(new CompleteCmd(1L, "approve", "ok", null), 40L);
        // Tasks 2 (a1/42) and 3 (a2/43) now exist.
        assertThat(insertedTasks()).anyMatch(t -> "a1".equals(t.getNodeId()));
        assertThat(insertedTasks()).anyMatch(t -> "a2".equals(t.getNodeId()));

        // Reject a1 -> sibling b2 pending skipped, return to a0.
        Mockito.when(taskMapper.selectById(2L))
            .thenReturn(taskWith(2L, "a1", 42L, "PENDING", "p1", "b1"));
        Mockito.when(taskMapper.selectById(3L))
            .thenReturn(taskWith(3L, "a2", 43L, "PENDING", "p1", "b2"));
        Mockito.when(taskMapper.selectList(any()))
            .thenReturn(List.of(taskWith(3L, "a2", 43L, "PENDING", "p1", "b2")));
        // previousApproval -> the approved a0 task (parallel_id is null)
        Mockito.when(taskMapper.selectOne(any())).thenReturn(taskWith(1L, "a0", 40L, "APPROVED", null, null));

        eng.reject(new CompleteCmd(2L, "reject", "no", null), 42L);

        // b2 task (id 3) must be SKIPPED.
        ArgumentCaptor<TaskEntity> updCap = ArgumentCaptor.forClass(TaskEntity.class);
        Mockito.verify(taskMapper, Mockito.atLeastOnce()).updateById(updCap.capture());
        assertThat(updCap.getAllValues()).anyMatch(t ->
            t.getId() == 3L && "SKIPPED".equals(t.getStatus()));
        // A new PENDING APPROVAL task for a0 is created.
        List<TaskEntity> tasks = insertedTasks();
        TaskEntity back = tasks.get(tasks.size() - 1);
        assertThat(back.getNodeId()).isEqualTo("a0");
        assertThat(back.getStatus()).isEqualTo("PENDING");
        assertThat(back.getAssigneeId()).isEqualTo(40L);
        assertThat(back.getParallelId()).isNull();
    }

    @Test
    void forceReject_disallowsTargetInsideParallelBranch() {
        ProcessInstance instance = runningInstance(PARALLEL_FLOW);
        instance.setFormDataId(1L);
        TaskEntity joinTask = taskWith(9L, "a3", 44L, "PENDING", null, null);
        Mockito.when(taskMapper.selectById(9L)).thenReturn(joinTask);
        Mockito.when(processInstanceMapper.selectById(1L)).thenReturn(instance);
        Mockito.when(taskMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> engine().forceReject(
            new CompleteCmd(9L, "REJECT", "incident", "a1"), 7L))
            .isInstanceOf(BizException.class)
            .matches(error -> "BAD_REJECT_TARGET".equals(((BizException) error).getCode()));
    }

    private ProcessInstance lastInstance() {
        ArgumentCaptor<ProcessInstance> cap = ArgumentCaptor.forClass(ProcessInstance.class);
        Mockito.verify(processInstanceMapper, Mockito.atLeastOnce()).updateById(cap.capture());
        List<ProcessInstance> all = cap.getAllValues();
        return all.get(all.size() - 1);
    }

    private String conditionalParallelFlow() {
        return PARALLEL_FLOW.replace(
            "{\"id\":\"b1\",\"type\":\"BRANCH\",\"name\":\"财务\",",
            "{\"id\":\"b1\",\"type\":\"BRANCH\",\"name\":\"财务\"," +
                "\"props\":{\"conditionMode\":\"WHEN_MATCHED\",\"groupsType\":\"OR\"," +
                "\"groups\":[{\"groupType\":\"AND\",\"conditions\":[{" +
                "\"field\":\"amount\",\"operator\":\">\",\"value\":\"100\"}]}]},"
        );
    }
}






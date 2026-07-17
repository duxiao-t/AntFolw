package com.antflow.engine;

import com.antflow.engine.condition.ConditionEvaluator;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Recursive-tree traversal implementation of {@link ProcessEngine}.
 *
 * <p>Mocks all mappers + services + {@link AssigneeResolver}; uses the real
 * {@link ConditionEvaluator} and a real {@link ObjectMapper}. Coverage:
 * <ol>
 *   <li>single approval</li>
 *   <li>OR-sign: sibling SKIPPED after first approve, instance advances/closes</li>
 *   <li>AND-sign: instance waits for all PENDING siblings before advancing</li>
 *   <li>CONDITIONS routing: formData drives branch selection (including default)</li>
 *   <li>CC non-blocking: parallel CC task + next PENDING</li>
 *   <li>NoAssignee + nobody=TO_PASS: auto-pass continue walking</li>
 * </ol>
 */
class ProcessEngineTreeTest {

    private FormDefinitionService formDefinitionService;
    private FormDataMapper formDataMapper;
    private ProcessDefinitionService processDefinitionService;
    private TaskMapper taskMapper;
    private ProcessInstanceMapper processInstanceMapper;
    private TaskHistoryMapper historyMapper;
    private AssigneeResolver assigneeResolver;
    private ObjectMapper json;

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    private final AtomicLong fakeTaskId = new AtomicLong(0L);
    private final AtomicLong fakeFormDataId = new AtomicLong(0L);
    private final AtomicLong fakeInstanceId = new AtomicLong(0L);
    private final AtomicLong fakeHistoryId = new AtomicLong(0L);

    private ProcessEngine engine() {
        // Sprint 2 C2：构造一组 handler（real impls + mocked mappers/resolver）
        var handlers = List.of(
            new com.antflow.engine.handler.EmptyHandler(),
            new com.antflow.engine.handler.ApprovalHandler(assigneeResolver, taskMapper, historyMapper),
            new com.antflow.engine.handler.CcHandler(taskMapper, historyMapper),
            new com.antflow.engine.handler.ConditionsHandler(evaluator)
        );
        return new ProcessEngine(
            formDefinitionService, formDataMapper, processDefinitionService,
            taskMapper, processInstanceMapper, new TaskMapperExt(processInstanceMapper),
            historyMapper, handlers, Mockito.mock(com.antflow.notify.NotificationPublisher.class), json
        );
    }

    @BeforeEach void setup() {
        formDefinitionService = Mockito.mock(FormDefinitionService.class);
        formDataMapper = Mockito.mock(FormDataMapper.class);
        processDefinitionService = Mockito.mock(ProcessDefinitionService.class);
        taskMapper = Mockito.mock(TaskMapper.class);
        processInstanceMapper = Mockito.mock(ProcessInstanceMapper.class);
        historyMapper = Mockito.mock(TaskHistoryMapper.class);
        assigneeResolver = Mockito.mock(AssigneeResolver.class);
        json = new ObjectMapper();

        // Auto-increment ids for inserts.
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

        // Default assignee resolver — individual tests can override per nodeId.
        Mockito.when(assigneeResolver.resolve(Mockito.anyString(), any()))
            .thenReturn(List.of());
    }

    // ---------- helpers ----------
    private FormDefinition publishedForm(String code) {
        FormDefinition fd = new FormDefinition();
        fd.setId(1L);
        fd.setCode(code);
        fd.setName("Test Form");
        fd.setVersion(1);
        fd.setSchema("[]");
        fd.setSettings("{}");
        fd.setStatus("PUBLISHED");
        return fd;
    }

    private ProcessDefinition pd(String processJson) {
        ProcessDefinition pd = new ProcessDefinition();
        pd.setId(10L);
        pd.setFormDefId(1L);
        pd.setVersion(1);
        pd.setProcess(processJson);
        pd.setStatus("PUBLISHED");
        return pd;
    }

    private void stubFormAndPd(String code, String processJson) {
        Mockito.when(formDefinitionService.getByCode(code)).thenReturn(publishedForm(code));
        Mockito.when(processDefinitionService.latestPublishedForForm(1L))
            .thenReturn(pd(processJson));
        Mockito.when(processDefinitionService.getById(10L))
            .thenReturn(pd(processJson));
    }

    private List<TaskEntity> capturesOfTaskInsert() {
        return Mockito.mockingDetails(taskMapper).getInvocations().stream()
            .filter(inv -> "insert".equals(inv.getMethod().getName()))
            .flatMap(inv -> Arrays.stream(inv.getArguments()))
            .filter(a -> a instanceof TaskEntity)
            .map(a -> (TaskEntity) a)
            .collect(Collectors.toList());
    }

    private ProcessInstance lastInstance() {
        ArgumentCaptor<ProcessInstance> cap = ArgumentCaptor.forClass(ProcessInstance.class);
        Mockito.verify(processInstanceMapper, Mockito.atLeastOnce()).updateById(cap.capture());
        List<ProcessInstance> all = cap.getAllValues();
        return all.get(all.size() - 1);
    }

    // ---------- 1. single approval ----------
    @Test
    void start_singleApproval_createsOnePendingTaskForAssignee() {
        String processJson = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[42]},
              "children":null}}
            """;
        stubFormAndPd("F1", processJson);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));

        Map<String, Object> res = engine().start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        assertThat(res.get("instanceId")).isEqualTo(1L);
        assertThat(res.get("formDataId")).isEqualTo(1L);
        assertThat((Iterable<?>) res.get("firstTaskIds")).hasSize(1);

        ArgumentCaptor<TaskEntity> cap = ArgumentCaptor.forClass(TaskEntity.class);
        Mockito.verify(taskMapper).insert(cap.capture());
        TaskEntity t = cap.getValue();
        assertThat(t.getNodeId()).isEqualTo("a1");
        assertThat(t.getAssigneeId()).isEqualTo(42L);
        assertThat(t.getStatus()).isEqualTo("PENDING");
        assertThat(t.getApprovalMode()).isEqualTo("OR");

        // Instance is RUNNING (only started, not yet finished).
        assertThat(lastInstance().getStatus()).isEqualTo("RUNNING");
        assertThat(lastInstance().getCurrentNodeId()).isEqualTo("a1");
    }

    // ---------- 2. OR-sign ----------
    @Test
    void approve_orSign_skipsSiblingAndCompletesInstance() {
        String processJson = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[42,43]},
              "children":null}}
            """;
        stubFormAndPd("F1", processJson);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L, 43L));

        ProcessEngine eng = engine();
        eng.start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        // Two PENDING tasks on a1 (ids 1, 2). First assigned 42, second 43.
        // Make selectById return 42's task; selectList for siblings return 43's.
        Mockito.when(taskMapper.selectById(1L)).thenAnswer(inv -> taskWithId(1L, "a1", 42L, "PENDING"));
        Mockito.when(taskMapper.selectList(any())).thenAnswer(inv -> {
            // OR-sign approve only calls selectList for the sibling-skip query.
            return List.of(taskWithId(2L, "a1", 43L, "PENDING"));
        });
        Mockito.when(processInstanceMapper.selectById(1L)).thenAnswer(inv -> {
            ProcessInstance pi = new ProcessInstance();
            pi.setId(1L); pi.setProcDefId(10L); pi.setFormDataId(1L);
            pi.setProcessSnapshot(processJson); pi.setProcessDefVersion(1);
            pi.setStatus("RUNNING"); pi.setStartedBy(7L);
            pi.setCurrentNodeId("a1");
            return pi;
        });
        Mockito.when(formDataMapper.selectById(1L)).thenAnswer(inv -> {
            FormData fd = new FormData();
            fd.setId(1L); fd.setFormDefId(1L); fd.setData("{\"k\":\"v\"}");
            fd.setStatus("SUBMITTED");
            return fd;
        });

        eng.approve(new CompleteCmd(1L, "approve", "ok", null), 42L);

        // Sibling (id=2) must be SKIPPED.
        ArgumentCaptor<TaskEntity> updCap = ArgumentCaptor.forClass(TaskEntity.class);
        Mockito.verify(taskMapper, Mockito.atLeastOnce()).updateById(updCap.capture());
        List<TaskEntity> updates = updCap.getAllValues();
        TaskEntity skipped = updates.stream()
            .filter(t -> "SKIPPED".equals(t.getStatus()) && t.getId() == 2L)
            .findFirst().orElseThrow();
        assertThat(skipped.getNodeId()).isEqualTo("a1");

        // Instance is APPROVED (only node, then end).
        assertThat(lastInstance().getStatus()).isEqualTo("APPROVED");
        assertThat(lastInstance().getFinishedAt()).isNotNull();
    }

    private TaskEntity taskWithId(long id, String nodeId, long assignee, String status) {
        TaskEntity t = new TaskEntity();
        t.setId(id); t.setProcInstId(1L); t.setNodeId(nodeId);
        t.setAssigneeId(assignee); t.setStatus(status);
        return t;
    }

    // ---------- 3. AND-sign ----------
    @Test
    void approve_andSign_waitsForAll() {
        String processJson = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[42,43],"mode":"AND"},
              "children":null}}
            """;
        stubFormAndPd("F1", processJson);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L, 43L));

        ProcessEngine eng = engine();
        eng.start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        // 42 approves first — sibling 43 still PENDING.
        Mockito.when(taskMapper.selectById(1L)).thenAnswer(inv -> taskWithId(1L, "a1", 42L, "PENDING"));
        java.util.concurrent.atomic.AtomicInteger andCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        Mockito.when(taskMapper.selectList(any())).thenAnswer(inv -> {
            int n = andCalls.incrementAndGet();
            // First AND-check (42 approves) → sibling still pending.
            // Second AND-check (43 approves) → no more pending siblings.
            if (n == 1) {
                return List.of(taskWithId(2L, "a1", 43L, "PENDING"));
            }
            return List.of();
        });
        Mockito.when(processInstanceMapper.selectById(1L)).thenAnswer(inv -> {
            ProcessInstance pi = new ProcessInstance();
            pi.setId(1L); pi.setProcDefId(10L); pi.setStatus("RUNNING");
            pi.setProcessSnapshot(processJson); pi.setProcessDefVersion(1);
            pi.setStartedBy(7L); pi.setCurrentNodeId("a1");
            return pi;
        });
        Mockito.when(formDataMapper.selectById(1L)).thenAnswer(inv -> {
            FormData fd = new FormData();
            fd.setId(1L); fd.setData("{\"k\":\"v\"}");
            return fd;
        });

        eng.approve(new CompleteCmd(1L, "approve", "ok", null), 42L);

        // Instance must STILL be RUNNING, no COMPLETE history yet.
        ProcessInstance pi = lastInstance();
        assertThat(pi.getStatus()).isEqualTo("RUNNING");
        boolean hasComplete = Mockito.mockingDetails(historyMapper).getInvocations().stream()
            .flatMap(inv -> Arrays.stream(inv.getArguments()))
            .anyMatch(a -> a instanceof TaskHistoryEntity
                && "COMPLETE".equals(((TaskHistoryEntity) a).getAction()));
        assertThat(hasComplete).isFalse();

        // 43 approves second — instance ends.
        Mockito.when(taskMapper.selectById(2L)).thenAnswer(inv -> taskWithId(2L, "a1", 43L, "PENDING"));
        eng.approve(new CompleteCmd(2L, "approve", "ok", null), 43L);

        ProcessInstance pi2 = lastInstance();
        assertThat(pi2.getStatus()).isEqualTo("APPROVED");
    }

    // ---------- 4. CONDITIONS routing ----------
    @Test
    void start_conditionsRouting_picksBranchByFormData() {
        // Two branches: amount>=5000 → APPROVAL X; default → APPROVAL Y.
        String processJson = """
            {"id":"root","type":"ROOT","children":{
              "id":"c1","type":"CONDITIONS","branchs":[
                {"id":"b1","type":"CONDITION",
                  "props":{"groupsType":"OR","groups":[
                    {"groupType":"AND","conditions":[
                      {"field":"amount","operator":">=","value":5000}
                    ]}
                  ]},
                  "children":{"id":"x","type":"APPROVAL",
                    "props":{"assignedType":"ASSIGN_USER","assignedUser":[100]}}},
                {"id":"b2","type":"CONDITION",
                  "props":{"isDefault":true},
                  "children":{"id":"y","type":"APPROVAL",
                    "props":{"assignedType":"ASSIGN_USER","assignedUser":[200]}}}
              ]}}
            """;
        stubFormAndPd("F1", processJson);
        // Stub resolver based on nodeId.
        Mockito.when(assigneeResolver.resolve(eq("x"), any())).thenReturn(List.of(100L));
        Mockito.when(assigneeResolver.resolve(eq("y"), any())).thenReturn(List.of(200L));

        ProcessEngine eng = engine();
        Map<String, Object> high = eng.start(
            new StartCmd("F1", Map.of("amount", 6000), null), 7L);
        Map<String, Object> low = eng.start(
            new StartCmd("F1", Map.of("amount", 100), null), 8L);

        TaskEntity tx = firstTaskFromInsert();
        assertThat(tx.getNodeId()).isEqualTo("x");
        assertThat(tx.getAssigneeId()).isEqualTo(100L);

        // The second start should have created a task for y.
        long secondStartFormDataId = fakeFormDataId.get();
        // Look at history of inserts grouped by proc_inst_id.
        List<TaskEntity> tasks = capturesOfTaskInsert();
        assertThat(tasks).anyMatch(t -> "y".equals(t.getNodeId()) && t.getAssigneeId() == 200L);
    }

    private TaskEntity firstTaskFromInsert() {
        ArgumentCaptor<TaskEntity> cap = ArgumentCaptor.forClass(TaskEntity.class);
        Mockito.verify(taskMapper, Mockito.atLeastOnce()).insert(cap.capture());
        return cap.getAllValues().get(0);
    }

    // ---------- 5. CC non-blocking ----------
    @Test
    void start_cc_createsCcTaskAlongsideNextApproval() {
        String processJson = """
            {"id":"root","type":"ROOT","children":{
              "id":"cc1","type":"CC","props":{"assignedUser":[77]},
              "children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[42]}}}}
            """;
        stubFormAndPd("F1", processJson);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));

        engine().start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        ArgumentCaptor<TaskEntity> cap = ArgumentCaptor.forClass(TaskEntity.class);
        Mockito.verify(taskMapper, Mockito.atLeast(2)).insert(cap.capture());
        List<TaskEntity> tasks = cap.getAllValues();
        assertThat(tasks).anyMatch(t -> "cc1".equals(t.getNodeId()) && t.getAssigneeId() == 77L && "CC".equals(t.getStatus()));
        assertThat(tasks).anyMatch(t -> "a1".equals(t.getNodeId()) && t.getAssigneeId() == 42L && "PENDING".equals(t.getStatus()));

        assertThat(lastInstance().getStatus()).isEqualTo("RUNNING");
        assertThat(lastInstance().getCurrentNodeId()).isEqualTo("a1");
    }

    // ---------- 6. No assignee + TO_PASS ----------
    @Test
    void start_noAssigneeToPass_autoPassesAndContinues() {
        // a1 has nobody but TO_PASS, then a2 is a real approval that closes.
        String processJson = """
            {"id":"root","type":"ROOT","children":{
              "id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[],
                "nobody":{"handler":"TO_PASS"}},
              "children":{"id":"a2","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[42]}}}}
            """;
        stubFormAndPd("F1", processJson);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any()))
            .thenThrow(new NoAssigneeFoundException("a1", "no users"));
        Mockito.when(assigneeResolver.resolve(eq("a2"), any())).thenReturn(List.of(42L));

        engine().start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        // Only a2 should have a PENDING task created.
        List<TaskEntity> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            try {
                ArgumentCaptor<TaskEntity> cap = ArgumentCaptor.forClass(TaskEntity.class);
                Mockito.verify(taskMapper, Mockito.atLeast(0)).insert(cap.capture());
                tasks = cap.getAllValues();
                break;
            } catch (Throwable ignored) {}
        }
        assertThat(tasks).anyMatch(t -> "a2".equals(t.getNodeId()) && t.getAssigneeId() == 42L);
        assertThat(tasks).noneMatch(t -> "a1".equals(t.getNodeId()));

        // Instance is RUNNING, currentNodeId = a2.
        ProcessInstance pi = lastInstance();
        assertThat(pi.getStatus()).isEqualTo("RUNNING");
        assertThat(pi.getCurrentNodeId()).isEqualTo("a2");
    }

    // ---------- 7. reject writes instance-level REJECT history ----------
    @Test
    void reject_writesInstanceLevelRejectHistory() {
        String processJson = """
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
              "props":{"assignedType":"ASSIGN_USER","assignedUser":[42]},
              "children":null}}
            """;
        stubFormAndPd("F1", processJson);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));

        ProcessEngine eng = engine();
        eng.start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        // Task id=1 belongs to 42, PENDING on a1.
        Mockito.when(taskMapper.selectById(1L)).thenAnswer(inv -> taskWithId(1L, "a1", 42L, "PENDING"));
        Mockito.when(taskMapper.selectList(any())).thenAnswer(inv -> List.of());
        Mockito.when(processInstanceMapper.selectById(1L)).thenAnswer(inv -> {
            ProcessInstance pi = new ProcessInstance();
            pi.setId(1L); pi.setProcDefId(10L); pi.setStatus("RUNNING");
            pi.setProcessSnapshot(processJson); pi.setProcessDefVersion(1);
            pi.setStartedBy(7L); pi.setCurrentNodeId("a1");
            return pi;
        });

        eng.reject(new CompleteCmd(1L, "reject", "not ok", null), 42L);

        // Instance must be REJECTED.
        assertThat(lastInstance().getStatus()).isEqualTo("REJECTED");

        // Verify an instance-level REJECT history row was inserted (taskId == null).
        ArgumentCaptor<TaskHistoryEntity> cap = ArgumentCaptor.forClass(TaskHistoryEntity.class);
        Mockito.verify(historyMapper, Mockito.atLeastOnce()).insert(cap.capture());
        List<TaskHistoryEntity> all = cap.getAllValues();
        TaskHistoryEntity instReject = all.stream()
            .filter(h -> "REJECT".equals(h.getAction()) && h.getTaskId() == null)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no instance-level REJECT history (taskId==null) found in: " + all));
        assertThat(instReject.getProcInstId()).isEqualTo(1L);
        assertThat(instReject.getFromNodeId()).isEqualTo("a1");
        assertThat(instReject.getToNodeId()).isNull();
        assertThat(instReject.getOperatorId()).isEqualTo(42L);
        assertThat(instReject.getComment()).isEqualTo("not ok");
    }

    // ---------- 8. reject to specified node (props.refuse = TO_NODE) ----------
    @Test
    void reject_toConfiguredNode_resumesAtTarget() {
        // ROOT -> a1 (ASSIGN_USER [42], refuse={mode:TO_NODE,targetNodeId:a1})
        //   -> a2 (ASSIGN_USER [99]) -> null
        String processJson = """
            {"id":"root","type":"ROOT","children":
              {"id":"a1","type":"APPROVAL",
               "props":{"assignedType":"ASSIGN_USER","assignedUser":[42],
                        "refuse":{"mode":"TO_NODE","targetNodeId":"a1"}},
               "children":
              {"id":"a2","type":"APPROVAL",
               "props":{"assignedType":"ASSIGN_USER","assignedUser":[99]},
               "children":null}}}
            """;
        stubFormAndPd("F1", processJson);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));
        Mockito.when(assigneeResolver.resolve(eq("a2"), any())).thenReturn(List.of(99L));

        ProcessEngine eng = engine();
        eng.start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        // Task id=1, 42's PENDING on a1.
        Mockito.when(taskMapper.selectById(1L)).thenAnswer(inv -> taskWithId(1L, "a1", 42L, "PENDING"));
        Mockito.when(taskMapper.selectList(any())).thenAnswer(inv -> List.of());
        Mockito.when(processInstanceMapper.selectById(1L)).thenAnswer(inv -> {
            ProcessInstance pi = new ProcessInstance();
            pi.setId(1L); pi.setProcDefId(10L); pi.setProcessSnapshot(processJson);
            pi.setProcessDefVersion(1);
            pi.setStatus("RUNNING"); pi.setStartedBy(7L); pi.setCurrentNodeId("a1");
            return pi;
        });
        Mockito.when(formDataMapper.selectById(1L)).thenAnswer(inv -> {
            FormData fd = new FormData();
            fd.setId(1L); fd.setData("{\"k\":\"v\"}");
            return fd;
        });

        eng.reject(new CompleteCmd(1L, "reject", "请重审", null), 42L);

        // Instance must STILL be RUNNING (驳回到节点未结束).
        assertThat(lastInstance().getStatus()).isEqualTo("RUNNING");

        // 应该有 1 条 PENDING 任务：a1 上的新任务（id=2）
        List<TaskEntity> tasks = capturesOfTaskInsert();
        TaskEntity reborn = tasks.stream()
            .filter(t -> "a1".equals(t.getNodeId()) && "PENDING".equals(t.getStatus()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no reborn PENDING on a1 in: " + tasks));
        assertThat(reborn.getAssigneeId()).isEqualTo(42L);

        // 历史记录里应有 REJECT_TO_NODE
        ArgumentCaptor<TaskHistoryEntity> cap = ArgumentCaptor.forClass(TaskHistoryEntity.class);
        Mockito.verify(historyMapper, Mockito.atLeastOnce()).insert(cap.capture());
        List<TaskHistoryEntity> all = cap.getAllValues();
        TaskHistoryEntity rtn = all.stream()
            .filter(h -> "REJECT_TO_NODE".equals(h.getAction()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no REJECT_TO_NODE history in: " + all));
        assertThat(rtn.getFromNodeId()).isEqualTo("a1");
        assertThat(rtn.getToNodeId()).isEqualTo("a1");
        assertThat(rtn.getOperatorId()).isEqualTo(42L);
        assertThat(rtn.getComment()).isEqualTo("请重审");
    }

    // ---------- 9. reject with runtime rejectToNodeId override ----------
    @Test
    void reject_runtimeOverride_routesToChosenNode() {
        // Process tree 没有 props.refuse，但运行时传 rejectToNodeId="a1"，应驳回到 a1。
        // a1 之后还有 a2，所以驳回到 a1 重审后会新建 a1 的任务。
        String processJson = """
            {"id":"root","type":"ROOT","children":
              {"id":"a1","type":"APPROVAL",
               "props":{"assignedType":"ASSIGN_USER","assignedUser":[42]},
               "children":
              {"id":"a2","type":"APPROVAL",
               "props":{"assignedType":"ASSIGN_USER","assignedUser":[99]},
               "children":null}}}
            """;
        stubFormAndPd("F1", processJson);
        Mockito.when(assigneeResolver.resolve(eq("a1"), any())).thenReturn(List.of(42L));
        Mockito.when(assigneeResolver.resolve(eq("a2"), any())).thenReturn(List.of(99L));

        ProcessEngine eng = engine();
        eng.start(new StartCmd("F1", Map.of("k", "v"), null), 7L);

        Mockito.when(taskMapper.selectById(1L)).thenAnswer(inv -> taskWithId(1L, "a1", 42L, "PENDING"));
        Mockito.when(taskMapper.selectList(any())).thenAnswer(inv -> List.of());
        Mockito.when(processInstanceMapper.selectById(1L)).thenAnswer(inv -> {
            ProcessInstance pi = new ProcessInstance();
            pi.setId(1L); pi.setProcDefId(10L); pi.setProcessSnapshot(processJson);
            pi.setProcessDefVersion(1);
            pi.setStatus("RUNNING"); pi.setStartedBy(7L); pi.setCurrentNodeId("a1");
            return pi;
        });
        Mockito.when(formDataMapper.selectById(1L)).thenAnswer(inv -> {
            FormData fd = new FormData();
            fd.setId(1L); fd.setData("{\"k\":\"v\"}");
            return fd;
        });

        // 运行时传 rejectToNodeId="a1"，即使树里没配 refuse，也应驳回到 a1
        eng.reject(new CompleteCmd(1L, "reject", "再看看", "a1"), 42L);

        // 实例仍 RUNNING，新任务在 a1 上
        assertThat(lastInstance().getStatus()).isEqualTo("RUNNING");
        boolean hasReborn = capturesOfTaskInsert().stream()
            .anyMatch(t -> "a1".equals(t.getNodeId()) && "PENDING".equals(t.getStatus()));
        assertThat(hasReborn).isTrue();

        // 历史有 REJECT_TO_NODE
        ArgumentCaptor<TaskHistoryEntity> cap = ArgumentCaptor.forClass(TaskHistoryEntity.class);
        Mockito.verify(historyMapper, Mockito.atLeastOnce()).insert(cap.capture());
        boolean hasRtn = cap.getAllValues().stream()
            .anyMatch(h -> "REJECT_TO_NODE".equals(h.getAction()));
        assertThat(hasRtn).isTrue();
    }
}

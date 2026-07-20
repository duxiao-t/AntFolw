package com.antflow.mobile.workflow;

import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.StartCmd;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskHistoryEntity;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

@SuppressWarnings({"unchecked", "rawtypes"})
class MobileWorkflowServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProcessEngine engine;
    private MobileDraftService draftService;
    private MobileWorkflowMapper workflowMapper;
    private FormDefinitionService formDefinitionService;
    private FormDataMapper formDataMapper;
    private ProcessInstanceMapper instanceMapper;
    private TaskMapper taskMapper;
    private TaskHistoryMapper historyMapper;
    private MobileFileMapper fileMapper;
    private UserMapper userMapper;
    private DepartmentMapper departmentMapper;
    private MobileWorkflowService service;

    @BeforeEach
    void setUp() {
        engine = Mockito.mock(ProcessEngine.class);
        draftService = Mockito.mock(MobileDraftService.class);
        workflowMapper = Mockito.mock(MobileWorkflowMapper.class);
        formDefinitionService = Mockito.mock(FormDefinitionService.class);
        formDataMapper = Mockito.mock(FormDataMapper.class);
        instanceMapper = Mockito.mock(ProcessInstanceMapper.class);
        taskMapper = Mockito.mock(TaskMapper.class);
        historyMapper = Mockito.mock(TaskHistoryMapper.class);
        fileMapper = Mockito.mock(MobileFileMapper.class);
        userMapper = Mockito.mock(UserMapper.class);
        departmentMapper = Mockito.mock(DepartmentMapper.class);
        service = new MobileWorkflowService(engine, draftService, workflowMapper,
            formDefinitionService, formDataMapper, instanceMapper, taskMapper, historyMapper,
            fileMapper, userMapper, departmentMapper, objectMapper);
    }

    @Test
    void startLinksReadyFilesToSubmittedFormDataAndDeletesDraft() {
        UUID fileId = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        JsonNode data = objectMapper.createObjectNode().put("days", 2);
        Mockito.when(draftService.get(101L, 7L)).thenReturn(new MobileDraftDto(101L, 10L,
            "leave", "请假申请", 3, data, false, null, null));
        Mockito.when(fileMapper.selectById(fileId)).thenReturn(file(fileId, 7L, "READY"));
        Mockito.when(engine.start(any(StartCmd.class), Mockito.eq(7L)))
            .thenReturn(Map.of("instanceId", 501L, "formDataId", 301L,
                "firstTaskIds", List.of(401L)));

        MobileStartResult result = service.start(new StartMobileInstanceRequest("leave", data,
            Map.of("a1", List.of(8L)), 101L,
            List.of(new MobileFileRef(fileId, "attachment", 0))), 7L);

        assertThat(result.instanceId()).isEqualTo(501L);
        assertThat(result.formDataId()).isEqualTo(301L);
        ArgumentCaptor<StartCmd> startCaptor = ArgumentCaptor.forClass(StartCmd.class);
        Mockito.verify(engine).start(startCaptor.capture(), Mockito.eq(7L));
        assertThat(startCaptor.getValue().formCode()).isEqualTo("leave");
        assertThat(startCaptor.getValue().data()).isEqualTo(data);
        Mockito.verify(workflowMapper).insertFileLink(301L, fileId, "attachment", 0);
        Mockito.verify(draftService).deleteAfterSubmit(101L, 7L);
    }

    @Test
    void instanceDetailReadsProcessSnapshotAndConvertsJsonStrings() {
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instance(501L, 7L, "RUNNING"));
        Mockito.when(formDataMapper.selectById(301L)).thenReturn(formData(301L));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form());
        Mockito.when(taskMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(task(401L, 501L, "a1", 8L, "PENDING")));
        Mockito.when(historyMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(history("ARRIVE", "root", "a1")));
        Mockito.when(workflowMapper.selectFilesByFormDataId(301L)).thenReturn(List.of());

        MobileInstanceDetailDto detail = service.getInstanceDetail(501L, 7L, List.of("user"));

        assertThat(detail.processSnapshot().path("children").path("id").asText()).isEqualTo("a1");
        assertThat(detail.formData().path("days").asInt()).isEqualTo(2);
        assertThat(detail.schema().get(0).path("id").asText()).isEqualTo("days");
        assertThat(detail.history()).extracting(MobileHistoryDto::action).containsExactly("ARRIVE");
    }

    @Test
    void pendingTaskQueryOnlyReturnsCurrentAssignee() {
        Mockito.when(taskMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(task(401L, 501L, "a1", 8L, "PENDING")));
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instance(501L, 7L, "RUNNING"));
        Mockito.when(formDataMapper.selectById(301L)).thenReturn(formData(301L));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form());
        Mockito.when(userMapper.selectById(7L)).thenReturn(user(7L, "张三", 20L));
        Mockito.when(departmentMapper.selectById(20L)).thenReturn(department("研发部"));

        List<MobileTaskDto> tasks = service.listTasks("pending", 8L);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).applicantName()).isEqualTo("张三");
        ArgumentCaptor<QueryWrapper<TaskEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        Mockito.verify(taskMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment().toUpperCase();
        assertThat(sql).contains("ASSIGNEE_ID");
        assertThat(sql).contains("STATUS");
    }

    @Test
    void processDetailReturnsCanWithdrawFromServerRules() {
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instance(501L, 7L, "RUNNING"));
        Mockito.when(formDataMapper.selectById(301L)).thenReturn(formData(301L));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form());
        Mockito.when(taskMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(historyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(workflowMapper.selectFilesByFormDataId(301L)).thenReturn(List.of());

        MobileInstanceDetailDto detail = service.getInstanceDetail(501L, 7L, List.of("user"));

        assertThat(detail.canWithdraw()).isTrue();
    }

    @Test
    void taskDetailReturnsAllowedActionsAndLegalRejectTargets() {
        Mockito.when(taskMapper.selectById(401L))
            .thenReturn(task(401L, 501L, "a2", 8L, "PENDING"));
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instanceWithTwoApprovals());
        Mockito.when(formDataMapper.selectById(301L)).thenReturn(formData(301L));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form());
        Mockito.when(historyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(workflowMapper.selectFilesByFormDataId(301L)).thenReturn(List.of());
        Mockito.when(userMapper.selectById(7L)).thenReturn(user(7L, "张三", 20L));
        Mockito.when(departmentMapper.selectById(20L)).thenReturn(department("研发部"));

        MobileTaskDetailDto detail = service.getTaskDetail(401L, 8L, List.of("user"));

        assertThat(detail.allowedActions()).containsExactly("APPROVE", "REJECT");
        assertThat(detail.rejectTargets()).extracting(RejectTargetDto::nodeId)
            .containsExactly("a1");
        assertThat(detail.task().nodeName()).isEqualTo("部门审批");
    }

    @Test
    void taskDetailRejectsUnrelatedUser() {
        Mockito.when(taskMapper.selectById(401L))
            .thenReturn(task(401L, 501L, "a1", 8L, "PENDING"));
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instance(501L, 7L, "RUNNING"));

        assertThatThrownBy(() -> service.getTaskDetail(401L, 9L, List.of("user")))
            .isInstanceOf(AccessDeniedException.class);
    }

    private static MobileFile file(UUID id, Long ownerId, String status) {
        MobileFile file = new MobileFile();
        file.setId(id);
        file.setOwnerId(ownerId);
        file.setOriginalName("proof.pdf");
        file.setStorageKey(ownerId + "/" + id + "-proof.pdf");
        file.setContentType("application/pdf");
        file.setSizeBytes(16L);
        file.setSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        file.setStatus(status);
        return file;
    }

    private static FormDefinition form() {
        FormDefinition form = new FormDefinition();
        form.setId(10L);
        form.setCode("leave");
        form.setName("请假申请");
        form.setSchema("[{\"id\":\"days\",\"type\":\"number\",\"label\":\"请假天数\"}]");
        form.setStatus("PUBLISHED");
        form.setVersion(3);
        return form;
    }

    private static FormData formData(Long id) {
        FormData formData = new FormData();
        formData.setId(id);
        formData.setFormDefId(10L);
        formData.setFormDefVersion(3);
        formData.setData("{\"days\":2}");
        formData.setStatus("SUBMITTED");
        formData.setCreatedBy(7L);
        return formData;
    }

    private static ProcessInstance instance(Long id, Long startedBy, String status) {
        ProcessInstance instance = new ProcessInstance();
        instance.setId(id);
        instance.setProcDefId(201L);
        instance.setProcessDefVersion(5);
        instance.setProcessSnapshot("""
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
             "props":{"name":"部门审批","assignedType":"ASSIGN_USER","assignedUser":[8]}}}
            """);
        instance.setFormDataId(301L);
        instance.setStatus(status);
        instance.setCurrentNodeId("a1");
        instance.setStartedBy(startedBy);
        instance.setStartedAt(OffsetDateTime.parse("2026-07-20T09:00:00+08:00"));
        return instance;
    }

    private static ProcessInstance instanceWithTwoApprovals() {
        ProcessInstance instance = instance(501L, 7L, "RUNNING");
        instance.setCurrentNodeId("a2");
        instance.setProcessSnapshot("""
            {"id":"root","type":"ROOT","children":{"id":"a1","type":"APPROVAL",
             "props":{"name":"直属主管","assignedType":"ASSIGN_USER","assignedUser":[8]},
             "children":{"id":"a2","type":"APPROVAL",
             "props":{"name":"部门审批","assignedType":"ASSIGN_USER","assignedUser":[8]}}}}
            """);
        return instance;
    }

    private static TaskEntity task(Long id, Long instanceId, String nodeId, Long assigneeId,
                                   String status) {
        TaskEntity task = new TaskEntity();
        task.setId(id);
        task.setProcInstId(instanceId);
        task.setNodeId(nodeId);
        task.setAssigneeId(assigneeId);
        task.setStatus(status);
        task.setCreatedAt(OffsetDateTime.parse("2026-07-20T09:05:00+08:00"));
        return task;
    }

    private static TaskHistoryEntity history(String action, String from, String to) {
        TaskHistoryEntity history = new TaskHistoryEntity();
        history.setId(601L);
        history.setProcInstId(501L);
        history.setFromNodeId(from);
        history.setToNodeId(to);
        history.setAction(action);
        history.setOperatorId(7L);
        history.setCreatedAt(OffsetDateTime.parse("2026-07-20T09:06:00+08:00"));
        return history;
    }

    private static User user(Long id, String name, Long deptId) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(name);
        user.setDeptId(deptId);
        return user;
    }

    private static Department department(String name) {
        Department department = new Department();
        department.setId(20L);
        department.setName(name);
        return department;
    }
}

package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.StartCmd;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;

@SuppressWarnings({"unchecked", "rawtypes"})
class MobileWorkflowServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProcessEngine engine;
    private MobileDraftService draftService;
    private MobileWorkflowMapper workflowMapper;
    private FormDefinitionService formDefinitionService;
    private ProcessDefinitionService processDefinitionService;
    private FormDataMapper formDataMapper;
    private ProcessInstanceMapper instanceMapper;
    private TaskMapper taskMapper;
    private TaskHistoryMapper historyMapper;
    private MobileFileMapper fileMapper;
    private UserMapper userMapper;
    private DepartmentMapper departmentMapper;
    private MobileWorkflowService service;
    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        engine = Mockito.mock(ProcessEngine.class);
        draftService = Mockito.mock(MobileDraftService.class);
        workflowMapper = Mockito.mock(MobileWorkflowMapper.class);
        formDefinitionService = Mockito.mock(FormDefinitionService.class);
        processDefinitionService = Mockito.mock(ProcessDefinitionService.class);
        formDataMapper = Mockito.mock(FormDataMapper.class);
        instanceMapper = Mockito.mock(ProcessInstanceMapper.class);
        taskMapper = Mockito.mock(TaskMapper.class);
        historyMapper = Mockito.mock(TaskHistoryMapper.class);
        fileMapper = Mockito.mock(MobileFileMapper.class);
        userMapper = Mockito.mock(UserMapper.class);
        departmentMapper = Mockito.mock(DepartmentMapper.class);
        authorizationService = Mockito.mock(AuthorizationService.class);
        Mockito.when(authorizationService.canReadInstance(Mockito.anyLong(), Mockito.anyLong()))
            .thenReturn(true);
        Mockito.when(authorizationService.canReadFullInstance(Mockito.anyLong(), Mockito.anyLong()))
            .thenReturn(true);
        Mockito.when(authorizationService.instanceVisibility(Mockito.anyLong(), Mockito.anyLong()))
            .thenReturn(AuthorizationService.InstanceVisibility.FULL);
        Mockito.when(authorizationService.snapshot(Mockito.anyLong())).thenReturn(
            new AuthorizationService.AuthzSnapshot(8L, 20L, false, Set.of("user"),
                Set.of(PermissionCodes.WORKFLOW_TASK_APPROVE,
                    PermissionCodes.WORKFLOW_TASK_REJECT), Map.of()));
        Mockito.when(workflowMapper.selectApprovalTasks(Mockito.anyLong())).thenReturn(List.of());
        service = new MobileWorkflowService(engine, draftService, workflowMapper,
            formDefinitionService, processDefinitionService, formDataMapper, instanceMapper,
            taskMapper, historyMapper, fileMapper, objectMapper, authorizationService);
    }

    @Test
    void startLinksReadyFilesToSubmittedFormDataAndDeletesDraft() {
        UUID fileId = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        JsonNode data = objectMapper.createObjectNode().put("days", 2);
        Mockito.when(draftService.get(101L, 7L)).thenReturn(new MobileDraftDto(101L, 10L,
            "leave", "请假申请", 3, data, objectMapper.createArrayNode(), false, null, null));
        Mockito.when(formDefinitionService.getByCode("leave")).thenReturn(publishedForm(3));
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
    void startRejectsDraftFromOlderFormVersion() {
        JsonNode data = objectMapper.createObjectNode().put("days", 2);
        Mockito.when(draftService.get(101L, 7L)).thenReturn(new MobileDraftDto(101L, 10L,
            "leave", "请假申请", 2, data, objectMapper.createArrayNode(), false, null, null));
        Mockito.when(formDefinitionService.getByCode("leave")).thenReturn(publishedForm(3));

        assertThatThrownBy(() -> service.start(new StartMobileInstanceRequest("leave", data,
            Map.of(), 101L, List.of()), 7L))
            .isInstanceOf(BizException.class)
            .satisfies(exception -> assertThat(((BizException) exception).getCode()).isEqualTo("DRAFT_VERSION_MISMATCH"))
            .hasMessageContaining("表单已改版");
    }

    @Test
    void startRejectsFormWithoutUsageGrant() {
        JsonNode data = objectMapper.createObjectNode().put("days", 2);
        Mockito.when(formDefinitionService.getByCode("leave")).thenReturn(publishedForm(3));
        Mockito.doThrow(new HiddenResourceException("form not found"))
            .when(authorizationService)
            .requireFormAction(10L, PermissionCodes.FORM_RUNTIME_READ);

        assertThatThrownBy(() -> service.start(
            new StartMobileInstanceRequest("leave", data, Map.of(), null, List.of()), 7L))
            .isInstanceOf(HiddenResourceException.class);
        Mockito.verify(engine, Mockito.never()).start(Mockito.any(StartCmd.class),
            Mockito.anyLong());
    }

    @Test
    void instanceDetailReadsProcessSnapshotAndConvertsJsonStrings() {



        ProcessInstance instance = instance(501L, 7L, "RUNNING");
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instance);
        Mockito.when(workflowMapper.selectInstanceDetail(501L))
            .thenReturn(instanceDetailRow(instance));
        Mockito.when(formDataMapper.selectById(301L)).thenReturn(formData(301L));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form());
        Mockito.when(historyMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(history("ARRIVE", "root", "a1")));
        Mockito.when(workflowMapper.selectFilesByFormDataId(301L)).thenReturn(List.of());
        Mockito.when(userMapper.selectById(7L)).thenReturn(user(7L, "张三", 20L));
        Mockito.when(departmentMapper.selectById(20L)).thenReturn(department("研发部"));

        MobileInstanceDetailDto detail = service.getInstanceDetail(501L, 7L, List.of("user"));

        assertThat(detail.processSnapshot().path("children").path("id").asText()).isEqualTo("a1");
        assertThat(detail.formData().path("days").asInt()).isEqualTo(2);
        assertThat(detail.schema().get(0).path("id").asText()).isEqualTo("days");
        assertThat(detail.history()).extracting(MobileHistoryDto::action).containsExactly("ARRIVE");
        assertThat(detail.applicantName()).isEqualTo("张三");
        assertThat(detail.applicantDepartment()).isEqualTo("研发部");
        assertThat(detail.startedAt()).isEqualTo(
            OffsetDateTime.parse("2026-07-20T09:00:00+08:00"));
        assertThat(detail.currentNodeName()).isEqualTo("部门审批");
        ArgumentCaptor<QueryWrapper<TaskHistoryEntity>> historyQuery =
            ArgumentCaptor.forClass(QueryWrapper.class);
        Mockito.verify(historyMapper).selectList(historyQuery.capture());
        assertThat(historyQuery.getValue().getSqlSegment().toUpperCase()).contains("ACTION <>");
        assertThat(historyQuery.getValue().getParamNameValuePairs()).containsValue("SKIP");
    }

    @Test
    void reworkInstanceUsesUserFacingCurrentNodeName() {
        ProcessInstance reworkInstance = instance(501L, 7L, "RUNNING");
        reworkInstance.setCurrentNodeId("__rework__");
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(reworkInstance);
        Mockito.when(workflowMapper.selectInstanceDetail(501L))
            .thenReturn(instanceDetailRow(reworkInstance));
        Mockito.when(formDataMapper.selectById(301L)).thenReturn(formData(301L));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form());
        Mockito.when(taskMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(historyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(workflowMapper.selectFilesByFormDataId(301L)).thenReturn(List.of());

        MobileInstanceDetailDto detail = service.getInstanceDetail(501L, 7L, List.of("user"));

        assertThat(detail.currentNodeName()).isEqualTo("待修改原单");
    }

    @Test
    void pendingTaskQueryOnlyReturnsCurrentAssignee() {
        Mockito.when(workflowMapper.selectTaskPage(Mockito.eq(8L), Mockito.eq("pending"),
                Mockito.eq("请假"), Mockito.eq("PENDING"), Mockito.eq(21), Mockito.eq(20)))
            .thenReturn(List.of(taskRow(401L, 501L, "a1", "PENDING")));

        MobilePageDto<MobileTaskDto> tasks = service.listTasks("pending", 8L, 2, 20,
            " 请假 ", "PENDING");

        assertThat(tasks.items()).hasSize(1);
        assertThat(tasks.items().get(0).applicantName()).isEqualTo("张三");
        assertThat(tasks.hasMore()).isFalse();
        Mockito.verify(workflowMapper).selectTaskPage(8L, "pending", "请假", "PENDING",
            21, 20);
        Mockito.verifyNoInteractions(instanceMapper, formDataMapper, userMapper, departmentMapper);
    }

    @Test
    void processDetailReturnsCanWithdrawFromServerRules() {
        ProcessInstance instance = instance(501L, 7L, "RUNNING");
        Mockito.when(engine.canWithdraw(501L, 7L)).thenReturn(true);
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instance);
        Mockito.when(workflowMapper.selectInstanceDetail(501L))
            .thenReturn(instanceDetailRow(instance));
        Mockito.when(formDataMapper.selectById(301L)).thenReturn(formData(301L));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form());
        Mockito.when(taskMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(historyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(workflowMapper.selectFilesByFormDataId(301L)).thenReturn(List.of());

        MobileInstanceDetailDto detail = service.getInstanceDetail(501L, 7L, List.of("user"));

        assertThat(detail.canWithdraw()).isTrue();
    }

    @Test
    void startedInstanceQueryAppliesPagingKeywordAndStatus() {
        Mockito.when(workflowMapper.selectInstancePage(Mockito.eq(7L), Mockito.eq("采购"),
                Mockito.eq("RUNNING"), Mockito.eq(21), Mockito.eq(0)))
            .thenReturn(List.of(instanceRow(501L, "RUNNING")));

        MobilePageDto<MobileInstanceDto> instances = service.listInstances(7L, 1, 20,
            " 采购 ", "RUNNING");

        assertThat(instances.items()).hasSize(1);
        Mockito.verify(workflowMapper).selectInstancePage(7L, "采购", "RUNNING", 21, 0);
        Mockito.verifyNoInteractions(formDataMapper, formDefinitionService);
    }

    @Test
    void taskDetailReturnsAllowedActionsAndLegalRejectTargets() {
        TaskEntity task = task(401L, 501L, "a2", 8L, "PENDING");
        ProcessInstance instance = instanceWithTwoApprovals();
        Mockito.when(taskMapper.selectById(401L)).thenReturn(task);
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instance);
        Mockito.when(workflowMapper.selectTaskDetail(401L)).thenReturn(taskDetailRow(task, instance));
        Mockito.when(formDataMapper.selectById(301L)).thenReturn(formData(301L));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form());
        Mockito.when(historyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(workflowMapper.selectFilesByFormDataId(301L)).thenReturn(List.of());
        Mockito.when(userMapper.selectById(7L)).thenReturn(user(7L, "张三", 20L));
        Mockito.when(departmentMapper.selectById(20L)).thenReturn(department("研发部"));

        MobileTaskDetailDto detail = service.getTaskDetail(401L, 8L, List.of("user"));

        assertThat(detail.allowedActions()).containsExactly("APPROVE", "REJECT");
        assertThat(detail.rejectDisabled()).isFalse();
        assertThat(detail.rejectTargets())
            .containsExactly(new RejectTargetDto("a1", "直属主管"));
        assertThat(detail.task().nodeName()).isEqualTo("部门审批");
    }

    @Test
    void taskDetailRejectsUnrelatedUser() {
        TaskEntity task = task(401L, 501L, "a1", 8L, "PENDING");
        Mockito.when(taskMapper.selectById(401L)).thenReturn(task);
        ProcessInstance instance = instance(501L, 7L, "RUNNING");
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instance);
        Mockito.when(workflowMapper.selectTaskDetail(401L)).thenReturn(taskDetailRow(task, instance));
        Mockito.when(authorizationService.canReadFullInstance(501L, 9L)).thenReturn(false);

        assertThatThrownBy(() -> service.getTaskDetail(401L, 9L, List.of("user")))
            .isInstanceOf(HiddenResourceException.class);
    }

    @Test
    void parallelTaskDetailDisablesRejectButKeepsApprove() {
        TaskEntity task = task(401L, 501L, "a2", 8L, "PENDING");
        task.setParallelId("parallel");
        ProcessInstance instance = instanceWithTwoApprovals();
        Mockito.when(workflowMapper.selectTaskDetail(401L)).thenReturn(taskDetailRow(task, instance));
        Mockito.when(historyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(workflowMapper.selectFilesByFormDataId(301L)).thenReturn(List.of());

        MobileTaskDetailDto detail = service.getTaskDetail(401L, 8L, List.of("user"));

        assertThat(detail.allowedActions()).containsExactly("APPROVE");
        assertThat(detail.rejectDisabled()).isTrue();
    }

    @Test
    void markTaskReadCompletesUnreadCcTaskForItsRecipient() {
        TaskEntity cc = task(402L, 501L, "cc1", 8L, "CC");
        Mockito.when(taskMapper.selectById(402L)).thenReturn(cc);

        service.markTaskRead(402L, 8L);

        assertThat(cc.getReadAt()).isNotNull();
        Mockito.verify(taskMapper).updateById(cc);
    }

    @Test
    void unreadCcDetailOffersAcknowledgeAndAppearsInApprovalRecords() {
        TaskEntity cc = task(402L, 501L, "cc1", 8L, "CC");
        ProcessInstance instance = instance(501L, 7L, "APPROVED");
        instance.setProcessSnapshot("""
            {"id":"root","type":"ROOT","children":{"id":"parallel","type":"PARALLEL",
             "branchs":[
               {"id":"approval-branch","type":"BRANCH","children":{"id":"a2","name":"财务审批","type":"APPROVAL"}},
               {"id":"cc-branch","type":"BRANCH","children":{"id":"cc1","name":"抄送人","type":"CC"}}
             ]}}
            """);
        Mockito.when(taskMapper.selectById(402L)).thenReturn(cc);
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instance);
        Mockito.when(workflowMapper.selectTaskDetail(402L)).thenReturn(taskDetailRow(cc, instance));
        Mockito.when(workflowMapper.selectApprovalTasks(501L))
            .thenReturn(List.of(
                new MobileWorkflowMapper.ApprovalRow(403L, "a2", "APPROVAL", "APPROVED",
                    null, null, "TRANSFER", "王五", "赵六", "E0009", "财务部", "同意",
                    OffsetDateTime.parse("2026-07-20T09:01:00+08:00"),
                    OffsetDateTime.parse("2026-07-20T09:02:00+08:00"), null, 1),
                approvalRow(cc, "李四")));
        Mockito.when(formDataMapper.selectById(301L)).thenReturn(formData(301L));
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form());
        Mockito.when(taskMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(cc));
        Mockito.when(historyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        Mockito.when(workflowMapper.selectFilesByFormDataId(301L)).thenReturn(List.of());
        Mockito.when(userMapper.selectById(7L)).thenReturn(user(7L, "张三", 20L));
        Mockito.when(userMapper.selectById(8L)).thenReturn(user(8L, "李四", 20L));
        Mockito.when(departmentMapper.selectById(20L)).thenReturn(department("研发部"));

        MobileTaskDetailDto detail = service.getTaskDetail(402L, 8L, List.of("user"));

        assertThat(detail.allowedActions()).containsExactly("ACKNOWLEDGE");
        assertThat(detail.approvalRecords()).extracting(ApprovalRecordDto::nodeName,
            ApprovalRecordDto::status, ApprovalRecordDto::operatorName,
            ApprovalRecordDto::comment)
            .contains(tuple("抄送人", "PROCESSING", "李四", "等待确认抄送内容。"));
        ApprovalRecordDto transferred = detail.approvalRecords().get(1);
        assertThat(transferred.parallelId()).isEqualTo("parallel");
        assertThat(transferred.branchId()).isEqualTo("approval-branch");
        assertThat(transferred.operationKind()).isEqualTo("TRANSFER");
        assertThat(transferred.sourceOperatorName()).isEqualTo("王五");
        ApprovalRecordDto ccRecord = detail.approvalRecords().get(2);
        assertThat(ccRecord.recordKind()).isEqualTo("CC");
        assertThat(ccRecord.nodeType()).isEqualTo("CC");
        assertThat(ccRecord.parallelId()).isEqualTo("parallel");
        assertThat(ccRecord.branchId()).isEqualTo("cc-branch");
    }

    @Test
    void resubmitReworkUpdatesOriginalFormDataAndReusesInstance() {
        TaskEntity rework = task(410L, 501L, "__rework__", 7L, "PENDING");
        rework.setTaskType("REWORK");
        Mockito.when(taskMapper.selectById(410L)).thenReturn(rework);
        Mockito.when(instanceMapper.selectById(501L)).thenReturn(instance(501L, 7L, "RUNNING"));
        FormData original = formData(301L);
        original.setBusinessNo("000000000301");
        original.setStatus("NEEDS_REVISION");
        Mockito.when(formDataMapper.selectById(301L)).thenReturn(original);
        Mockito.when(formDefinitionService.getById(10L)).thenReturn(form());
        Mockito.when(engine.resubmitRework(410L, 7L)).thenReturn(List.of(411L));
        JsonNode changed = objectMapper.createObjectNode().put("days", 3);

        ReworkResult result = service.resubmitRework(410L,
            new ReworkTaskRequest(changed, List.of()), 7L);

        assertThat(result.instanceId()).isEqualTo(501L);
        assertThat(result.formDataId()).isEqualTo(301L);
        assertThat(result.businessNo()).isEqualTo("000000000301");
        assertThat(result.firstTaskIds()).containsExactly(411L);
        assertThat(original.getData()).contains("\"days\":3");
        Mockito.verify(workflowMapper).deleteFileLinks(301L);
        Mockito.verify(engine).resubmitRework(410L, 7L);
    }

    @Test
    void mobileFormDetailReturnsPublishedSchemaForFillPage() {
        Mockito.when(formDefinitionService.getByCode("leave")).thenReturn(form());
        Mockito.when(processDefinitionService.latestPublishedForForm(10L)).thenReturn(process());

        MobileFormDto detail = service.getMobileForm("leave");

        assertThat(detail.code()).isEqualTo("leave");
        assertThat(detail.name()).isEqualTo("请假申请");
        assertThat(detail.version()).isEqualTo(3);
        Mockito.verify(authorizationService).requireFormAction(10L,
            PermissionCodes.FORM_RUNTIME_READ);
        assertThat(detail.schema().get(0).path("id").asText()).isEqualTo("days");
        assertThat(detail.process().path("children").path("props").path("assignedType").asText())
            .isEqualTo("SELF_SELECT");
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

    private static ProcessDefinition process() {
        ProcessDefinition process = new ProcessDefinition();
        process.setId(201L);
        process.setFormDefId(10L);
        process.setVersion(5);
        process.setStatus("PUBLISHED");
        process.setProcess("""
            {"id":"root","type":"ROOT","children":{"id":"manager","type":"APPROVAL",
             "props":{"name":"直属主管","assignedType":"SELF_SELECT",
             "selfSelect":{"multiple":false}}}}
            """);
        return process;
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

    private static MobileWorkflowMapper.TaskRow taskRow(Long id, Long instanceId,
                                                        String nodeId, String status) {
        return new MobileWorkflowMapper.TaskRow(id, instanceId, nodeId, "leave", "请假申请",
            "000000000301", "张三", "E0007", "研发部",
            instance(instanceId, 7L, "RUNNING").getProcessSnapshot(), "APPROVAL", status,
            "RUNNING", OffsetDateTime.parse("2026-07-20T09:05:00+08:00"), null);
    }

    private static MobileWorkflowMapper.InstanceRow instanceRow(Long id, String status) {
        ProcessInstance instance = instance(id, 7L, status);
        return new MobileWorkflowMapper.InstanceRow(id, status, "请假申请", "000000000301",
            instance.getCurrentNodeId(), instance.getProcessSnapshot(), instance.getStartedAt(),
            instance.getFinishedAt());
    }

    private static MobileWorkflowMapper.InstanceDetailRow instanceDetailRow(
            ProcessInstance instance) {
        return new MobileWorkflowMapper.InstanceDetailRow(instance.getId(), instance.getStatus(),
            instance.getCurrentNodeId(), instance.getProcessSnapshot(), instance.getStartedBy(),
            instance.getStartedAt(), instance.getFinishedAt(), instance.getFormDataId(),
            "000000000301", "{\"days\":2}", "leave", "请假申请",
            "[{\"id\":\"days\",\"type\":\"number\",\"label\":\"请假天数\"}]",
            "张三", "E0007", "研发部");
    }

    private static MobileWorkflowMapper.TaskDetailRow taskDetailRow(
            TaskEntity task, ProcessInstance instance) {
        return new MobileWorkflowMapper.TaskDetailRow(task.getId(), instance.getId(),
            task.getNodeId(), task.getAssigneeId(), task.getParallelId(), null,
            task.getTaskType() == null ? "APPROVAL" : task.getTaskType(), task.getStatus(),
            task.getCreatedAt(), task.getReadAt(), instance.getStatus(), instance.getCurrentNodeId(),
            instance.getProcessSnapshot(), instance.getStartedBy(), instance.getStartedAt(),
            instance.getFinishedAt(), instance.getFormDataId(), "000000000301", "{\"days\":2}",
            "leave", "请假申请",
            "[{\"id\":\"days\",\"type\":\"number\",\"label\":\"请假天数\"}]",
            "张三", "E0007", "研发部");
    }

    private static MobileWorkflowMapper.ApprovalRow approvalRow(TaskEntity task, String name) {
        return new MobileWorkflowMapper.ApprovalRow(task.getId(), task.getNodeId(),
            task.getTaskType(), task.getStatus(), task.getParallelId(), task.getBranchId(),
            null, null, name, "E0008", "研发部", task.getComment(),
            task.getCreatedAt(), task.getApprovedAt(), task.getReadAt(), 1);
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

    private static FormDefinition publishedForm(int version) {
        FormDefinition form = new FormDefinition();
        form.setId(10L);
        form.setCode("leave");
        form.setName("请假申请");
        form.setVersion(version);
        form.setStatus("PUBLISHED");
        return form;
    }
    private static Department department(String name) {
        Department department = new Department();
        department.setId(20L);
        department.setName(name);
        return department;
    }
}

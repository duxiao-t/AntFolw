package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskHistoryEntity;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MobileWorkflowService {
    private static final long CC_TASK_ID_BASE = 8_000_000_000_000_000L;
    private static final String PENDING_STATUS = "PENDING";
    private static final String PUBLISHED_STATUS = "PUBLISHED";
    private static final String RUNNING_STATUS = "RUNNING";
    private static final String APPROVAL_NODE = "APPROVAL";
    private static final String ADMIN_ROLE = "admin";

    private final ProcessEngine engine;
    private final MobileDraftService draftService;
    private final MobileWorkflowMapper workflowMapper;
    private final FormDefinitionService formDefinitionService;
    private final ProcessDefinitionService processDefinitionService;
    private final FormDataMapper formDataMapper;
    private final ProcessInstanceMapper instanceMapper;
    private final TaskMapper taskMapper;
    private final TaskHistoryMapper historyMapper;
    private final MobileFileLinkService fileLinkService;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorizationService;
    @Autowired(required = false)
    private DefinitionVersionRepository definitionVersions;

    public MobileFormDto getMobileForm(String code) {
        FormDefinition form = formDefinitionService.getByCode(code);
        if (form == null || !PUBLISHED_STATUS.equals(form.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + code);
        }
        authorizationService.requireFormAction(form.getId(), PermissionCodes.FORM_RUNTIME_READ);
        ProcessDefinition process = processDefinitionService.latestPublishedForForm(form.getId());
        JsonNode processTree = readJsonObject(
            process == null ? null : process.getProcess(), "BAD_FLOW_JSON");
        JsonNode schema = readJsonArray(form.getSchema(), "BAD_SCHEMA_JSON");
        return new MobileFormDto(form.getCode(), form.getName(), form.getVersion(),
            formDefinitionService.projectStarterSchema(schema, processTree),
            readJsonObject(form.getSettings(), "BAD_SETTINGS_JSON"),
            processTree, formDefinitionService.starterFieldModes(processTree));
    }

    @Transactional(rollbackFor = Exception.class)
    public MobileStartResult start(StartMobileInstanceRequest request, long userId) {
        JsonNode data = request.data() == null ? objectMapper.createObjectNode() : request.data();
        FormDefinition currentForm = formDefinitionService.getByCode(request.formCode());
        if (currentForm == null || !PUBLISHED_STATUS.equals(currentForm.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + request.formCode());
        }
        authorizationService.requireFormAction(currentForm.getId(),
            PermissionCodes.FORM_RUNTIME_READ);
        if (request.draftId() != null) {
            MobileDraftDto draft = draftService.get(request.draftId(), userId);
            if (!Objects.equals(draft.formCode(), request.formCode())) {
                throw new BizException("BAD_DRAFT", "draft does not belong to requested form");
            }
            if (draft.readOnly()) {
                throw new BizException("BAD_DRAFT", "draft is read only");
            }
            if (draft.formVersion() != currentForm.getVersion()) {
                throw new BizException("DRAFT_VERSION_MISMATCH",
                    "表单已改版，请基于新版本重新填写");
            }
        }

        Map<String, List<Long>> selfSelected = request.selfSelected() == null
            ? Map.of() : request.selfSelected();
        Map<String, Object> result = engine.start(new StartCmd(request.formCode(), data, selfSelected),
            userId);
        Long formDataId = asLong(result.get("formDataId"));
        Long instanceId = asLong(result.get("instanceId"));
        String businessNo = Objects.toString(result.get("businessNo"), null);
        List<Long> firstTaskIds = asLongList(result.get("firstTaskIds"));

        ProcessDefinition currentProcess =
            processDefinitionService.latestPublishedForForm(currentForm.getId());
        Map<String, String> starterModes = formDefinitionService.starterFieldModes(
            currentProcess == null ? null : currentProcess.getProcess());
        fileLinkService.append(formDataId,
            starterEditableFiles(filesOf(request), starterModes), userId);
        if (definitionVersions != null) {
            ProcessInstance started = instanceMapper.selectById(instanceId);
            if (started != null && started.getCurrentFormRevisionId() != null) {
                definitionVersions.syncRevisionFiles(started.getCurrentFormRevisionId(), formDataId);
            }
        }
        if (request.draftId() != null) {
            draftService.deleteAfterSubmit(request.draftId(), userId);
        }
        return new MobileStartResult(instanceId, formDataId, businessNo, firstTaskIds);
    }

    public MobilePageDto<MobileInstanceDto> listInstances(long userId, int page, int size,
                                                          String keyword, String status) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 50);
        int fetchSize = normalizedSize + 1;
        int offset = (normalizedPage - 1) * normalizedSize;
        List<MobileInstanceDto> items = workflowMapper.selectInstancePage(userId,
                normalizedText(keyword), normalizedText(status), fetchSize, offset)
            .stream()
            .map(this::toInstanceDto)
            .toList();
        boolean hasMore = items.size() > normalizedSize;
        return new MobilePageDto<>(
            hasMore ? items.subList(0, normalizedSize) : items,
            hasMore
        );
    }

    public MobileInstanceDetailDto getInstanceDetail(Long instanceId, long userId,
                                                      java.util.Collection<String> roles) {
        AuthorizationService.InstanceVisibility visibility =
            authorizationService.instanceVisibility(instanceId, userId);
        if (visibility == AuthorizationService.InstanceVisibility.NONE) {
            throw new HiddenResourceException("instance not found");
        }
        MobileWorkflowMapper.InstanceDetailRow row = workflowMapper.selectInstanceDetail(instanceId);
        if (row == null) throw new BizException("NOT_FOUND", "instance not found");
        ProcessInstance instance = toProcessInstance(row);
        JsonNode snapshot = readJsonObject(row.processSnapshot(), "BAD_FLOW_JSON");
        List<ApprovalRecordDto> records = approvalRecords(row.instanceId(), snapshot,
            row.applicantName(), row.applicantEmployeeNo(), row.applicantDepartment(),
            row.startedAt());
        if (visibility == AuthorizationService.InstanceVisibility.SUMMARY) {
            return new MobileInstanceDetailDto(
                visibility.name(), row.instanceId(), row.instanceStatus(), null, null,
                row.applicantName(), row.applicantEmployeeNo(), row.applicantDepartment(),
                row.startedAt(), nodeName(snapshot, row.currentNodeId()), null, null, null,
                history(row.instanceId()), false, List.of(), approvalSummary(instance, records), records);
        }
        JsonNode formSchema = readJsonArray(row.formSchema(), "BAD_SCHEMA_JSON");
        JsonNode formData = readJsonObject(row.formDataJson(), "BAD_JSON");
        boolean starterView = Objects.equals(row.startedBy(), userId) && !roles.contains(ADMIN_ROLE);
        if (starterView) {
            formData = formDefinitionService.projectStarterData(formData, formSchema, snapshot);
            formSchema = formDefinitionService.projectStarterSchema(formSchema, snapshot);
        }
        return new MobileInstanceDetailDto(
            visibility.name(),
            row.instanceId(),
            row.instanceStatus(),
            row.formName(),
            row.businessNo(),
            row.applicantName(),
            row.applicantEmployeeNo(),
            row.applicantDepartment(),
            row.startedAt(),
            nodeName(snapshot, row.currentNodeId()),
            formSchema,
            formData,
            snapshot,
            history(row.instanceId()),
            canWithdraw(instance, userId),
            starterView ? starterFiles(row.formDataId(), snapshot) : files(row.formDataId()),
            approvalSummary(instance, records),
            records
        );
    }

    public MobilePageDto<MobileTaskDto> listTasks(String view, long userId, int page, int size,
                                                  String keyword, String status) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 50);
        int fetchSize = normalizedSize + 1;
        int offset = (normalizedPage - 1) * normalizedSize;
        String normalizedView = "done".equalsIgnoreCase(view) ? "done" : "pending";
        List<MobileTaskDto> items = workflowMapper.selectTaskPage(userId, normalizedView,
                normalizedText(keyword), normalizedText(status), fetchSize, offset).stream()
            .map(this::toTaskDto)
            .toList();
        boolean hasMore = items.size() > normalizedSize;
        return new MobilePageDto<>(
            hasMore ? items.subList(0, normalizedSize) : items,
            hasMore
        );
    }

    public MobileTaskDetailDto getTaskDetail(Long taskId, long userId,
                                             java.util.Collection<String> roles) {
        MobileWorkflowMapper.TaskDetailRow row = workflowMapper.selectTaskDetail(taskId);
        if (row == null) throw new BizException("NOT_FOUND", "task not found");
        if (!authorizationService.canReadFullInstance(row.instanceId(), userId)) {
            throw new HiddenResourceException("task not found");
        }
        TaskEntity task = toTaskEntity(row);
        ProcessInstance instance = toProcessInstance(row);
        JsonNode snapshot = readJsonObject(row.processSnapshot(), "BAD_FLOW_JSON");
        List<ApprovalRecordDto> records = approvalRecords(row.instanceId(), snapshot,
            row.applicantName(), row.applicantEmployeeNo(), row.applicantDepartment(),
            row.startedAt());
        Set<String> permissions = authorizationService.snapshot(userId).permissions();
        JsonNode formSchema = readJsonArray(row.formSchema(), "BAD_SCHEMA_JSON");
        JsonNode formData = readJsonObject(row.formDataJson(), "BAD_JSON");
        if (Objects.equals(row.startedBy(), userId) && !roles.contains(ADMIN_ROLE)) {
            formData = formDefinitionService.projectStarterData(formData, formSchema, snapshot);
            formSchema = formDefinitionService.projectStarterSchema(formSchema, snapshot);
        }
        return new MobileTaskDetailDto(
            toTaskDto(row, snapshot),
            formSchema,
            formData,
            snapshot,
            history(row.instanceId()),
            allowedActions(task, userId, permissions),
            task.getParallelId() != null
                && task.getNodeInstanceId() == null
                && PENDING_STATUS.equals(task.getStatus())
                && Objects.equals(task.getAssigneeId(), userId)
                && permissions.contains(PermissionCodes.WORKFLOW_TASK_REJECT),
            task.getParallelId() == null && !fixedRejectTarget(task)
                ? rejectTargets(snapshot, task.getNodeId()) : List.of(),
            Objects.equals(row.startedBy(), userId) && !roles.contains(ADMIN_ROLE)
                ? starterFiles(row.formDataId(), snapshot) : files(row.formDataId()),
            approvalSummary(instance, records),
            records,
            processDefinitionService.commentPresets(snapshot, task.getNodeId())
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public void markTaskRead(Long taskId, long userId) {
        if (taskId != null && taskId >= CC_TASK_ID_BASE) {
            int updated = workflowMapper.markCcRead(taskId - CC_TASK_ID_BASE, userId);
            if (updated == 0) throw new AccessDeniedException("not your task");
            return;
        }
        TaskEntity task = requireExistingTask(taskId);
        if (!Objects.equals(task.getAssigneeId(), userId)) {
            throw new AccessDeniedException("not your task");
        }
        if (!"CC".equals(task.getStatus()) || task.getReadAt() != null) return;
        task.setReadAt(OffsetDateTime.now());
        taskMapper.updateById(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long taskId, MobileTaskActionRequest request, long userId) {
        Object data = request == null || request.data() == null
            ? null : objectMapper.convertValue(request.data(), Map.class);
        engine.approve(new CompleteCmd(taskId, "APPROVE",
            request == null ? null : request.comment(), null, data), userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long taskId, MobileTaskActionRequest request, long userId) {
        engine.reject(new CompleteCmd(taskId, "REJECT",
            request == null ? null : request.comment(),
            request == null ? null : request.rejectToNodeId()), userId);
    }

    public ReworkTaskDto getReworkTask(Long taskId, long userId) {
        TaskEntity task = requireOwnedReworkTask(taskId, userId);
        ProcessInstance instance = requireExistingInstance(task.getProcInstId());
        FormData formData = requireFormData(instance.getFormDataId());
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        if (form == null) {
            throw new BizException("NOT_FOUND", "form definition not found");
        }
        String schema = instance.getCurrentFormRevisionId() != null && definitionVersions != null
            ? definitionVersions.revisionSchema(instance.getCurrentFormRevisionId()) : null;
        String effectiveSchema = schema == null ? form.getSchema() : schema;
        JsonNode schemaTree = readJsonArray(effectiveSchema, "BAD_SCHEMA_JSON");
        JsonNode snapshot = readJsonObject(instance.getProcessSnapshot(), "BAD_FLOW_JSON");
        return new ReworkTaskDto(task.getId(), instance.getId(), form.getCode(), form.getName(),
            formData.getBusinessNo(),
            formDefinitionService.projectStarterSchema(schemaTree, snapshot),
            formDefinitionService.projectStarterData(formData.getData(), schemaTree, snapshot),
            snapshot,
            starterFiles(formData.getId(), snapshot));
    }

    @Transactional(rollbackFor = Exception.class)
    public ReworkTaskDto saveRework(Long taskId, ReworkTaskRequest request, long userId) {
        TaskEntity task = requireOwnedReworkTask(taskId, userId);
        updateRework(task, request, userId, false);
        return getReworkTask(taskId, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ReworkResult resubmitRework(Long taskId, ReworkTaskRequest request, long userId) {
        TaskEntity task = requireOwnedReworkTask(taskId, userId);
        FormData formData = updateRework(task, request, userId, true);
        List<Long> firstTaskIds = engine.resubmitRework(taskId, userId);
        return new ReworkResult(task.getProcInstId(), formData.getId(), formData.getBusinessNo(),
            firstTaskIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long instanceId, long userId) {
        engine.withdraw(instanceId, userId);
    }

    private ProcessInstance requireReadableInstance(Long instanceId, long userId) {
        ProcessInstance instance = requireExistingInstance(instanceId);
        if (authorizationService.canReadInstance(instanceId, userId)) {
            return instance;
        }
        throw new HiddenResourceException("instance not found");
    }

    private TaskEntity requireOwnedReworkTask(Long taskId, long userId) {
        TaskEntity task = requireExistingTask(taskId);
        if (!"PENDING".equals(task.getStatus()) || !"REWORK".equals(task.getTaskType())) {
            throw new BizException("TASK_NOT_PENDING", "待修改任务已处理");
        }
        if (!Objects.equals(task.getAssigneeId(), userId)) {
            throw new AccessDeniedException("not your rework task");
        }
        return task;
    }

    private FormData updateRework(TaskEntity task, ReworkTaskRequest request, long userId,
                                  boolean validate) {
        if (request == null) {
            throw new BizException("BAD_REQUEST", "表单内容不能为空");
        }
        ProcessInstance instance = requireExistingInstance(task.getProcInstId());
        FormData formData = requireFormData(instance.getFormDataId());
        JsonNode data = request.data() == null ? objectMapper.createObjectNode() : request.data();
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        if (form == null) {
            throw new BizException("NOT_FOUND", "form definition not found");
        }
        String schema = instance.getCurrentFormRevisionId() != null
            && definitionVersions != null
            ? definitionVersions.revisionSchema(instance.getCurrentFormRevisionId()) : null;
        String effectiveSchema = schema == null ? form.getSchema() : schema;
        Map<String, Object> canonicalData =
            formDefinitionService.canonicalizeStarterRevision(effectiveSchema, data,
                formData.getData(), instance.getProcessSnapshot());
        if (validate) {
            formDefinitionService.validateStarterSubmission(effectiveSchema, canonicalData,
                instance.getProcessSnapshot());
        }
        formData.setData(writeJson(objectMapper.valueToTree(canonicalData)));
        formData.setStatus("NEEDS_REVISION");
        formDataMapper.updateById(formData);
        if (request.files() != null) {
            Map<String, String> starterModes = formDefinitionService.starterFieldModes(
                instance.getProcessSnapshot());
            fileLinkService.reconcileEditable(formData.getId(), request.files(), userId,
                starterModes);
        }
        return formData;
    }

    private ProcessInstance requireExistingInstance(Long instanceId) {
        ProcessInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new BizException("NOT_FOUND", "instance not found");
        }
        return instance;
    }

    private TaskEntity requireExistingTask(Long taskId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("NOT_FOUND", "task not found");
        }
        return task;
    }

    private FormData requireFormData(Long formDataId) {
        FormData formData = formDataMapper.selectById(formDataId);
        if (formData == null) {
            throw new BizException("NOT_FOUND", "form data not found");
        }
        return formData;
    }

    private boolean isParticipant(Long instanceId, long userId) {
        return taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", instanceId)
            .eq("assignee_id", userId)
            .last("LIMIT 1")).size() > 0;
    }

    private boolean canWithdraw(ProcessInstance instance, long userId) {
        return engine.canWithdraw(instance.getId(), userId);
    }

    private MobileInstanceDto toInstanceDto(MobileWorkflowMapper.InstanceRow row) {
        JsonNode snapshot = readJsonObject(row.processSnapshot(), "BAD_FLOW_JSON");
        return new MobileInstanceDto(row.id(), row.status(), row.formName(), row.businessNo(),
            nodeName(snapshot, row.currentNodeId()), row.startedAt(), row.finishedAt());
    }

    private static ProcessInstance toProcessInstance(MobileWorkflowMapper.InstanceDetailRow row) {
        ProcessInstance instance = new ProcessInstance();
        instance.setId(row.instanceId());
        instance.setFormDataId(row.formDataId());
        instance.setProcessSnapshot(row.processSnapshot());
        instance.setStatus(row.instanceStatus());
        instance.setCurrentNodeId(row.currentNodeId());
        instance.setStartedBy(row.startedBy());
        instance.setStartedAt(row.startedAt());
        instance.setFinishedAt(row.finishedAt());
        return instance;
    }

    private static ProcessInstance toProcessInstance(MobileWorkflowMapper.TaskDetailRow row) {
        ProcessInstance instance = new ProcessInstance();
        instance.setId(row.instanceId());
        instance.setFormDataId(row.formDataId());
        instance.setProcessSnapshot(row.processSnapshot());
        instance.setStatus(row.instanceStatus());
        instance.setCurrentNodeId(row.currentNodeId());
        instance.setStartedBy(row.startedBy());
        instance.setStartedAt(row.startedAt());
        instance.setFinishedAt(row.finishedAt());
        return instance;
    }

    private static TaskEntity toTaskEntity(MobileWorkflowMapper.TaskDetailRow row) {
        TaskEntity task = new TaskEntity();
        task.setId(row.taskId());
        task.setProcInstId(row.instanceId());
        task.setNodeId(row.nodeId());
        task.setAssigneeId(row.assigneeId());
        task.setParallelId(row.parallelId());
        task.setNodeInstanceId(row.nodeInstanceId());
        task.setApprovalMode(row.approvalMode());
        task.setTaskType(row.taskType());
        task.setStatus(row.taskStatus());
        task.setCreatedAt(row.taskCreatedAt());
        task.setReadAt(row.readAt());
        return task;
    }

    private MobileTaskDto toTaskDto(MobileWorkflowMapper.TaskRow row) {
        JsonNode snapshot = readJsonObject(row.processSnapshot(), "BAD_FLOW_JSON");
        return new MobileTaskDto(row.id(), row.instanceId(), row.nodeId(), row.formCode(),
            row.formName(), row.businessNo(), row.applicantName(), row.applicantEmployeeNo(),
            row.applicantDepartment(), "REWORK".equals(row.taskType()) ? "待修改原单"
                : nodeName(snapshot, row.nodeId()), row.taskType(), row.taskStatus(),
            row.instanceStatus(), row.createdAt(), row.readAt());
    }

    private MobileTaskDto toTaskDto(MobileWorkflowMapper.TaskDetailRow row, JsonNode snapshot) {
        return new MobileTaskDto(row.taskId(), row.instanceId(), row.nodeId(), row.formCode(),
            row.formName(), row.businessNo(), row.applicantName(), row.applicantEmployeeNo(),
            row.applicantDepartment(), "REWORK".equals(row.taskType()) ? "待修改原单"
                : nodeName(snapshot, row.nodeId()), row.taskType(), row.taskStatus(),
            row.instanceStatus(), row.taskCreatedAt(), row.readAt());
    }

    private List<MobileHistoryDto> history(Long instanceId) {
        return historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
                .eq("proc_inst_id", instanceId)
                .ne("action", "SKIP")
                .orderByAsc("created_at")
                .orderByAsc("id"))
            .stream()
            .map(history -> new MobileHistoryDto(history.getId(), history.getFromNodeId(),
                history.getToNodeId(), history.getTaskId(), history.getAction(),
                history.getOperatorId(), history.getComment(), history.getCreatedAt()))
            .toList();
    }

    private List<ApprovalRecordDto> approvalRecords(Long instanceId, JsonNode snapshot,
                                                    String applicantName,
                                                    String applicantEmployeeNo,
                                                    String applicantDepartment,
                                                    OffsetDateTime startedAt) {
        List<ApprovalRecordDto> records = new ArrayList<>();
        records.add(new ApprovalRecordDto(
            "submission", null, snapshot.path("id").asText("root"), "提交申请",
            "SUBMISSION", "ROOT", null, null, null, null, "SUBMITTED",
            applicantName == null ? "未记录" : applicantName, applicantEmployeeNo,
            applicantDepartment, null, startedAt, startedAt, 1));

        for (MobileWorkflowMapper.ApprovalRow task : workflowMapper.selectApprovalTasks(instanceId)) {
            String status = approvalRecordStatus(task);
            if (status == null) {
                continue;
            }
            JsonNode node = ProcessTreeNav.findById(snapshot, task.nodeId());
            ProcessTreeNav.ParallelParent parent = ProcessTreeNav.findParallelParent(
                snapshot, task.nodeId());
            String taskType = "REWORK".equals(task.taskType()) ? "REWORK"
                : "CC".equals(task.taskStatus()) ? "CC" : "APPROVAL";
            records.add(new ApprovalRecordDto(
                "task-" + task.taskId(), task.taskId(), task.nodeId(),
                "REWORK".equals(task.taskType()) ? "退回修改"
                    : nodeName(snapshot, task.nodeId()),
                taskType, node == null ? taskType : node.path("type").asText(taskType),
                task.parallelId() != null ? task.parallelId()
                    : parent == null ? null : parent.parallelId(),
                task.branchId() != null ? task.branchId()
                    : parent == null ? null : parent.branchId(),
                task.operationKind(), task.sourceOperatorName(), status,
                task.operatorName(), task.employeeNo(), task.department(),
                approvalRecordComment(task), task.receivedAt(),
                "CC".equals(task.taskStatus()) ? task.readAt() : task.approvedAt(),
                task.roundNo()));
        }
        return records;
    }

    private ApprovalSummaryDto approvalSummary(ProcessInstance instance,
                                                List<ApprovalRecordDto> records) {
        int processing = (int) records.stream()
            .filter(record -> "PROCESSING".equals(record.status())
                || "RETURNED".equals(record.status()))
            .count();
        int completed = records.size() - processing;
        return new ApprovalSummaryDto(records.size(), completed, processing,
            "APPROVED".equals(instance.getStatus()) && processing == 0);
    }

    private static String approvalRecordStatus(MobileWorkflowMapper.ApprovalRow task) {
        if ("CC".equals(task.taskStatus())) {
            return task.readAt() == null ? "PROCESSING" : "APPROVED";
        }
        if ("PENDING".equals(task.taskStatus())) {
            return "REWORK".equals(task.taskType()) ? "RETURNED" : "PROCESSING";
        }
        return switch (task.taskStatus()) {
            case "APPROVED" -> "APPROVED";
            case "REJECTED" -> "REJECTED";
            case "RESUBMITTED" -> "RESUBMITTED";
            default -> null;
        };
    }

    private static String approvalRecordComment(MobileWorkflowMapper.ApprovalRow task) {
        if (!"CC".equals(task.taskStatus())) return task.comment();
        return task.readAt() == null ? "等待确认抄送内容。" : "已确认抄送内容。";
    }

    private List<MobileFileDto> files(Long formDataId) {
        return workflowMapper.selectFilesByFormDataId(formDataId).stream()
            .map(MobileWorkflowService::toFileDto)
            .toList();
    }

    private List<MobileFileDto> starterFiles(Long formDataId, JsonNode processSnapshot) {
        Map<String, String> modes = formDefinitionService.starterFieldModes(processSnapshot);
        Set<UUID> visibleFileIds = new LinkedHashSet<>();
        for (MobileWorkflowMapper.FormDataFileLink link
                : workflowMapper.selectFileLinks(formDataId)) {
            if (!"HIDDEN".equals(modes.get(link.fieldId()))) {
                visibleFileIds.add(link.fileId());
            }
        }
        return workflowMapper.selectFilesByFormDataId(formDataId).stream()
            .filter(file -> visibleFileIds.contains(file.getId()))
            .map(MobileWorkflowService::toFileDto)
            .toList();
    }

    private List<String> allowedActions(TaskEntity task, long userId,
                                        Set<String> permissions) {
        if ("CC".equals(task.getStatus()) && task.getReadAt() == null
            && Objects.equals(task.getAssigneeId(), userId)) {
            return List.of("ACKNOWLEDGE");
        }
        if (!PENDING_STATUS.equals(task.getStatus())
            || "REWORK".equals(task.getTaskType())
            || !Objects.equals(task.getAssigneeId(), userId)
            || "CC".equals(task.getStatus())) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (permissions.contains(PermissionCodes.WORKFLOW_TASK_APPROVE)) result.add("APPROVE");
        if ((task.getParallelId() == null || task.getNodeInstanceId() != null)
            && permissions.contains(PermissionCodes.WORKFLOW_TASK_REJECT)) result.add("REJECT");
        return result;
    }

    private List<RejectTargetDto> rejectTargets(JsonNode root, String currentNodeId) {
        List<RejectTargetDto> targets = new ArrayList<>();
        collectRejectTargets(root, currentNodeId, targets);
        JsonNode current = ProcessTreeNav.findById(root, currentNodeId);
        JsonNode configured = current == null
            ? objectMapper.missingNode() : current.path("props").path("rejectTargets");
        if (!configured.isArray() || configured.isEmpty()) {
            return targets.isEmpty() ? targets : List.of(targets.get(targets.size() - 1));
        }
        Set<String> allowed = new LinkedHashSet<>();
        configured.forEach(value -> allowed.add(value.asText()));
        return targets.stream().filter(target -> allowed.contains(target.nodeId())).toList();
    }

    private static boolean fixedRejectTarget(TaskEntity task) {
        String mode = task.getApprovalMode();
        return "AND".equals(mode) || "ALL".equals(mode) || "RATIO".equals(mode);
    }

    private boolean collectRejectTargets(JsonNode node, String currentNodeId,
                                         List<RejectTargetDto> targets) {
        if (node == null || node.isNull() || !node.has("id")) {
            return false;
        }
        String nodeId = node.path("id").asText();
        if (currentNodeId.equals(nodeId)) {
            return true;
        }
        if (APPROVAL_NODE.equals(node.path("type").asText())) {
            targets.add(new RejectTargetDto(nodeId, nodeName(node)));
        }
        if ("CONDITIONS".equals(node.path("type").asText())) {
            for (JsonNode branch : node.withArray("branchs")) {
                if (collectRejectTargets(branch, currentNodeId, targets)) {
                    return true;
                }
            }
        }
        return collectRejectTargets(node.get("children"), currentNodeId, targets);
    }

    private String nodeName(JsonNode root, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        if ("__rework__".equals(nodeId)) {
            return "待修改原单";
        }
        JsonNode node = ProcessTreeNav.findById(root, nodeId);
        return node == null ? nodeId : nodeName(node);
    }

    private static String nodeName(JsonNode node) {
        String name = node.path("props").path("name").asText(null);
        if (name == null || name.isBlank()) {
            name = node.path("props").path("title").asText(null);
        }
        if (name == null || name.isBlank()) {
            name = node.path("name").asText(null);
        }
        return name == null || name.isBlank() ? node.path("id").asText() : name;
    }

    private JsonNode readJsonObject(String value, String code) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(code, exception.getMessage());
        }
    }

    private JsonNode readJsonArray(String value, String code) {
        if (value == null || value.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(code, exception.getMessage());
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException("BAD_JSON", exception.getMessage());
        }
    }

    private static List<MobileFileRef> filesOf(StartMobileInstanceRequest request) {
        return request.files() == null ? List.of() : request.files();
    }

    private static List<MobileFileRef> starterEditableFiles(
        List<MobileFileRef> files, Map<String, String> modes) {
        return files.stream()
            .filter(file -> "EDITABLE".equals(modes.getOrDefault(file.fieldId(), "EDITABLE")))
            .toList();
    }

    private static boolean isAdmin(java.util.Collection<String> roles) {
        return roles != null && roles.contains(ADMIN_ROLE);
    }

    private static String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new BizException("BAD_ENGINE_RESULT", "engine result is missing numeric id");
    }

    private static List<Long> asLongList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .map(Number::longValue)
            .toList();
    }

    private static MobileFileDto toFileDto(MobileFile file) {
        return new MobileFileDto(
            file.getId(),
            file.getOriginalName(),
            file.getContentType(),
            file.getSizeBytes(),
            "/api/mobile/files/" + file.getId() + "/content"
        );
    }
}

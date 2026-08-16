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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MobileWorkflowService {
    private static final String READY_STATUS = "READY";
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
    private final MobileFileMapper fileMapper;
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorizationService;

    public MobileFormDto getMobileForm(String code) {
        FormDefinition form = formDefinitionService.getByCode(code);
        if (form == null || !PUBLISHED_STATUS.equals(form.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + code);
        }
        authorizationService.requireFormAction(form.getId(), PermissionCodes.FORM_RUNTIME_READ);
        ProcessDefinition process = processDefinitionService.latestPublishedForForm(form.getId());
        return new MobileFormDto(form.getCode(), form.getName(), form.getVersion(),
            readJsonArray(form.getSchema(), "BAD_SCHEMA_JSON"),
            readJsonObject(form.getSettings(), "BAD_SETTINGS_JSON"),
            readJsonObject(process == null ? null : process.getProcess(), "BAD_FLOW_JSON"));
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

        for (MobileFileRef file : filesOf(request)) {
            MobileFile row = requireReadyOwnedFile(file.fileId(), userId);
            workflowMapper.insertFileLink(formDataId, row.getId(), file.fieldId(),
                file.sortOrder());
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
        ProcessInstance instance = requireReadableInstance(instanceId, userId);
        FormData formData = requireFormData(instance.getFormDataId());
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        JsonNode snapshot = readJsonObject(instance.getProcessSnapshot(), "BAD_FLOW_JSON");
        User applicant = userMapper.selectById(instance.getStartedBy());
        Department department = applicant == null || applicant.getDeptId() == null
            ? null : departmentMapper.selectById(applicant.getDeptId());
        List<ApprovalRecordDto> records = approvalRecords(instance, snapshot);
        return new MobileInstanceDetailDto(
            instance.getId(),
            instance.getStatus(),
            form == null ? null : form.getName(),
            formData.getBusinessNo(),
            applicant == null ? null : applicant.getDisplayName(),
            applicant == null ? null : applicant.getEmployeeNo(),
            department == null ? null : department.getName(),
            instance.getStartedAt(),
            nodeName(snapshot, instance.getCurrentNodeId()),
            readJsonArray(form == null ? null : form.getSchema(), "BAD_SCHEMA_JSON"),
            readJsonObject(formData.getData(), "BAD_JSON"),
            snapshot,
            history(instance.getId()),
            canWithdraw(instance, userId),
            files(instance.getFormDataId()),
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
        TaskEntity task = requireExistingTask(taskId);
        ProcessInstance instance = requireExistingInstance(task.getProcInstId());
        if (!authorizationService.canReadInstance(instance.getId(), userId)) {
            throw new HiddenResourceException("task not found");
        }
        FormData formData = requireFormData(instance.getFormDataId());
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        JsonNode snapshot = readJsonObject(instance.getProcessSnapshot(), "BAD_FLOW_JSON");
        List<ApprovalRecordDto> records = approvalRecords(instance, snapshot);
        return new MobileTaskDetailDto(
            toTaskDto(task, instance, form, snapshot),
            readJsonArray(form == null ? null : form.getSchema(), "BAD_SCHEMA_JSON"),
            readJsonObject(formData.getData(), "BAD_JSON"),
            snapshot,
            history(instance.getId()),
            allowedActions(task, userId),
            List.of(),
            files(instance.getFormDataId()),
            approvalSummary(instance, records),
            records
        );
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
            null), userId);
    }

    public ReworkTaskDto getReworkTask(Long taskId, long userId) {
        TaskEntity task = requireOwnedReworkTask(taskId, userId);
        ProcessInstance instance = requireExistingInstance(task.getProcInstId());
        FormData formData = requireFormData(instance.getFormDataId());
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        if (form == null) {
            throw new BizException("NOT_FOUND", "form definition not found");
        }
        return new ReworkTaskDto(task.getId(), instance.getId(), form.getCode(), form.getName(),
            formData.getBusinessNo(),
            readJsonArray(form.getSchema(), "BAD_SCHEMA_JSON"),
            readJsonObject(formData.getData(), "BAD_JSON"),
            readJsonObject(instance.getProcessSnapshot(), "BAD_FLOW_JSON"),
            files(formData.getId()));
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

    private MobileFile requireReadyOwnedFile(java.util.UUID fileId, long userId) {
        MobileFile file = fileMapper.selectById(fileId);
        if (file == null || file.getDeletedAt() != null || !READY_STATUS.equals(file.getStatus())) {
            throw new BizException("FILE_NOT_FOUND", "file not found");
        }
        if (!Objects.equals(file.getOwnerId(), userId)) {
            throw new AccessDeniedException("file belongs to another user");
        }
        return file;
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
        if (validate) {
            FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
            if (form == null) {
                throw new BizException("NOT_FOUND", "form definition not found");
            }
            formDefinitionService.validateSubmission(form.getSchema(), data);
        }
        formData.setData(writeJson(data));
        formData.setStatus("NEEDS_REVISION");
        formDataMapper.updateById(formData);
        if (request.files() != null) {
            reconcileFileLinks(formData.getId(), request.files(), userId);
        }
        return formData;
    }

    private void reconcileFileLinks(Long formDataId, List<MobileFileRef> fileRefs, long userId) {
        List<MobileFileRef> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MobileFileRef ref : fileRefs) {
            if (ref == null || ref.fileId() == null || ref.fieldId() == null
                || ref.fieldId().isBlank() || ref.sortOrder() < 0) {
                throw new BizException("BAD_FILE_REF", "附件关联无效");
            }
            requireReadyOwnedFile(ref.fileId(), userId);
            String key = ref.fileId() + "\u0000" + ref.fieldId();
            if (seen.add(key)) {
                normalized.add(ref);
            }
        }
        workflowMapper.deleteFileLinks(formDataId);
        for (MobileFileRef ref : normalized) {
            workflowMapper.insertFileLink(formDataId, ref.fileId(), ref.fieldId(),
                ref.sortOrder());
        }
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
        if (!Objects.equals(instance.getStartedBy(), userId)
            || !RUNNING_STATUS.equals(instance.getStatus())) {
            return false;
        }
        return taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", instance.getId())
            .ne("status", PENDING_STATUS)
            .last("LIMIT 1")).isEmpty();
    }

    private MobileInstanceDto toInstanceDto(ProcessInstance instance) {
        FormData formData = formDataMapper.selectById(instance.getFormDataId());
        FormDefinition form = formData == null ? null : formDefinitionService.getById(
            formData.getFormDefId());
        JsonNode snapshot = readJsonObject(instance.getProcessSnapshot(), "BAD_FLOW_JSON");
        String currentNodeName = nodeName(snapshot, instance.getCurrentNodeId());
        return new MobileInstanceDto(instance.getId(), instance.getStatus(),
            form == null ? null : form.getName(),
            formData == null ? null : formData.getBusinessNo(), currentNodeName,
            instance.getStartedAt(), instance.getFinishedAt());
    }

    private MobileTaskDto toTaskDto(TaskEntity task) {
        ProcessInstance instance = requireExistingInstance(task.getProcInstId());
        FormData formData = requireFormData(instance.getFormDataId());
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        JsonNode snapshot = readJsonObject(instance.getProcessSnapshot(), "BAD_FLOW_JSON");
        return toTaskDto(task, instance, form, snapshot);
    }

    private MobileTaskDto toTaskDto(TaskEntity task, ProcessInstance instance,
                                    FormDefinition form, JsonNode snapshot) {
        User applicant = userMapper.selectById(instance.getStartedBy());
        Department department = applicant == null || applicant.getDeptId() == null
            ? null : departmentMapper.selectById(applicant.getDeptId());
        return new MobileTaskDto(
            task.getId(),
            task.getProcInstId(),
            task.getNodeId(),
            form == null ? null : form.getCode(),
            form == null ? null : form.getName(),
            form == null ? null : requireFormData(instance.getFormDataId()).getBusinessNo(),
            applicant == null ? null : applicant.getDisplayName(),
            applicant == null ? null : applicant.getEmployeeNo(),
            department == null ? null : department.getName(),
            "REWORK".equals(task.getTaskType()) ? "待修改原单" : nodeName(snapshot, task.getNodeId()),
            task.getTaskType() == null ? "APPROVAL" : task.getTaskType(),
            task.getStatus(),
            instance.getStatus(),
            task.getCreatedAt()
        );
    }

    private List<MobileHistoryDto> history(Long instanceId) {
        return historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
                .eq("proc_inst_id", instanceId)
                .orderByAsc("created_at")
                .orderByAsc("id"))
            .stream()
            .map(history -> new MobileHistoryDto(history.getId(), history.getFromNodeId(),
                history.getToNodeId(), history.getTaskId(), history.getAction(),
                history.getOperatorId(), history.getComment(), history.getCreatedAt()))
            .toList();
    }

    private List<ApprovalRecordDto> approvalRecords(ProcessInstance instance, JsonNode snapshot) {
        List<ApprovalRecordDto> records = new ArrayList<>();
        User applicant = userMapper.selectById(instance.getStartedBy());
        Department applicantDepartment = department(applicant);
        records.add(new ApprovalRecordDto(
            "submission", null, snapshot.path("id").asText("root"), "提交申请", "SUBMITTED",
            displayName(applicant), applicant == null ? null : applicant.getEmployeeNo(),
            applicantDepartment == null ? null : applicantDepartment.getName(), null,
            instance.getStartedAt(), instance.getStartedAt()));

        List<TaskEntity> tasks = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", instance.getId())
            .orderByAsc("created_at")
            .orderByAsc("id"));
        for (TaskEntity task : tasks) {
            String status = approvalRecordStatus(task);
            if (status == null) {
                continue;
            }
            Long operatorId = task.getApprovedBy() == null
                ? task.getAssigneeId() : task.getApprovedBy();
            User operator = userMapper.selectById(operatorId);
            Department operatorDepartment = department(operator);
            records.add(new ApprovalRecordDto(
                "task-" + task.getId(), task.getId(), task.getNodeId(),
                "REWORK".equals(task.getTaskType()) ? "退回修改"
                    : nodeName(snapshot, task.getNodeId()),
                status, displayName(operator), operator == null ? null : operator.getEmployeeNo(),
                operatorDepartment == null ? null : operatorDepartment.getName(),
                task.getComment(), task.getCreatedAt(), task.getApprovedAt()));
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

    private static String approvalRecordStatus(TaskEntity task) {
        if ("PENDING".equals(task.getStatus())) {
            return "REWORK".equals(task.getTaskType()) ? "RETURNED" : "PROCESSING";
        }
        return switch (task.getStatus()) {
            case "APPROVED" -> "APPROVED";
            case "REJECTED" -> "REJECTED";
            case "RESUBMITTED" -> "RESUBMITTED";
            default -> null;
        };
    }

    private Department department(User user) {
        return user == null || user.getDeptId() == null
            ? null : departmentMapper.selectById(user.getDeptId());
    }

    private static String displayName(User user) {
        if (user == null) {
            return "未记录";
        }
        return user.getDisplayName() == null || user.getDisplayName().isBlank()
            ? user.getUsername() : user.getDisplayName();
    }

    private List<MobileFileDto> files(Long formDataId) {
        return workflowMapper.selectFilesByFormDataId(formDataId).stream()
            .map(MobileWorkflowService::toFileDto)
            .toList();
    }

    private List<String> allowedActions(TaskEntity task, long userId) {
        if (!PENDING_STATUS.equals(task.getStatus())
            || "REWORK".equals(task.getTaskType())
            || !Objects.equals(task.getAssigneeId(), userId)
            || "CC".equals(task.getStatus())) {
            return List.of();
        }
        return List.of("APPROVE", "REJECT");
    }

    private List<RejectTargetDto> rejectTargets(JsonNode root, String currentNodeId) {
        List<RejectTargetDto> targets = new ArrayList<>();
        collectRejectTargets(root, currentNodeId, targets);
        return targets;
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

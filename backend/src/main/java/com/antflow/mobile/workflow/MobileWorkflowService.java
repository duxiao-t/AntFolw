package com.antflow.mobile.workflow;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    public MobileFormDto getMobileForm(String code) {
        FormDefinition form = formDefinitionService.getByCode(code);
        if (form == null || !PUBLISHED_STATUS.equals(form.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + code);
        }
        ProcessDefinition process = processDefinitionService.latestPublishedForForm(form.getId());
        return new MobileFormDto(form.getCode(), form.getName(), form.getVersion(),
            readJsonArray(form.getSchema(), "BAD_SCHEMA_JSON"),
            readJsonObject(process == null ? null : process.getProcess(), "BAD_FLOW_JSON"));
    }

    @Transactional(rollbackFor = Exception.class)
    public MobileStartResult start(StartMobileInstanceRequest request, long userId) {
        JsonNode data = request.data() == null ? objectMapper.createObjectNode() : request.data();
        if (request.draftId() != null) {
            MobileDraftDto draft = draftService.get(request.draftId(), userId);
            if (!Objects.equals(draft.formCode(), request.formCode())) {
                throw new BizException("BAD_DRAFT", "draft does not belong to requested form");
            }
            if (draft.readOnly()) {
                throw new BizException("BAD_DRAFT", "draft is read only");
            }
        }

        Map<String, List<Long>> selfSelected = request.selfSelected() == null
            ? Map.of() : request.selfSelected();
        Map<String, Object> result = engine.start(new StartCmd(request.formCode(), data, selfSelected),
            userId);
        Long formDataId = asLong(result.get("formDataId"));
        Long instanceId = asLong(result.get("instanceId"));
        List<Long> firstTaskIds = asLongList(result.get("firstTaskIds"));

        for (MobileFileRef file : filesOf(request)) {
            MobileFile row = requireReadyOwnedFile(file.fileId(), userId);
            workflowMapper.insertFileLink(formDataId, row.getId(), file.fieldId(),
                file.sortOrder());
        }
        if (request.draftId() != null) {
            draftService.deleteAfterSubmit(request.draftId(), userId);
        }
        return new MobileStartResult(instanceId, formDataId, firstTaskIds);
    }

    public List<MobileInstanceDto> listInstances(long userId) {
        return instanceMapper.selectList(new QueryWrapper<ProcessInstance>()
                .eq("started_by", userId)
                .orderByDesc("started_at")
                .orderByDesc("id"))
            .stream()
            .map(this::toInstanceDto)
            .toList();
    }

    public MobileInstanceDetailDto getInstanceDetail(Long instanceId, long userId,
                                                     List<String> roles) {
        ProcessInstance instance = requireReadableInstance(instanceId, userId, roles);
        FormData formData = requireFormData(instance.getFormDataId());
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        JsonNode snapshot = readJsonObject(instance.getProcessSnapshot(), "BAD_FLOW_JSON");
        return new MobileInstanceDetailDto(
            instance.getId(),
            instance.getStatus(),
            form == null ? null : form.getName(),
            readJsonArray(form == null ? null : form.getSchema(), "BAD_SCHEMA_JSON"),
            readJsonObject(formData.getData(), "BAD_JSON"),
            snapshot,
            history(instance.getId()),
            canWithdraw(instance, userId),
            files(instance.getFormDataId())
        );
    }

    public List<MobileTaskDto> listTasks(String view, long userId) {
        QueryWrapper<TaskEntity> query = new QueryWrapper<TaskEntity>()
            .eq("assignee_id", userId);
        if ("done".equalsIgnoreCase(view)) {
            query.ne("status", PENDING_STATUS);
        } else {
            query.eq("status", PENDING_STATUS);
        }
        query.orderByDesc("created_at").orderByDesc("id");
        return taskMapper.selectList(query).stream().map(this::toTaskDto).toList();
    }

    public MobileTaskDetailDto getTaskDetail(Long taskId, long userId, List<String> roles) {
        TaskEntity task = requireExistingTask(taskId);
        ProcessInstance instance = requireExistingInstance(task.getProcInstId());
        if (!isAdmin(roles)
            && !Objects.equals(task.getAssigneeId(), userId)
            && !Objects.equals(instance.getStartedBy(), userId)) {
            throw new AccessDeniedException("task is not readable");
        }
        FormData formData = requireFormData(instance.getFormDataId());
        FormDefinition form = formDefinitionService.getById(formData.getFormDefId());
        JsonNode snapshot = readJsonObject(instance.getProcessSnapshot(), "BAD_FLOW_JSON");
        return new MobileTaskDetailDto(
            toTaskDto(task, instance, form, snapshot),
            readJsonArray(form == null ? null : form.getSchema(), "BAD_SCHEMA_JSON"),
            readJsonObject(formData.getData(), "BAD_JSON"),
            snapshot,
            history(instance.getId()),
            allowedActions(task, userId),
            rejectTargets(snapshot, task.getNodeId()),
            files(instance.getFormDataId())
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long taskId, MobileTaskActionRequest request, long userId) {
        engine.approve(new CompleteCmd(taskId, "APPROVE",
            request == null ? null : request.comment(), null), userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long taskId, MobileTaskActionRequest request, long userId) {
        engine.reject(new CompleteCmd(taskId, "REJECT",
            request == null ? null : request.comment(),
            request == null ? null : request.rejectToNodeId()), userId);
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

    private ProcessInstance requireReadableInstance(Long instanceId, long userId,
                                                    List<String> roles) {
        ProcessInstance instance = requireExistingInstance(instanceId);
        if (isAdmin(roles) || Objects.equals(instance.getStartedBy(), userId)
            || isParticipant(instanceId, userId)) {
            return instance;
        }
        throw new AccessDeniedException("instance is not readable");
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
            form == null ? null : form.getName(), currentNodeName, instance.getStartedAt(),
            instance.getFinishedAt());
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
            form == null ? null : form.getName(),
            applicant == null ? null : applicant.getDisplayName(),
            department == null ? null : department.getName(),
            nodeName(snapshot, task.getNodeId()),
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

    private List<MobileFileDto> files(Long formDataId) {
        return workflowMapper.selectFilesByFormDataId(formDataId).stream()
            .map(MobileWorkflowService::toFileDto)
            .toList();
    }

    private List<String> allowedActions(TaskEntity task, long userId) {
        if (!PENDING_STATUS.equals(task.getStatus())
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

    private static List<MobileFileRef> filesOf(StartMobileInstanceRequest request) {
        return request.files() == null ? List.of() : request.files();
    }

    private static boolean isAdmin(List<String> roles) {
        return roles != null && roles.contains(ADMIN_ROLE);
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

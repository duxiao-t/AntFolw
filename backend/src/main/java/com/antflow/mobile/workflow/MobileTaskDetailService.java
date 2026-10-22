package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.form.FormDefinitionService;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 移动端详情页的投影：实例详情、任务详情、无流程表单的直接提交详情。
 *
 * <p>这一簇的"变化原因"是**可见性规则**，不是查询本身。三处容易踩的坑都集中在这里：
 * <ul>
 *   <li>实例可见性分 FULL / SUMMARY / NONE：SUMMARY 只给"谁、什么时候、到哪一步"，
 *       表单内容、附件、可撤回标记一律为 null/false——少写一个 null 就是把内容泄给只读角色；</li>
 *   <li>发起人视角（{@code startedBy == me} 且非 admin）要再过一层
 *       {@code projectStarterSchema/Data} 与 {@code starterFiles}：隐藏字段的附件与取值都不外露；</li>
 *   <li>驳回目标只在非并行（或已落到节点实例）且审批模式不是 AND/ALL/RATIO 时才计算；
 *       并行分支上的任务一旦选错目标节点，会退回另一条分支。</li>
 * </ul>
 *
 * <p>事务：详情全是只读，不加 {@code @Transactional}；调用方也不需要写事务。
 */
final class MobileTaskDetailService {
    private static final String PENDING_STATUS = "PENDING";
    private static final String APPROVAL_NODE = "APPROVAL";
    private static final String ADMIN_ROLE = "admin";

    private final MobileWorkflowMapper workflowMapper;
    private final AuthorizationService authorizationService;
    private final FormDefinitionService formDefinitionService;
    private final ProcessDefinitionService processDefinitionService;
    private final ProcessEngine engine;
    private final MobileApprovalRecordService approvals;
    private final MobileAttachmentProjector attachments;
    private final MobileProjectionSupport projection;
    private final ObjectMapper objectMapper;

    MobileTaskDetailService(MobileWorkflowMapper workflowMapper,
                            AuthorizationService authorizationService,
                            FormDefinitionService formDefinitionService,
                            ProcessDefinitionService processDefinitionService,
                            ProcessEngine engine,
                            MobileApprovalRecordService approvals,
                            MobileAttachmentProjector attachments,
                            MobileProjectionSupport projection,
                            ObjectMapper objectMapper) {
        this.workflowMapper = workflowMapper;
        this.authorizationService = authorizationService;
        this.formDefinitionService = formDefinitionService;
        this.processDefinitionService = processDefinitionService;
        this.engine = engine;
        this.approvals = approvals;
        this.attachments = attachments;
        this.projection = projection;
        this.objectMapper = objectMapper;
    }

    MobileDirectSubmissionDetailDto getDirectSubmission(long dataId, long userId) {
        MobileWorkflowMapper.DirectSubmissionRow row = workflowMapper.selectDirectSubmission(dataId, userId);
        if (row == null) throw new HiddenResourceException("submission not found");
        return new MobileDirectSubmissionDetailDto(row.dataId(), row.status(), row.formCode(),
            row.formName(), row.businessNo(), row.createdAt(),
            projection.readJsonArray(row.formSchema(), "BAD_SCHEMA_JSON"),
            projection.readJsonObject(row.formDataJson(), "BAD_JSON"), attachments.files(row.dataId()));
    }

    MobileInstanceDetailDto getInstanceDetail(Long instanceId, long userId,
                                              java.util.Collection<String> roles) {
        AuthorizationService.InstanceVisibility visibility =
            authorizationService.instanceVisibility(instanceId, userId);
        if (visibility == AuthorizationService.InstanceVisibility.NONE) {
            throw new HiddenResourceException("instance not found");
        }
        MobileWorkflowMapper.InstanceDetailRow row = workflowMapper.selectInstanceDetail(instanceId);
        if (row == null) throw new BizException("NOT_FOUND", "instance not found");
        ProcessInstance instance = toProcessInstance(row);
        JsonNode snapshot = projection.readJsonObject(row.processSnapshot(), "BAD_FLOW_JSON");
        List<ApprovalRecordDto> records = approvals.approvalRecords(row.instanceId(), snapshot,
            row.applicantName(), row.applicantEmployeeNo(), row.applicantDepartment(),
            row.startedAt());
        // SUMMARY：只给"谁/何时/到哪一步"。表单内容、附件、可撤回标记全部留空，
        // 多带一个字段就等于把只读角色看不到的内容顺带发出去了。
        if (visibility == AuthorizationService.InstanceVisibility.SUMMARY) {
            return new MobileInstanceDetailDto(
                visibility.name(), row.instanceId(), row.instanceStatus(), null, null,
                row.applicantName(), row.applicantEmployeeNo(), row.applicantDepartment(),
                row.startedAt(), MobileProjectionSupport.nodeName(snapshot, row.currentNodeId()), null, null, null,
                approvals.history(row.instanceId()), false, List.of(),
                approvals.approvalSummary(instance, records), records);
        }
        JsonNode formSchema = projection.readJsonArray(row.formSchema(), "BAD_SCHEMA_JSON");
        JsonNode formData = projection.readJsonObject(row.formDataJson(), "BAD_JSON");
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
            MobileProjectionSupport.nodeName(snapshot, row.currentNodeId()),
            formSchema,
            formData,
            snapshot,
            approvals.history(row.instanceId()),
            canWithdraw(instance, userId),
            starterView ? attachments.starterFiles(row.formDataId(), snapshot) : attachments.files(row.formDataId()),
            approvals.approvalSummary(instance, records),
            records
        );
    }

    MobileTaskDetailDto getTaskDetail(Long taskId, long userId,
                                      java.util.Collection<String> roles) {
        MobileWorkflowMapper.TaskDetailRow row = workflowMapper.selectTaskDetail(taskId);
        if (row == null) throw new BizException("NOT_FOUND", "task not found");
        if (!authorizationService.canReadFullInstance(row.instanceId(), userId)) {
            throw new HiddenResourceException("task not found");
        }
        TaskEntity task = toTaskEntity(row);
        ProcessInstance instance = toProcessInstance(row);
        JsonNode snapshot = projection.readJsonObject(row.processSnapshot(), "BAD_FLOW_JSON");
        List<ApprovalRecordDto> records = approvals.approvalRecords(row.instanceId(), snapshot,
            row.applicantName(), row.applicantEmployeeNo(), row.applicantDepartment(),
            row.startedAt());
        Set<String> permissions = authorizationService.snapshot(userId).permissions();
        JsonNode formSchema = projection.readJsonArray(row.formSchema(), "BAD_SCHEMA_JSON");
        JsonNode formData = projection.readJsonObject(row.formDataJson(), "BAD_JSON");
        if (Objects.equals(row.startedBy(), userId) && !roles.contains(ADMIN_ROLE)) {
            formData = formDefinitionService.projectStarterData(formData, formSchema, snapshot);
            formSchema = formDefinitionService.projectStarterSchema(formSchema, snapshot);
        }
        return new MobileTaskDetailDto(
            toTaskDto(row, snapshot),
            formSchema,
            formData,
            snapshot,
            approvals.history(row.instanceId()),
            allowedActions(task, userId, permissions),
            task.getParallelId() != null
                && task.getNodeInstanceId() == null
                && PENDING_STATUS.equals(task.getStatus())
                && Objects.equals(task.getAssigneeId(), userId)
                && permissions.contains(PermissionCodes.WORKFLOW_TASK_REJECT),
            task.getParallelId() == null && !fixedRejectTarget(task)
                ? rejectTargets(snapshot, task.getNodeId()) : List.of(),
            Objects.equals(row.startedBy(), userId) && !roles.contains(ADMIN_ROLE)
                ? attachments.starterFiles(row.formDataId(), snapshot) : attachments.files(row.formDataId()),
            approvals.approvalSummary(instance, records),
            records,
            processDefinitionService.commentPresets(snapshot, task.getNodeId())
        );
    }

    private MobileTaskDto toTaskDto(MobileWorkflowMapper.TaskDetailRow row, JsonNode snapshot) {
        return new MobileTaskDto(row.taskId(), row.instanceId(), row.nodeId(), row.formCode(),
            row.formName(), row.businessNo(), row.applicantName(), row.applicantEmployeeNo(),
            row.applicantDepartment(), "REWORK".equals(row.taskType()) ? "待修改原单"
                : MobileProjectionSupport.nodeName(snapshot, row.nodeId()), row.taskType(), row.taskStatus(),
            row.instanceStatus(), row.taskCreatedAt(), row.readAt());
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

    private boolean canWithdraw(ProcessInstance instance, long userId) {
        return engine.canWithdraw(instance.getId(), userId);
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
            targets.add(new RejectTargetDto(nodeId, MobileProjectionSupport.nodeName(node)));
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
}

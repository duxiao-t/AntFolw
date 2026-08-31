package com.antflow.engine;

import com.antflow.automation.WorkflowJob;
import com.antflow.automation.WorkflowJobMapper;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.common.FormalNumberService;
import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
import com.antflow.engine.handler.NodeContext;
import com.antflow.engine.handler.NodeHandler;
import com.antflow.engine.handler.NodeOutcome;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.notify.NotificationEvent;
import com.antflow.notify.NotificationPublisher;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 钉钉式流程树的运行时引擎（Task 7）。
 *
 * <p>流程由 {@link ProcessDefinition#getProcess()}（JSONB 字符串）持有：
 * 每个节点 {@code {id, type, props, children, branchs?}}；业务节点用单个
 * {@code children} 指向唯一后继；{@code CONDITIONS} 有
 * {@code branchs[]}+{@code children}（合流后续）。
 *
 * <p>引擎入口：
 * <ul>
 *   <li>{@link #start(StartCmd, long)} — 建 FormData(SUBMITTED) + ProcessInstance(RUNNING)，
 *       从 ROOT 出发首次 {@code resolveAndLand}。</li>
 *   <li>{@link #approve(CompleteCmd, long)} — 标记 PENDING 任务 APPROVED，按节点 mode
 *       （OR→跳兄弟+推进；AND→等全员再推进）处理后继续 {@code resolveAndLand}。</li>
 *   <li>{@link #reject(CompleteCmd, long)} — 标记当前任务 REJECTED，并退回直接上一级。</li>
 *   <li>{@link #withdraw(long, long)} — 发起人在当前审批轮次被人工处理前撤回修改。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ProcessEngine {

    private final FormDefinitionService formDefinitionService;
    private final FormDataMapper formDataMapper;
    private final ProcessDefinitionService processDefinitionService;
    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final TaskMapperExt taskMapperExt;
    private final TaskHistoryMapper historyMapper;
    private final List<NodeHandler> nodeHandlers;
    private final NotificationPublisher notifier;
    private final ObjectMapper json;
    private final FormalNumberService formalNumberService;
    private final WorkflowJobMapper workflowJobMapper;
    private final AuthorizationService authorizationService;

    /** Optional only so the legacy unit tests that construct this class directly keep working. */
    @Autowired(required = false)
    private WorkflowRuntimeV2 runtimeV2;

    @Transactional
    public Map<String, Object> start(StartCmd cmd, long userId) {
        FormDefinition fd = formDefinitionService.getByCode(cmd.formCode());
        if (fd == null || !"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + cmd.formCode());
        }
        // 表单使用授权：未获得 t_form_resource_grant 授权的用户不能发起该表单的流程。
        authorizationService.requireFormAction(fd.getId(), PermissionCodes.FORM_RUNTIME_READ);
        ProcessDefinition pd = processDefinitionService.latestPublishedForForm(fd.getId());
        if (pd == null) {
            throw new BizException("NO_FLOW", "No published process for form " + cmd.formCode());
        }
        formDefinitionService.validateSubmission(fd.getSchema(), cmd.data());
        var visibleData = formDefinitionService.filterVisibleSubmission(fd.getSchema(), cmd.data());

        FormData fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        fd2.setBusinessNo(formalNumberService.businessNo());
        fd2.setData(writeJson(visibleData));
        fd2.setStatus("SUBMITTED");
        fd2.setCreatedBy(userId);
        formDataMapper.insert(fd2);

        ProcessInstance pi = new ProcessInstance();
        pi.setProcDefId(pd.getId());
        pi.setProcessDefVersion(pd.getVersion());
        String normalizedProcess = processDefinitionService.normalizeConditionValues(
            pd.getProcess(), fd.getSchema());
        pi.setProcessSnapshot(normalizedProcess == null ? pd.getProcess() : normalizedProcess);
        pi.setFormDataId(fd2.getId());
        pi.setStatus("RUNNING");
        pi.setStartedBy(userId);
        PrincipalHolder.current()
            .filter(principal -> principal.userId() == userId)
            .ifPresent(principal -> pi.setStartedDeptId(principal.departmentId()));
        pi.setStartedAt(OffsetDateTime.now());
        if (runtimeV2 != null) {
            WorkflowRuntimeV2.StartState state = runtimeV2.prepareStart(fd2, pd, userId);
            pi.setEngineVersion(2);
            pi.setRoundNo(1);
            pi.setProcessDefinitionVersionId(state.processVersionId());
            pi.setCurrentFormRevisionId(state.revisionId());
        }
        processInstanceMapper.insert(pi);

        // 引擎后续一律走快照树，而非 pd.getProcess()
        JsonNode root = readTree(pi.getProcessSnapshot());
        JsonNode formData = readTreeOrEmpty(fd2.getData());
        Map<String, List<Long>> selfSelected =
            cmd.selfSelected() == null ? Map.of() : cmd.selfSelected();

        List<Long> firstTasks = resolveAndLand(root, pi, formData, userId, selfSelected, root);
        if (runtimeV2 != null && runtimeV2.active(pi)) {
            runtimeV2.outbox(pi.getId(), userId, "INSTANCE_STARTED", null);
        } else {
            notifier.publish(new NotificationEvent(this, "INSTANCE_STARTED",
                pi.getId(), null, userId, "流程发起 #" + pi.getId()));
        }
        return Map.of(
            "instanceId", pi.getId(),
            "formDataId", fd2.getId(),
            "businessNo", fd2.getBusinessNo(),
            "firstTaskIds", firstTasks
        );
    }

    @Transactional
    public void approve(CompleteCmd cmd, long operatorId) {
        approveInternal(cmd, operatorId, false);
    }

    @Transactional
    public void forceApprove(CompleteCmd cmd, long operatorId) {
        approveInternal(cmd, operatorId, true);
    }

    @Transactional
    public void recallApproval(long taskId, long operatorId) {
        TaskEntity initial = taskMapper.selectById(taskId);
        if (initial == null) throw new BizException("NOT_FOUND", "task not found");
        ProcessInstance instance = processInstanceMapper.selectForUpdate(initial.getProcInstId());
        TaskEntity task = taskMapper.selectForUpdate(taskId);
        if (instance == null || task == null || !"RUNNING".equals(instance.getStatus())
            || !"APPROVED".equals(task.getStatus())
            || !Objects.equals(task.getApprovedBy(), operatorId)) {
            throw new BizException("BAD_RECALL_STATE", "仅能追回自己已同意且仍在流转的任务");
        }
        if (runtimeV2 == null) throw new BizException("BAD_RECALL_STATE", "旧流程不支持同意追回");
        runtimeV2.recallApproval(task, instance, operatorId);
    }

    @Transactional
    public void adminReassign(long taskId, long targetUserId, long operatorId, String reason) {
        LockedTask locked = lockPendingTask(taskId);
        if (runtimeV2 == null) throw new BizException("BAD_TASK_STATE", "reassign requires V2 task");
        runtimeV2.adminReassign(locked.task(), targetUserId, operatorId, reason);
    }

    @Transactional
    public void adminTerminate(long instanceId, long operatorId, String reason) {
        ProcessInstance instance = processInstanceMapper.selectForUpdate(instanceId);
        if (instance == null) throw new BizException("NOT_FOUND", "instance not found");
        if (!"RUNNING".equals(instance.getStatus())) {
            throw new BizException("BAD_INSTANCE_STATE", "only running instance can be terminated");
        }
        if (runtimeV2 != null) runtimeV2.terminate(instance, operatorId, reason);
        instance.setStatus("REJECTED");
        instance.setCurrentNodeId(null);
        instance.setCurrentNodeInstanceId(null);
        instance.setFinishedAt(OffsetDateTime.now());
        processInstanceMapper.updateById(instance);
    }

    private void approveInternal(CompleteCmd cmd, long operatorId, boolean force) {
        LockedTask locked = lockPendingTask(cmd.taskId());
        TaskEntity t = locked.task();
        ProcessInstance pi = locked.instance();
        skipDelegatedParentIfNeeded(t, operatorId);
        if ("REWORK".equals(t.getTaskType())) {
            throw new BizException("BAD_TASK_TYPE", "Rework task must be resubmitted from the form");
        }
        if (!force && !Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }

        // 永远走快照，不依赖 pd.getProcess()（避免流程改版后已发起的实例跑飞）
        JsonNode root = readProcessTree(pi);
        JsonNode cur = ProcessTreeNav.findById(root, t.getNodeId());
        if (cur == null) {
            throw new BizException("BAD_FLOW", "approval node not in tree: " + t.getNodeId());
        }
        if (cmd.data() != null) {
            applyNodeFieldEdits(pi, cur, cmd.data(), operatorId);
        }

        t.setStatus("APPROVED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        if (runtimeV2 != null && runtimeV2.active(pi)) {
            t.setActionFormRevisionId(pi.getCurrentFormRevisionId());
        }
        updateTaskOrConflict(t);
        insertHistory(t, null, t.getNodeId(), force ? "FORCE_APPROVE" : "APPROVE",
            operatorId, cmd.comment());

        if (runtimeV2 != null && runtimeV2.active(pi) && t.getNodeInstanceId() != null) {
            if ("ADD_BEFORE".equals(t.getOperationKind())) {
                runtimeV2.completeBeforeSign(t);
                return;
            }
            if (runtimeV2.activateAfterSign(t)) return;
            WorkflowRuntimeV2.Decision decision = runtimeV2.approve(t, cur, operatorId);
            if (!decision.newTaskIds().isEmpty()) {
                notifyAssigned(pi.getId(), decision.newTaskIds());
            }
            if (!decision.advance()) return;

            JsonNode formData = readFormData(pi.getFormDataId());
            if (t.getParallelId() != null) {
                advanceParallelBranch(root, pi, formData, t.getParallelId(), t.getBranchId(), cur);
            } else {
                resolveAndLand(root, pi, formData, pi.getStartedBy(), Map.of(), cur);
            }
            return;
        }

        String mode = cur.path("props").path("mode").asText("OR");
        boolean andMode = "AND".equals(mode);

        if (andMode) {
            // Wait until all PENDING siblings have acted.
            List<TaskEntity> stillPending = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", pi.getId())
                .eq("status", "PENDING")
                .eq("node_id", t.getNodeId())
                .ne("id", t.getId()));
            if (!stillPending.isEmpty()) {
                return;   // 尚未完成本节点全员
            }
            // All done — first-come OR any-of-several, choose "auto approve" semantics.
        } else {
            // OR-sign: skip sibling PENDING tasks on same node.
            List<TaskEntity> siblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", pi.getId())
                .eq("status", "PENDING")
                .eq("node_id", t.getNodeId())
                .ne("id", t.getId()));
            for (TaskEntity sib : siblings) {
                sib.setStatus("SKIPPED");
                taskMapper.updateById(sib);
                insertHistoryOnInstance(pi.getId(), t.getNodeId(), sib.getNodeId(),
                    "SKIP", operatorId, "OR-sign short-circuit");
            }
        }

        JsonNode formData = readFormData(pi.getFormDataId());
        if (t.getParallelId() != null) {
            advanceParallelBranch(root, pi, formData, t.getParallelId(), t.getBranchId(), cur);
            return;
        }
        // 仅首轮 start 时传入过 selfSelected；后续（理论上不会出现）传空 map。
        resolveAndLand(root, pi, formData, pi.getStartedBy(), Map.of(), cur);
    }

    private void advanceParallelBranch(JsonNode root, ProcessInstance instance,
                                       JsonNode formData, String parallelId,
                                       String branchId, JsonNode completedNode) {
        JsonNode node = ProcessTreeNav.next(root, completedNode, parallelId);
        NodeContext context = new NodeContext(
            instance.getStartedBy(), formData, Map.of(), completedNode.path("id").asText(null),
            parallelId, branchId
        );
        while (node != null) {
            String type = node.path("type").asText();
            NodeHandler handler = pickHandler(type);
            if (handler == null) {
                throw new BizException("BAD_NODE_TYPE", "未识别节点类型: " + type);
            }
            JsonNode handled = node;
            long nodeInstanceId = runtimeV2 == null ? 0
                : runtimeV2.enterNode(instance, handled, context);
            NodeOutcome outcome = handler.handle(root, handled, instance,
                nodeInstanceId == 0 ? context : context.atNode(nodeInstanceId));
            if (outcome.type() == NodeOutcome.Type.HALT) {
                if (outcome instanceof NodeOutcome.Halt halt) {
                    notifyAssigned(instance.getId(), halt.newTaskIds());
                }
                return;
            }
            if (runtimeV2 != null) runtimeV2.completeNode(nodeInstanceId, "PASSED");
            if (outcome.type() == NodeOutcome.Type.END) return;
            node = outcome.node();
            context = new NodeContext(
                instance.getStartedBy(), formData, Map.of(), handled.path("id").asText(null),
                parallelId, branchId
            );
        }

        if (runtimeV2 != null && runtimeV2.active(instance)) {
            runtimeV2.parallelBranchPassed(instance, root, parallelId, branchId);
        }

        Long stillPending = taskMapper.selectCount(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", instance.getId())
            .eq("parallel_id", parallelId)
            .eq("status", "PENDING"));
        if ((stillPending != null && stillPending > 0)
            || hasActiveParallelAutomation(root, instance.getId(), parallelId)) {
            processInstanceMapper.updateById(instance);
            return;
        }
        JsonNode parallelNode = ProcessTreeNav.findById(root, parallelId);
        if (parallelNode == null) {
            throw new BizException("BAD_FLOW", "parallel node not found: " + parallelId);
        }
        ProcessTreeNav.ParallelParent parent = ProcessTreeNav.findParallelParent(root, parallelId);
        if (parent != null) {
            advanceParallelBranch(root, instance, formData,
                parent.parallelId(), parent.branchId(), parallelNode);
            return;
        }
        processInstanceMapper.updateById(instance);
        resolveAndLand(root, instance, formData, instance.getStartedBy(), Map.of(), parallelNode);
    }

    @Transactional
    public void reject(CompleteCmd cmd, long operatorId) {
        rejectInternal(cmd, operatorId, false);
    }

    @Transactional
    public void forceReject(CompleteCmd cmd, long operatorId) {
        rejectInternal(cmd, operatorId, true);
    }

    private void rejectInternal(CompleteCmd cmd, long operatorId, boolean force) {
        LockedTask locked = lockPendingTask(cmd.taskId());
        TaskEntity t = locked.task();
        ProcessInstance pi = locked.instance();
        if ("REWORK".equals(t.getTaskType())) {
            throw new BizException("BAD_TASK_TYPE", "Rework task cannot be rejected as approval");
        }
        if (!force && t.getParallelId() != null
            && (runtimeV2 == null || !runtimeV2.active(pi))) {
            throw new BizException("PARALLEL_REJECT_DISABLED", "并行审批任务不允许驳回");
        }
        if (!force && !Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }

        JsonNode root = readProcessTree(pi);
        JsonNode cur = ProcessTreeNav.findById(root, t.getNodeId());
        if (cur == null) {
            throw new BizException("BAD_FLOW", "current node not in tree: " + t.getNodeId());
        }

        t.setStatus("REJECTED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        if (runtimeV2 != null && runtimeV2.active(pi)) {
            t.setActionFormRevisionId(pi.getCurrentFormRevisionId());
        }
        updateTaskOrConflict(t);
        insertHistory(t, null, t.getNodeId(), force ? "FORCE_REJECT_VOTE" : "REJECT_VOTE",
            operatorId, cmd.comment());

        if (runtimeV2 != null && runtimeV2.active(pi) && t.getNodeInstanceId() != null) {
            WorkflowRuntimeV2.Decision decision = t.getOperationKind() != null
                && t.getOperationKind().startsWith("ADD_")
                ? runtimeV2.rejectAdditional(t, operatorId)
                : runtimeV2.rejectVote(t, cur, operatorId);
            if (!decision.reject()) return;
            if (t.getParallelId() != null) {
                WorkflowRuntimeV2.ParallelDecision parallel =
                    runtimeV2.parallelBranchRejected(t, pi, root);
                if (parallel == WorkflowRuntimeV2.ParallelDecision.WAIT) return;
                if (parallel == WorkflowRuntimeV2.ParallelDecision.ADVANCE) {
                    advanceParallelBranch(root, pi, readFormData(pi.getFormDataId()),
                        t.getParallelId(), t.getBranchId(), cur);
                    return;
                }
            }
            rejectV2(cmd, operatorId, force, t, pi, root, cur);
            return;
        }

        // 同节点兄弟一律 SKIPPED；并行分支任务则把整个并行网关的 PENDING 任务都跳过
        List<TaskEntity> siblings;
        if (t.getParallelId() != null) {
            siblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", pi.getId()).eq("status", "PENDING")
                .eq("parallel_id", t.getParallelId()).ne("id", t.getId()));
        } else {
            siblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", pi.getId()).eq("status", "PENDING")
                .eq("node_id", t.getNodeId()).ne("id", t.getId()));
        }
        for (TaskEntity sib : siblings) {
            sib.setStatus("SKIPPED");
            taskMapper.updateById(sib);
            insertHistoryOnInstance(pi.getId(), t.getNodeId(), sib.getNodeId(),
                "SKIP", operatorId, null);
        }

        if (force && cmd.rejectToNodeId() != null && !cmd.rejectToNodeId().isBlank()) {
            JsonNode target = ProcessTreeNav.findById(root, cmd.rejectToNodeId());
            if (target == null || !"APPROVAL".equals(target.path("type").asText())
                || ProcessTreeNav.isInsideParallelBranch(root, cmd.rejectToNodeId())) {
                throw new BizException("BAD_REJECT_TARGET", "reject target is not an approval node");
            }
            pi.setStatus("RUNNING");
            pi.setFinishedAt(null);
            pi.setCurrentNodeId(target.path("id").asText());
            processInstanceMapper.updateById(pi);
            insertHistory(t, t.getNodeId(), target.path("id").asText(),
                force ? "FORCE_REJECT" : "REJECT_TO_NODE", operatorId, cmd.comment());
            resolveAndLandFromNode(root, pi, readFormData(pi.getFormDataId()),
                pi.getStartedBy(), Map.of(), target);
            return;
        }
        TaskEntity previous = previousApproval(t);
        List<TaskEntity> returnedTasks = previous == null
            ? List.of(newReturnedTask(pi.getId(), "__rework__", "REWORK", "OR_SIGN",
                pi.getStartedBy(), null, null))
            : previousNodeAssignees(previous).stream()
                .map(assigneeId -> newReturnedTask(pi.getId(), previous.getNodeId(), "APPROVAL",
                    previous.getApprovalMode(), assigneeId, null, null))
                .toList();
        String returnedNodeId = previous == null ? "__rework__" : previous.getNodeId();
        if (returnedTasks.isEmpty()) {
            throw new BizException("BAD_FLOW", "previous approval has no assignee to return to");
        }





        for (TaskEntity returned : returnedTasks) {
            taskMapper.insert(returned);
        }

        pi.setStatus("RUNNING");
        pi.setFinishedAt(null);
        pi.setCurrentNodeId(returnedNodeId);
        processInstanceMapper.updateById(pi);
        insertHistory(t, t.getNodeId(), returnedNodeId,
            force ? "FORCE_REJECT" : "REJECT", operatorId,
            cmd.comment());

        if (previous == null) {
            FormData formData = formDataMapper.selectById(pi.getFormDataId());
            if (formData == null) {
                throw new BizException("NOT_FOUND", "form data not found");
            }
            formData.setStatus("NEEDS_REVISION");
            formDataMapper.updateById(formData);
        }
        for (TaskEntity returned : returnedTasks) {
            notifier.publish(new NotificationEvent(this, "TASK_RETURNED",
                pi.getId(), returned.getId(), returned.getAssigneeId(),
                previous == null ? "申请已退回修改" : "审批已退回上一级"));
        }
    }

    @Transactional
    public List<Long> resubmitRework(long taskId, long operatorId) {
        LockedTask locked = lockPendingTask(taskId);
        TaskEntity task = locked.task();
        if (!"REWORK".equals(task.getTaskType())) {
            throw new BizException("TASK_NOT_PENDING", "Rework task not pending");
        }
        if (!Objects.equals(task.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }
        ProcessInstance instance = locked.instance();
        FormData formDataRow = formDataMapper.selectById(instance.getFormDataId());
        if (formDataRow == null) {
            throw new BizException("NOT_FOUND", "form data not found");
        }

        task.setStatus("RESUBMITTED");
        task.setApprovedBy(operatorId);
        task.setApprovedAt(OffsetDateTime.now());
        task.setComment("修改后重新提交");
        if (runtimeV2 != null && runtimeV2.active(instance)) {
            runtimeV2.beginRound(instance, "RESUBMIT");
        }
        taskMapper.updateById(task);
        formDataRow.setStatus("SUBMITTED");
        formDataMapper.updateById(formDataRow);
        if (runtimeV2 != null && runtimeV2.active(instance)) {
            long revisionId = runtimeV2.createRevision(formDataRow, "SUBMITTED", "RESUBMIT",
                operatorId);
            instance.setCurrentFormRevisionId(revisionId);
            task.setActionFormRevisionId(revisionId);
            taskMapper.updateById(task);
        }

        JsonNode root = readProcessTree(instance);
        JsonNode formData = readTreeOrEmpty(formDataRow.getData());
        Map<String, List<Long>> previousSelections = taskMapper.selectList(
                new QueryWrapper<TaskEntity>()
                    .eq("proc_inst_id", instance.getId())
                    .eq("task_type", "APPROVAL")
                    .orderByAsc("id"))
            .stream()
            .collect(Collectors.groupingBy(TaskEntity::getNodeId, LinkedHashMap::new,
                Collectors.mapping(TaskEntity::getAssigneeId,
                    Collectors.collectingAndThen(Collectors.toList(), values -> values.stream()
                        .distinct().toList()))));

        instance.setStatus("RUNNING");
        instance.setFinishedAt(null);
        processInstanceMapper.updateById(instance);
        List<Long> taskIds = resolveAndLand(root, instance, formData, instance.getStartedBy(),
            previousSelections, root);
        insertHistory(task, "__rework__", root.path("id").asText(null), "RESUBMIT",
            operatorId, task.getComment());
        return taskIds;
    }

    private TaskEntity previousApproval(TaskEntity current) {
        OffsetDateTime before = current.getCreatedAt() == null
            ? current.getApprovedAt() : current.getCreatedAt();
        QueryWrapper<TaskEntity> query = new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", current.getProcInstId())
            .eq("status", "APPROVED")
            .isNull("parallel_id")
            .ne("node_id", current.getNodeId());
        if (before != null) {
            query.lt("approved_at", before);
        }
        query.orderByDesc("approved_at").orderByDesc("id").last("LIMIT 1");
        return taskMapper.selectOne(query);
    }

    private List<Long> previousNodeAssignees(TaskEntity previous) {
        List<TaskEntity> rows = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", previous.getProcInstId())
            .eq("node_id", previous.getNodeId())
            .eq("status", "APPROVED")
            .isNull("parallel_id")
            .orderByAsc("id"));
        List<Long> assignees = rows.stream()
            .filter(task -> Objects.equals(previous.getNodeId(), task.getNodeId())
                && "APPROVED".equals(task.getStatus())
                && task.getAssigneeId() != null)
            .map(TaskEntity::getAssigneeId)
            .distinct()
            .collect(Collectors.toList());
        if (assignees.isEmpty()) {
            return previous.getAssigneeId() == null ? List.of() : List.of(previous.getAssigneeId());
        }
        return assignees;
    }

    private static TaskEntity newReturnedTask(Long procInstId, String nodeId, String taskType,
                                              String approvalMode, Long assigneeId,
                                              String parallelId, String branchId) {
        TaskEntity task = new TaskEntity();
        task.setProcInstId(procInstId);
        task.setNodeId(nodeId);
        task.setTaskType(taskType);
        task.setStatus("PENDING");
        task.setApprovalMode(approvalMode);
        task.setAssigneeId(assigneeId);
        task.setParallelId(parallelId);
        task.setBranchId(branchId);
        return task;
    }


    @Transactional
    public void withdraw(long instanceId, long operatorId) {
        ProcessInstance pi = processInstanceMapper.selectForUpdate(instanceId);
        if (pi == null) throw new BizException("NOT_FOUND", "instance not found");
        if (!Objects.equals(pi.getStartedBy(), operatorId)) {
            throw new AccessDeniedException("only starter can withdraw");
        }
        if (!"RUNNING".equals(pi.getStatus())) {
            throw new BizException("BAD_STATE", "instance not running");
        }
        if ("__rework__".equals(pi.getCurrentNodeId())) {
            throw new BizException("BAD_STATE", "instance already waiting for revision");
        }
        if (!canWithdrawCurrentRound(pi, operatorId)) {
            throw new BizException("ALREADY_ACTED",
                "cannot withdraw after a task has been acted on");
        }
        String previousNodeId = pi.getCurrentNodeId();
        List<TaskEntity> active = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).in("status", List.of("PENDING", "CC")));
        for (TaskEntity task : active) {
            task.setStatus("SKIPPED");
            taskMapper.updateById(task);
        }
        workflowJobMapper.cancelActive(pi.getId());

        TaskEntity rework = newReturnedTask(pi.getId(), "__rework__", "REWORK",
            "OR", pi.getStartedBy(), null, null);
        taskMapper.insert(rework);
        FormData formData = formDataMapper.selectById(pi.getFormDataId());
        if (formData == null) throw new BizException("NOT_FOUND", "form data not found");
        formData.setStatus("NEEDS_REVISION");
        formDataMapper.updateById(formData);

        pi.setStatus("RUNNING");
        pi.setCurrentNodeId("__rework__");
        pi.setFinishedAt(null);
        processInstanceMapper.updateById(pi);
        insertHistoryOnInstance(pi.getId(), previousNodeId, "__rework__",
            "WITHDRAW", operatorId, null);
        notifier.publish(new NotificationEvent(this, "TASK_RETURNED",
            pi.getId(), rework.getId(), pi.getStartedBy(), "申请已撤回，等待修改后重新提交"));
    }

    public boolean canWithdraw(long instanceId, long operatorId) {
        ProcessInstance instance = processInstanceMapper.selectById(instanceId);
        return instance != null && canWithdrawCurrentRound(instance, operatorId);
    }

    private boolean canWithdrawCurrentRound(ProcessInstance instance, long operatorId) {
        if (!Objects.equals(instance.getStartedBy(), operatorId)
            || !"RUNNING".equals(instance.getStatus())
            || "__rework__".equals(instance.getCurrentNodeId())) {
            return false;
        }
        TaskEntity boundary = taskMapper.selectOne(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", instance.getId())
            .eq("task_type", "REWORK")
            .eq("status", "RESUBMITTED")
            .orderByDesc("id").last("LIMIT 1"));
        QueryWrapper<TaskEntity> acted = new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", instance.getId())
            .eq("task_type", "APPROVAL")
            .in("status", List.of("APPROVED", "REJECTED"))
            .isNotNull("approved_by");
        if (boundary != null) acted.gt("id", boundary.getId());
        Long count = taskMapper.selectCount(acted);
        return count == null || count == 0;
    }

    /**
     * Completes a claimed automation job and, for blocking nodes, advances the
     * process in the same transaction. Repeated completion calls are no-ops.
     */
    @Transactional
    public boolean completeAutomation(Long jobId) {
        WorkflowJob initial = workflowJobMapper.selectById(jobId);
        if (initial == null) initial = workflowJobMapper.selectForUpdate(jobId);
        if (initial == null) return false;
        ProcessInstance instance = processInstanceMapper.selectForUpdate(initial.getProcInstId());
        WorkflowJob job = workflowJobMapper.selectForUpdate(jobId);
        if (job == null || !"RUNNING".equals(job.getStatus())) return false;
        if (instance == null || (Boolean.TRUE.equals(job.getBlocking())
            && !"RUNNING".equals(instance.getStatus()))) {
            job.setStatus("CANCELLED");
            job.setLockedAt(null);
            job.setLockedBy(null);
            workflowJobMapper.updateById(job);
            return false;
        }
        JsonNode root = null;
        if (Boolean.TRUE.equals(job.getBlocking())) {
            root = readProcessTree(instance);
            if (!Objects.equals(instance.getCurrentNodeId(), job.getNodeId())
                && !ProcessTreeNav.isInsideParallel(
                    root, instance.getCurrentNodeId(), job.getNodeId())) {
                job.setStatus("CANCELLED");
                job.setLastError("instance is no longer waiting at the automation node");
                job.setLockedAt(null);
                job.setLockedBy(null);
                workflowJobMapper.updateById(job);
                return false;
            }
        }

        job.setStatus("SUCCEEDED");
        job.setCompletedAt(OffsetDateTime.now());
        job.setLastError(null);
        job.setLockedAt(null);
        job.setLockedBy(null);
        workflowJobMapper.updateById(job);
        String action = "DELAY".equals(job.getJobType())
            ? "DELAY_COMPLETED" : "TRIGGER_SUCCEEDED";
        insertHistoryOnInstance(instance.getId(), job.getNodeId(), job.getNodeId(),
            action, null, "deliveryId=" + job.getDeliveryId());

        if (Boolean.TRUE.equals(job.getBlocking())) {
            JsonNode current = ProcessTreeNav.findById(root, job.getNodeId());
            if (current == null) {
                throw new BizException("BAD_FLOW", "automation node not found: " + job.getNodeId());
            }
            JsonNode formData = readFormData(instance.getFormDataId());
            ProcessTreeNav.ParallelParent parent =
                ProcessTreeNav.findParallelParent(root, current.path("id").asText());
            if (parent == null) {
                resolveAndLand(root, instance, formData, instance.getStartedBy(), Map.of(), current);
            } else {
                advanceParallelBranch(root, instance, formData,
                    parent.parallelId(), parent.branchId(), current);
            }
        }
        return true;
    }

    /** Executes a persisted timeout action; already-handled tasks are a successful no-op. */
    @Transactional
    public boolean completeTaskTimeout(Long jobId) {
        WorkflowJob job = workflowJobMapper.selectForUpdate(jobId);
        if (job == null || !"TASK_TIMEOUT".equals(job.getJobType())
            || !"RUNNING".equals(job.getStatus())) return false;
        TaskEntity task = taskMapper.selectById(job.getTaskId());
        if (task == null || !"PENDING".equals(task.getStatus())) {
            finishTimeoutJob(job, "task already completed");
            return false;
        }
        JsonNode policy = readTreeOrEmpty(job.getPayload());
        String action = policy.path("action").asText("REMIND");
        if (runtimeV2 == null) {
            finishTimeoutJob(job, "legacy task ignored");
            return false;
        }
        switch (action) {
            case "ESCALATE" -> runtimeV2.timeoutEscalate(job, task);
            case "AUTO_APPROVE" -> {
                if (!"LOW".equals(policy.path("riskLevel").asText())) {
                    runtimeV2.timeoutReminder(job, task);
                } else {
                    approveInternal(new CompleteCmd(task.getId(), "APPROVE",
                        "超时自动通过", null), task.getAssigneeId(), true);
                }
            }
            default -> runtimeV2.timeoutReminder(job, task);
        }
        finishTimeoutJob(job, null);
        return true;
    }

    private void finishTimeoutJob(WorkflowJob job, String note) {
        job.setStatus("SUCCEEDED");
        job.setCompletedAt(OffsetDateTime.now());
        job.setLastError(note);
        job.setLockedAt(null);
        job.setLockedBy(null);
        workflowJobMapper.updateById(job);
        insertHistoryOnInstance(job.getProcInstId(), job.getNodeId(), job.getNodeId(),
            note == null ? "TASK_TIMEOUT" : "TASK_TIMEOUT_SKIPPED", null, note);
    }

    private LockedTask lockPendingTask(long taskId) {
        TaskEntity initial = taskMapper.selectById(taskId);
        if (initial == null) {
            throw new BizException("TASK_NOT_PENDING", "Task not pending");
        }
        ProcessInstance instance = processInstanceMapper.selectForUpdate(initial.getProcInstId());
        if (instance == null) {
            throw new BizException("NOT_FOUND", "instance not found");
        }
        TaskEntity current = taskMapper.selectForUpdate(taskId);
        if (current == null || !Objects.equals(current.getProcInstId(), instance.getId())
            || !"PENDING".equals(current.getStatus())) {
            throw new BizException("TASK_NOT_PENDING", "Task not pending");
        }
        return new LockedTask(current, instance);
    }

    private void updateTaskOrConflict(TaskEntity task) {
        if (taskMapper.updateById(task) != 1) {
            throw new BizException("CONCURRENT_CONFLICT", "Task changed concurrently");
        }
    }

    private record LockedTask(TaskEntity task, ProcessInstance instance) { }

    /**
     * 委托任务（delegatedFrom != null 且非加签）审批时，原任务若仍为 PENDING，
     * 则由委托任务替代原任务完成，避免 AND 模式把“同一审批人的镜像任务”算成两个人。
     */
    private void skipDelegatedParentIfNeeded(TaskEntity task, long operatorId) {
        if (task.getDelegatedFrom() == null || Boolean.TRUE.equals(task.getIsAdditional())
                || task.getParentTaskId() == null) {
            return;
        }
        TaskEntity parent = taskMapper.selectById(task.getParentTaskId());
        if (parent == null || !"PENDING".equals(parent.getStatus())
                || !Objects.equals(parent.getNodeId(), task.getNodeId())) {
            return;
        }
        parent.setStatus("SKIPPED");
        parent.setComment("由委托任务 #" + task.getId() + " 处理");
        taskMapper.updateById(parent);
        insertHistoryOnInstance(parent.getProcInstId(), parent.getNodeId(), parent.getNodeId(),
            "SKIP", operatorId, "delegated task acted");
    }

    /**
     * 审批节点可编辑字段回写：只允许修改当前节点 formPerms 中标记为 EDITABLE 的字段，
     * 逐字段按 schema 校验后合并进 t_form_data.data（未提交的字段保持不变）。
     */
    private void applyNodeFieldEdits(ProcessInstance pi, JsonNode cur, Object data,
                                     long operatorId) {
        if (data instanceof Map<?, ?> edits && edits.isEmpty()) {
            return;
        }
        Set<String> editable = editableFieldIds(cur);
        if (editable.isEmpty()) {
            throw new BizException("FORM_DATA_INVALID", "该审批节点不允许编辑表单字段");
        }
        if (!(data instanceof Map<?, ?> edits)) {
            throw new BizException("FORM_DATA_INVALID", "表单字段更新必须是对象");
        }
        for (Object key : edits.keySet()) {
            if (!(key instanceof String fieldId) || !editable.contains(fieldId)) {
                throw new BizException("FORM_DATA_INVALID", "字段不可编辑: " + key);
            }
        }
        FormData formData = formDataMapper.selectById(pi.getFormDataId());
        if (formData == null) {
            throw new BizException("NOT_FOUND", "form data not found");
        }
        FormDefinition fd = formDefinitionService.getById(formData.getFormDefId());
        String schema = runtimeV2 == null ? null : runtimeV2.formSchema(pi);
        if (fd == null && schema == null) {
            throw new BizException("NOT_FOUND", "form definition not found");
        }
        var merged = (com.fasterxml.jackson.databind.node.ObjectNode)
            readTreeOrEmpty(formData.getData()).deepCopy();
        edits.forEach((key, value) -> merged.set((String) key, json.valueToTree(value)));
        formDefinitionService.validateSubmission(schema == null ? fd.getSchema() : schema,
            merged, editable);
        formData.setData(writeJson(merged));
        formDataMapper.updateById(formData);
        if (runtimeV2 != null && runtimeV2.active(pi)) {
            long revisionId = runtimeV2.createRevision(formData, "SUBMITTED", "NODE_EDIT",
                operatorId);
            pi.setCurrentFormRevisionId(revisionId);
            processInstanceMapper.updateById(pi);
        }
    }

    private Set<String> editableFieldIds(JsonNode node) {
        var perms = node.path("props").path("formPerms");
        var ids = new LinkedHashSet<String>();
        if (perms.isArray()) {
            for (var entry : perms) {
                if ("EDITABLE".equals(entry.path("mode").asText())) {
                    ids.add(entry.path("fieldId").asText());
                }
            }
        }
        return ids;
    }


    // -----------------------------------------------------------------------
    // 核心：resolveAndLand — 从刚完成/起点节点起沿树前进，直到落到一个需要建
    // 任务的 APPROVAL 节点（可能多条）、走完末端、或实例结束。
    // -----------------------------------------------------------------------

    /**
     * @param root          流程树根（用于历史记录）
     * @param pi            当前实例
     * @param formData      当前表单数据（条件求值用）
     * @param starterId     发起人 id
     * @param selfSelected  自选审批人映射
     * @param fromNode      刚完成的节点（首轮 = root）
     * @return 新建的任务 id 列表
     */
    private List<Long> resolveAndLand(JsonNode root, ProcessInstance pi,
                                      JsonNode formData, long starterId,
                                      Map<String, List<Long>> selfSelected,
                                      JsonNode fromNode) {
        JsonNode node = ProcessTreeNav.next(root, fromNode, null);
        List<Long> taskIds = resolveAndLandLoop(root, pi, formData, starterId, selfSelected,
            fromNode, node);
        notifyAssigned(pi.getId(), taskIds);
        return taskIds;
    }

    /**
     * 驳回到节点专用：从指定 target 节点自身（而不是其 children）开始推进，
     * 让 target 自身被重新评估（建任务 / AUTO_PASS / 继续 children）。
     * fromNode 设为 null，使 ARRIVE/CC/AUTO_PASS/COMPLETE 历史行的 from 字段为空。
     */
    private List<Long> resolveAndLandFromNode(JsonNode root, ProcessInstance pi,
                                               JsonNode formData, long starterId,
                                               Map<String, List<Long>> selfSelected,
                                               JsonNode targetNode) {
        List<Long> taskIds = resolveAndLandLoop(root, pi, formData, starterId, selfSelected,
            null, targetNode);
        notifyAssigned(pi.getId(), taskIds);
        return taskIds;
    }

    /** 真正的循环实现：startNode 为入口节点（可能为 null）。 */
    private List<Long> resolveAndLandLoop(JsonNode root, ProcessInstance pi,
                                          JsonNode formData, long starterId,
                                          Map<String, List<Long>> selfSelected,
                                          JsonNode fromNode, JsonNode startNode) {
        JsonNode node = startNode;
        NodeContext ctx = new NodeContext(starterId, formData, selfSelected, fromNode == null ? null : fromNode.path("id").asText(null), null, null);
        while (true) {
            if (node == null) {
                // 末端 → 实例 APPROVED
                pi.setStatus("APPROVED");
                pi.setFinishedAt(OffsetDateTime.now());
                pi.setCurrentNodeId(null);
                processInstanceMapper.updateById(pi);
                insertHistoryOnInstance(pi.getId(),
                    fromNode == null ? null : fromNode.path("id").asText(null),
                    null, "COMPLETE", pi.getStartedBy(), null);
                if (runtimeV2 != null && runtimeV2.active(pi)) {
                    runtimeV2.outbox(pi.getId(), pi.getStartedBy(), "INSTANCE_APPROVED", null);
                } else {
                    notifier.publish(new NotificationEvent(this, "INSTANCE_APPROVED",
                        pi.getId(), null, pi.getStartedBy(),
                        "实例 #" + pi.getId() + " 已审批通过"));
                }
                return List.of();
            }
            String type = node.path("type").asText();
            NodeHandler handler = pickHandler(type);
            if (handler == null) {
                throw new BizException("BAD_NODE_TYPE", "未识别节点类型: " + type);
            }
            long nodeInstanceId = runtimeV2 == null ? 0 : runtimeV2.enterNode(pi, node, ctx);
            NodeOutcome outcome = handler.handle(root, node, pi,
                nodeInstanceId == 0 ? ctx : ctx.atNode(nodeInstanceId));
            switch (outcome.type()) {
                case NEXT:
                    if (runtimeV2 != null) runtimeV2.completeNode(nodeInstanceId, "PASSED");
                    String nextFromId = node.path("id").asText(null);
                    fromNode = node;
                    node = outcome.node();
                    ctx = new NodeContext(starterId, formData, selfSelected, nextFromId, null, null);
                    continue;
                case JUMP:
                    if (runtimeV2 != null) runtimeV2.completeNode(nodeInstanceId, "PASSED");
                    String jumpFromId = node.path("id").asText(null);
                    fromNode = node;
                    node = outcome.node();
                    ctx = new NodeContext(starterId, formData, selfSelected, jumpFromId, null, null);
                    continue;
                case END:
                    if (runtimeV2 != null) runtimeV2.completeNode(nodeInstanceId, "PASSED");
                    processInstanceMapper.updateById(pi);
                    insertHistoryOnInstance(pi.getId(), node.path("id").asText(null),
                        null, "COMPLETE", pi.getStartedBy(), null);
                    return List.of();
                case HALT:
                default:
                    // handler 已建 PENDING 任务并写 ARRIVE 历史；保存实例并返回
                    processInstanceMapper.updateById(pi);
                    if (outcome instanceof NodeOutcome.Halt h) {
                        return h.newTaskIds();
                    }
                    return List.of();
            }
        }
    }

    private NodeHandler pickHandler(String type) {
        for (NodeHandler h : nodeHandlers) {
            if (h.supports(type)) return h;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private JsonNode readTree(String s) {
        if (s == null || s.isBlank()) {
            throw new BizException("BAD_FLOW_JSON", "process tree is empty");
        }
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new BizException("BAD_FLOW_JSON", e.getMessage());
        }
    }

    private JsonNode readTreeOrEmpty(String s) {
        if (s == null || s.isBlank()) return json.createObjectNode();
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }

    private JsonNode readFormData(Long formDataId) {
        FormData fd = formDataMapper.selectById(formDataId);
        if (fd == null) return json.createObjectNode();
        return readTreeOrEmpty(fd.getData());
    }

    /** 旧运行快照只在内存中按当前表单 Schema 兼容 label，绝不回写。 */
    private JsonNode readProcessTree(ProcessInstance instance) {
        if (runtimeV2 != null && runtimeV2.active(instance)) {
            return readTree(runtimeV2.processTree(instance));
        }
        FormData data = formDataMapper.selectById(instance.getFormDataId());
        FormDefinition form = data == null ? null
            : formDefinitionService.getById(data.getFormDefId());
        if (form == null) return readTree(instance.getProcessSnapshot());
        String normalized = processDefinitionService.normalizeConditionValues(
            instance.getProcessSnapshot(), form.getSchema());
        return readTree(normalized == null ? instance.getProcessSnapshot() : normalized);
    }

    private boolean hasActiveParallelAutomation(JsonNode root, Long instanceId,
                                                String parallelId) {
        List<WorkflowJob> active = workflowJobMapper.selectList(
            new QueryWrapper<WorkflowJob>()
                .eq("proc_inst_id", instanceId)
                .eq("blocking", true)
                .in("status", List.of("SCHEDULED", "RUNNING", "FAILED")));
        return active != null && active.stream().anyMatch(job ->
            ProcessTreeNav.isInsideParallel(root, parallelId, job.getNodeId()));
    }

    private void notifyAssigned(Long instanceId, List<Long> taskIds) {
        for (Long taskId : taskIds) {
            TaskEntity task = taskMapper.selectById(taskId);
            if (task != null) {
                if (task.getNodeInstanceId() != null) continue;
                notifier.publish(new NotificationEvent(this, "TASK_ASSIGNED",
                    instanceId, taskId, task.getAssigneeId(),
                    "新任务 #" + taskId + " 节点 " + task.getNodeId()));
            }
        }
    }

    private void rejectV2(CompleteCmd cmd, long operatorId, boolean force, TaskEntity task,
                          ProcessInstance instance, JsonNode root, JsonNode current) {
        TaskEntity previous = previousApproval(task);
        String requested = cmd.rejectToNodeId();
        String targetId = requested == null || requested.isBlank()
            ? (previous == null ? null : previous.getNodeId()) : requested;
        JsonNode target = targetId == null ? null : ProcessTreeNav.findById(root, targetId);
        if (target != null) {
            JsonNode configuredTargets = current.path("props").path("rejectTargets");
            boolean configured = configuredTargets.isArray() && !configuredTargets.isEmpty()
                ? java.util.stream.StreamSupport.stream(configuredTargets.spliterator(), false)
                    .anyMatch(value -> targetId.equals(value.asText()))
                : previous != null && targetId.equals(previous.getNodeId());
            if (!"APPROVAL".equals(target.path("type").asText())
                || !ProcessTreeNav.isAncestor(root, targetId, task.getNodeId())
                || ProcessTreeNav.isInsideParallelBranch(root, targetId)
                || (!force && !configured)) {
                throw new BizException("BAD_REJECT_TARGET", "reject target is not an allowed upstream approval");
            }
            runtimeV2.invalidateApprovedNodes(instance,
                ProcessTreeNav.nodesBetween(root, targetId, task.getNodeId()),
                "rejected back to " + targetId);
            runtimeV2.beginRound(instance, "REJECT_TO_NODE");
            instance.setStatus("RUNNING");
            instance.setFinishedAt(null);
            instance.setCurrentNodeId(targetId);
            processInstanceMapper.updateById(instance);
            insertHistory(task, task.getNodeId(), targetId,
                force ? "FORCE_REJECT" : "REJECT_TO_NODE", operatorId, cmd.comment());
            resolveAndLandFromNode(root, instance, readFormData(instance.getFormDataId()),
                instance.getStartedBy(), Map.of(), target);
            return;
        }
        if (targetId != null) {
            throw new BizException("BAD_REJECT_TARGET", "reject target not found: " + targetId);
        }

        TaskEntity rework = newReturnedTask(instance.getId(), "__rework__", "REWORK",
            "ANY", instance.getStartedBy(), null, null);
        rework.setActionFormRevisionId(instance.getCurrentFormRevisionId());
        rework.setOperationKind("REWORK");
        taskMapper.insert(rework);

        FormData formData = formDataMapper.selectById(instance.getFormDataId());
        if (formData == null) throw new BizException("NOT_FOUND", "form data not found");
        formData.setStatus("NEEDS_REVISION");
        formDataMapper.updateById(formData);
        long revisionId = runtimeV2.createRevision(formData, "NEEDS_REVISION", "REJECTED",
            operatorId);
        instance.setCurrentFormRevisionId(revisionId);
        instance.setCurrentNodeInstanceId(null);
        instance.setCurrentNodeId("__rework__");
        instance.setStatus("RUNNING");
        instance.setFinishedAt(null);
        processInstanceMapper.updateById(instance);
        insertHistory(task, task.getNodeId(), "__rework__",
            force ? "FORCE_REJECT" : "REJECT", operatorId, cmd.comment());
        runtimeV2.outbox(instance.getId(), rework.getAssigneeId(), "TASK_RETURNED", rework.getId());
    }

    private void insertHistory(TaskEntity t, String from, String to,
                                String action, Long operatorId, String comment) {
        TaskHistoryEntity h = new TaskHistoryEntity();
        h.setProcInstId(t.getProcInstId());
        h.setTaskId(t.getId());
        h.setFromNodeId(from);
        h.setToNodeId(to);
        h.setAction(action);
        h.setOperatorId(operatorId);
        h.setComment(comment);
        historyMapper.insert(h);
    }

    private void insertHistoryOnInstance(Long instId, String from, String to,
                                          String action, Long operatorId, String comment) {
        TaskHistoryEntity h = new TaskHistoryEntity();
        h.setProcInstId(instId);
        h.setFromNodeId(from);
        h.setToNodeId(to);
        h.setAction(action);
        h.setOperatorId(operatorId);
        h.setComment(comment);
        historyMapper.insert(h);
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}

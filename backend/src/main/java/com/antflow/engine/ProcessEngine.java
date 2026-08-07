package com.antflow.engine;

import com.antflow.automation.WorkflowJob;
import com.antflow.automation.WorkflowJobMapper;
import com.antflow.auth.PrincipalHolder;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *   <li>{@link #withdraw(long, long)} — 发起人在任意任务被处理前撤回。</li>
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

    @Transactional
    public Map<String, Object> start(StartCmd cmd, long userId) {
        FormDefinition fd = formDefinitionService.getByCode(cmd.formCode());
        if (fd == null || !"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + cmd.formCode());
        }
        ProcessDefinition pd = processDefinitionService.latestPublishedForForm(fd.getId());
        if (pd == null) {
            throw new BizException("NO_FLOW", "No published process for form " + cmd.formCode());
        }

        FormData fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        fd2.setBusinessNo(formalNumberService.businessNo());
        fd2.setData(writeJson(cmd.data()));
        fd2.setStatus("SUBMITTED");
        fd2.setCreatedBy(userId);
        formDataMapper.insert(fd2);

        ProcessInstance pi = new ProcessInstance();
        pi.setProcDefId(pd.getId());
        pi.setProcessDefVersion(pd.getVersion());
        pi.setProcessSnapshot(pd.getProcess());  // 冻结流程树，避免后续改版污染已发起实例
        pi.setFormDataId(fd2.getId());
        pi.setStatus("RUNNING");
        pi.setStartedBy(userId);
        PrincipalHolder.current()
            .filter(principal -> principal.userId() == userId)
            .ifPresent(principal -> pi.setStartedDeptId(principal.departmentId()));
        pi.setStartedAt(OffsetDateTime.now());
        processInstanceMapper.insert(pi);

        // 引擎后续一律走快照树，而非 pd.getProcess()
        JsonNode root = readTree(pi.getProcessSnapshot());
        JsonNode formData = readTreeOrEmpty(fd2.getData());
        Map<String, List<Long>> selfSelected =
            cmd.selfSelected() == null ? Map.of() : cmd.selfSelected();

        List<Long> firstTasks = resolveAndLand(root, pi, formData, userId, selfSelected, root);
        notifier.publish(new NotificationEvent(this, "INSTANCE_STARTED",
            pi.getId(), null, userId, "流程发起 #" + pi.getId()));
        for (Long tid : firstTasks) {
            TaskEntity nt = taskMapper.selectById(tid);
            if (nt != null) {
                notifier.publish(new NotificationEvent(this, "TASK_ASSIGNED",
                    pi.getId(), tid, nt.getAssigneeId(),
                    "新任务 #" + tid + " 节点 " + nt.getNodeId()));
            }
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

    private void approveInternal(CompleteCmd cmd, long operatorId, boolean force) {
        LockedTask locked = lockPendingTask(cmd.taskId());
        TaskEntity t = locked.task();
        ProcessInstance pi = locked.instance();
        if ("REWORK".equals(t.getTaskType())) {
            throw new BizException("BAD_TASK_TYPE", "Rework task must be resubmitted from the form");
        }
        if (!force && !Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }

        t.setStatus("APPROVED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        taskMapper.updateById(t);
        insertHistory(t, null, t.getNodeId(), force ? "FORCE_APPROVE" : "APPROVE",
            operatorId, cmd.comment());

        // 永远走快照，不依赖 pd.getProcess()（避免流程改版后已发起的实例跑飞）
        JsonNode root = readTree(pi.getProcessSnapshot());
        JsonNode cur = ProcessTreeNav.findById(root, t.getNodeId());
        if (cur == null) {
            throw new BizException("BAD_FLOW", "approval node not in tree: " + t.getNodeId());
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
            // Advance this branch's remaining single chain before checking the join.
            landParallelContinuation(root, pi, formData, t, cur);
            Long stillPending = taskMapper.selectCount(new QueryWrapper<TaskEntity>()
                .eq("proc_inst_id", pi.getId())
                .eq("parallel_id", t.getParallelId())
                .eq("status", "PENDING"));
            if (stillPending != null && stillPending > 0) {
                processInstanceMapper.updateById(pi);
                return;
            }
            JsonNode parallelNode = ProcessTreeNav.findById(root, t.getParallelId());
            if (parallelNode == null) {
                throw new BizException("BAD_FLOW", "parallel node not found: " + t.getParallelId());
            }
            processInstanceMapper.updateById(pi);
            resolveAndLand(root, pi, formData, pi.getStartedBy(), Map.of(), parallelNode);
            return;
        }
        // 仅首轮 start 时传入过 selfSelected；后续（理论上不会出现）传空 map。
        resolveAndLand(root, pi, formData, pi.getStartedBy(), Map.of(), cur);
    }

    private void landParallelContinuation(JsonNode root, ProcessInstance instance,
                                          JsonNode formData, TaskEntity completedTask,
                                          JsonNode completedNode) {
        JsonNode node = ProcessTreeNav.childrenOf(completedNode);
        NodeContext context = new NodeContext(
            instance.getStartedBy(), formData, Map.of(), completedNode.path("id").asText(null),
            completedTask.getParallelId(), completedTask.getBranchId()
        );
        while (node != null) {
            String type = node.path("type").asText();
            if (!"APPROVAL".equals(type) && !"CC".equals(type)) {
                throw new BizException("BAD_FLOW", "并行分支内只允许审批和抄送节点: " + type);
            }
            NodeHandler handler = pickHandler(type);
            if (handler == null) {
                throw new BizException("BAD_NODE_TYPE", "未识别节点类型: " + type);
            }
            JsonNode handled = node;
            NodeOutcome outcome = handler.handle(root, handled, instance, context);
            if (outcome.type() == NodeOutcome.Type.HALT || outcome.type() == NodeOutcome.Type.END) {
                return;
            }
            node = outcome.node();
            context = new NodeContext(
                instance.getStartedBy(), formData, Map.of(), handled.path("id").asText(null),
                completedTask.getParallelId(), completedTask.getBranchId()
            );
        }
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
        if (!force && !Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }

        t.setStatus("REJECTED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        taskMapper.updateById(t);

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

        JsonNode root = readTree(pi.getProcessSnapshot());
        JsonNode cur = ProcessTreeNav.findById(root, t.getNodeId());
        if (cur == null) {
            throw new BizException("BAD_FLOW", "current node not in tree: " + t.getNodeId());
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
        TaskEntity returned = new TaskEntity();
        returned.setProcInstId(pi.getId());
        returned.setStatus("PENDING");
        returned.setTaskType(previous == null ? "REWORK" : "APPROVAL");
        returned.setNodeId(previous == null ? "__rework__" : previous.getNodeId());
        returned.setAssigneeId(previous == null ? pi.getStartedBy() : previous.getAssigneeId());
        returned.setApprovalMode(previous == null ? "OR_SIGN" : previous.getApprovalMode());
        taskMapper.insert(returned);

        pi.setStatus("RUNNING");
        pi.setFinishedAt(null);
        pi.setCurrentNodeId(returned.getNodeId());
        processInstanceMapper.updateById(pi);
        insertHistory(t, t.getNodeId(), returned.getNodeId(),
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
        notifier.publish(new NotificationEvent(this, "TASK_RETURNED",
            pi.getId(), returned.getId(), returned.getAssigneeId(),
            previous == null ? "申请已退回修改" : "审批已退回上一级"));
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
        taskMapper.updateById(task);
        formDataRow.setStatus("SUBMITTED");
        formDataMapper.updateById(formDataRow);

        JsonNode root = readTree(instance.getProcessSnapshot());
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
        for (Long newTaskId : taskIds) {
            TaskEntity next = taskMapper.selectById(newTaskId);
            if (next != null) {
                notifier.publish(new NotificationEvent(this, "TASK_ASSIGNED",
                    instance.getId(), next.getId(), next.getAssigneeId(),
                    "重新提交的审批任务 #" + next.getId()));
            }
        }
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
        List<TaskEntity> anyDone = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).ne("status", "PENDING"));
        if (!anyDone.isEmpty()) {
            throw new BizException("ALREADY_ACTED",
                "cannot withdraw after a task has been acted on");
        }
        List<TaskEntity> pending = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).eq("status", "PENDING"));
        for (TaskEntity p : pending) {
            p.setStatus("SKIPPED");
            taskMapper.updateById(p);
        }
        pi.setStatus("WITHDRAWN");
        pi.setFinishedAt(OffsetDateTime.now());
        processInstanceMapper.updateById(pi);
        workflowJobMapper.cancelActive(pi.getId());
        insertHistoryOnInstance(pi.getId(), null, pi.getCurrentNodeId(),
            "WITHDRAW", operatorId, null);
        notifier.publish(new NotificationEvent(this, "INSTANCE_WITHDRAWN",
            pi.getId(), null, pi.getStartedBy(), "实例 #" + pi.getId() + " 已撤回"));
    }

    /**
     * Completes a claimed automation job and, for blocking nodes, advances the
     * process in the same transaction. Repeated completion calls are no-ops.
     */
    @Transactional
    public boolean completeAutomation(Long jobId) {
        WorkflowJob job = workflowJobMapper.selectForUpdate(jobId);
        if (job == null || !"RUNNING".equals(job.getStatus())) return false;

        ProcessInstance instance = processInstanceMapper.selectForUpdate(job.getProcInstId());
        if (instance == null || (Boolean.TRUE.equals(job.getBlocking())
            && !"RUNNING".equals(instance.getStatus()))) {
            job.setStatus("CANCELLED");
            job.setLockedAt(null);
            job.setLockedBy(null);
            workflowJobMapper.updateById(job);
            return false;
        }
        if (Boolean.TRUE.equals(job.getBlocking())
            && !Objects.equals(instance.getCurrentNodeId(), job.getNodeId())) {
            job.setStatus("CANCELLED");
            job.setLastError("instance is no longer waiting at the automation node");
            job.setLockedAt(null);
            job.setLockedBy(null);
            workflowJobMapper.updateById(job);
            return false;
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
            JsonNode root = readTree(instance.getProcessSnapshot());
            JsonNode current = ProcessTreeNav.findById(root, job.getNodeId());
            if (current == null) {
                throw new BizException("BAD_FLOW", "automation node not found: " + job.getNodeId());
            }
            resolveAndLand(root, instance, readFormData(instance.getFormDataId()),
                instance.getStartedBy(), Map.of(), current);
        }
        return true;
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
        TaskEntity current = taskMapper.selectById(taskId);
        if (current == null || !Objects.equals(current.getProcInstId(), instance.getId())
            || !"PENDING".equals(current.getStatus())) {
            throw new BizException("TASK_NOT_PENDING", "Task not pending");
        }
        return new LockedTask(current, instance);
    }

    private record LockedTask(TaskEntity task, ProcessInstance instance) { }

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
        JsonNode node = ProcessTreeNav.childrenOf(fromNode);
        return resolveAndLandLoop(root, pi, formData, starterId, selfSelected,
            fromNode, node);
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
        return resolveAndLandLoop(root, pi, formData, starterId, selfSelected,
            null, targetNode);
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
                notifier.publish(new NotificationEvent(this, "INSTANCE_APPROVED",
                    pi.getId(), null, pi.getStartedBy(),
                    "实例 #" + pi.getId() + " 已审批通过"));
                return List.of();
            }
            String type = node.path("type").asText();
            NodeHandler handler = pickHandler(type);
            if (handler == null) {
                throw new BizException("BAD_NODE_TYPE", "未识别节点类型: " + type);
            }
            NodeOutcome outcome = handler.handle(root, node, pi, ctx);
            switch (outcome.type()) {
                case NEXT:
                    String nextFromId = node.path("id").asText(null);
                    fromNode = node;
                    node = outcome.node();
                    ctx = new NodeContext(starterId, formData, selfSelected, nextFromId, null, null);
                    continue;
                case JUMP:
                    String jumpFromId = node.path("id").asText(null);
                    fromNode = node;
                    node = outcome.node();
                    ctx = new NodeContext(starterId, formData, selfSelected, jumpFromId, null, null);
                    continue;
                case END:
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

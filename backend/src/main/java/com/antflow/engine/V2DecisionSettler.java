package com.antflow.engine;

import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.process.RevisionReasons;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.Supplier;

/**
 * V2（{@code engine_version = 2}）的结算：把 {@link WorkflowRuntimeV2} 算出的
 * {@link WorkflowRuntimeV2.Decision} 落到任务、实例与历史上。
 *
 * <p>V2 是**唯一的写入运行时**（新实例一律 engineVersion=2），但它的保护只在
 * PostgreSQL 集成测试里，本地单测跑不到。所以这里的搬迁纪律是死规矩：
 * 方法体逐字不动，只改调用前缀；任何"顺手优化"都等于在无保护的情况下改行为。
 *
 * <p>三个易错点：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：它跑在调用方 approve/reject 的事务里，
 *       自己开事务会让"任务 + 实例 + 历史 + outbox"分成两段提交。</li>
 *   <li>会签/或签的"驳回"必须落到**第一个被驳回的任务**（{@code firstRejectedTask}），
 *       而不是当前这次投票的人；否则驳回落点会随投票顺序漂移。</li>
 *   <li>ALL/RATIO 节点的驳回目标是**固定的**（{@code fixedRejectTarget}），
 *       客户端传来的 rejectToNodeId 必须丢掉，否则会绕过会签配置。</li>
 * </ol>
 */
final class V2DecisionSettler {

    private final EngineSupport support;
    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final FormDataMapper formDataMapper;
    private final Supplier<WorkflowRuntimeV2> runtimeV2;
    private final FlowAdvancer flow;

    V2DecisionSettler(EngineSupport support, TaskMapper taskMapper,
                      ProcessInstanceMapper processInstanceMapper,
                      FormDataMapper formDataMapper,
                      Supplier<WorkflowRuntimeV2> runtimeV2, FlowAdvancer flow) {
        this.support = support;
        this.taskMapper = taskMapper;
        this.processInstanceMapper = processInstanceMapper;
        this.formDataMapper = formDataMapper;
        this.runtimeV2 = runtimeV2;
        this.flow = flow;
    }

    void settleV2Decision(WorkflowRuntimeV2.Decision decision, CompleteCmd command,
                          long operatorId, boolean force, TaskEntity acted,
                          ProcessInstance instance, JsonNode root, JsonNode current) {
        if (!decision.advance() && !decision.reject()) return;
        if (decision.advance()) {
            JsonNode formData = support.readFormData(instance.getFormDataId());
            if (acted.getParallelId() != null) {
                flow.advanceParallelBranch(root, instance, formData, acted.getParallelId(),
                    acted.getBranchId(), current);
            } else {
                flow.resolveAndLand(root, instance, formData, instance.getStartedBy(), Map.of(), current);
            }
            return;
        }

        TaskEntity rejection = firstRejectedTask(acted);
        long rejectionOperator = rejection.getApprovedBy() == null
            ? operatorId : rejection.getApprovedBy();
        CompleteCmd rejectionCommand = fixedRejectTarget(current)
            ? new CompleteCmd(rejection.getId(), "REJECT", rejection.getComment(), null)
            : command;
        if (rejection.getParallelId() != null) {
            WorkflowRuntimeV2.ParallelDecision parallel =
                runtimeV2.get().parallelBranchRejected(rejection, instance, root);
            if (parallel == WorkflowRuntimeV2.ParallelDecision.WAIT) return;
            if (parallel == WorkflowRuntimeV2.ParallelDecision.ADVANCE) {
                flow.advanceParallelBranch(root, instance,
                    support.readFormData(instance.getFormDataId()),
                    rejection.getParallelId(), rejection.getBranchId(), current);
                return;
            }
        }
        rejectV2(rejectionCommand, rejectionOperator, force, rejection, instance, root, current);
    }

    private TaskEntity firstRejectedTask(TaskEntity acted) {
        TaskEntity rejection = taskMapper.selectOne(new QueryWrapper<TaskEntity>()
            .eq("node_instance_id", acted.getNodeInstanceId())
            .eq("status", "REJECTED")
            .orderByAsc("approved_at", "id")
            .last("LIMIT 1"));
        return rejection == null ? acted : rejection;
    }

    private static boolean fixedRejectTarget(JsonNode node) {
        String mode = WorkflowRuntimeV2.mode(node);
        return "ALL".equals(mode) || "RATIO".equals(mode);
    }

    private void rejectV2(CompleteCmd cmd, long operatorId, boolean force, TaskEntity task,
                          ProcessInstance instance, JsonNode root, JsonNode current) {
        TaskEntity previous = support.previousApproval(task);
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
            runtimeV2.get().invalidateApprovedNodes(instance,
                ProcessTreeNav.nodesBetween(root, targetId, task.getNodeId()),
                "rejected back to " + targetId);
            runtimeV2.get().beginRound(instance, "REJECT_TO_NODE");
            instance.setStatus("RUNNING");
            instance.setFinishedAt(null);
            instance.setCurrentNodeId(targetId);
            processInstanceMapper.updateById(instance);
            support.insertHistory(task, task.getNodeId(), targetId,
                force ? "FORCE_REJECT" : "REJECT_TO_NODE", operatorId, cmd.comment());
            flow.resolveAndLandFromNode(root, instance,
                support.readFormData(instance.getFormDataId()),
                instance.getStartedBy(), Map.of(), target);
            return;
        }
        if (targetId != null) {
            throw new BizException("BAD_REJECT_TARGET", "reject target not found: " + targetId);
        }

        TaskEntity rework = EngineSupport.newReturnedTask(instance.getId(), "__rework__", "REWORK",
            "ANY", instance.getStartedBy(), null, null);
        rework.setActionFormRevisionId(instance.getCurrentFormRevisionId());
        rework.setOperationKind("REWORK");
        taskMapper.insert(rework);

        FormData formData = formDataMapper.selectRuntimeById(instance.getFormDataId());
        if (formData == null) throw new BizException("NOT_FOUND", "form data not found");
        formData.setStatus("NEEDS_REVISION");
        formDataMapper.updateById(formData);
        long revisionId = runtimeV2.get().createRevision(formData, "NEEDS_REVISION",
            RevisionReasons.REJECTED, operatorId);
        instance.setCurrentFormRevisionId(revisionId);
        instance.setCurrentNodeInstanceId(null);
        instance.setCurrentNodeId("__rework__");
        instance.setStatus("RUNNING");
        instance.setFinishedAt(null);
        processInstanceMapper.updateById(instance);
        support.insertHistory(task, task.getNodeId(), "__rework__",
            force ? "FORCE_REJECT" : "REJECT", operatorId, cmd.comment());
        runtimeV2.get().outbox(instance.getId(), rework.getAssigneeId(), "TASK_RETURNED",
            rework.getId());
    }
}

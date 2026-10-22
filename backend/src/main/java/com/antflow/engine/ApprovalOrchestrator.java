package com.antflow.engine;

import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 审批 / 驳回的编排：锁任务 → 校验 → 写任务与历史 → 判 V1/V2 分流 → 交给结算或落地。
 *
 * <p>为什么是**一个类装两条流程**、而不是把 {@code approveInternal} 再切几段：
 * 审批和驳回是镜像的两条路径（同样先锁、同样写一条历史、同样在 V2 有 nodeInstanceId 时分流、
 * 同样最后推进），任何一段被单独拿走，另一段都会留下"半条规则"。真正的变化原因只有一个：
 * **决策怎么落到任务上**；V1/V2 的差异已经各自沉到 {@link LegacyDecisionSettler} /
 * {@link V2DecisionSettler}，推进已沉到 {@link NodeLander}。这里只剩"顺序"。
 *
 * <p>五个易错点：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：入口 {@code ProcessEngine.approve/reject} 已开事务，
 *       系统代批也从 {@code AutomationCompleter} 的事务里进来。这里加事务会让任务状态与后续节点分两段提交。</li>
 *   <li>V2 分流条件必须是 {@code active(pi) && nodeInstanceId != null} 两条**同时**成立：
 *       缺 nodeInstanceId 说明是历史/V1 任务，不能交给 V2 运行时。</li>
 *   <li>V1 的 OR-sign 必须把同节点其他 PENDING 任务置 SKIPPED 并写 SKIP 历史；
 *       只改状态不写历史，审批轨迹里会出现"凭空消失的任务"。</li>
 *   <li>V1 会签未通过时，驳回人取**第一个 REJECTED 票**的 approvedBy，不是当前操作人；
 *       否则会签里"谁驳的"会随投票顺序漂移。</li>
 *   <li>并行任务在 V1 下不允许驳回（PARALLEL_REJECT_DISABLED），这个检查要排在"是否本人任务"之前：
 *       错误码优先级是外部契约，调换会改变客户端提示。</li>
 * </ol>
 */
final class ApprovalOrchestrator {

    private final EngineSupport support;
    private final TaskLocker taskLocker;
    private final FieldEditApplier fieldEditApplier;
    private final NodeLander nodeLander;
    private final LegacyDecisionSettler legacySettler;
    private final V2DecisionSettler v2Settler;
    private final TaskMapper taskMapper;
    private final Supplier<WorkflowRuntimeV2> runtimeV2;

    ApprovalOrchestrator(EngineSupport support, TaskLocker taskLocker,
                         FieldEditApplier fieldEditApplier, NodeLander nodeLander,
                         LegacyDecisionSettler legacySettler, V2DecisionSettler v2Settler,
                         TaskMapper taskMapper, Supplier<WorkflowRuntimeV2> runtimeV2) {
        this.support = support;
        this.taskLocker = taskLocker;
        this.fieldEditApplier = fieldEditApplier;
        this.nodeLander = nodeLander;
        this.legacySettler = legacySettler;
        this.v2Settler = v2Settler;
        this.taskMapper = taskMapper;
        this.runtimeV2 = runtimeV2;
    }

    /**
     * @param historyAction 非空时覆盖历史 action（用于把"系统代批"与人工通过区分开）。
     *                      审计口径见 code-review A9：approved_by 仍是审批人本人，
     *                      但历史里能明确看到这是超时自动通过、不是人工点的。
     */
    void approve(CompleteCmd cmd, long operatorId, boolean force, String historyAction) {
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        LockedTask locked = taskLocker.lockPendingTask(cmd.taskId());
        TaskEntity t = locked.task();
        ProcessInstance pi = locked.instance();
        if ("REWORK".equals(t.getTaskType())) {
            throw new BizException("BAD_TASK_TYPE", "Rework task must be resubmitted from the form");
        }
        if (!force && !Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }
        taskLocker.skipDelegatedParentIfNeeded(t, operatorId);

        // 永远走快照，不依赖 pd.getProcess()（避免流程改版后已发起的实例跑飞）
        JsonNode root = support.readProcessTree(pi);
        JsonNode cur = ProcessTreeNav.findById(root, t.getNodeId());
        if (cur == null) {
            throw new BizException("BAD_FLOW", "approval node not in tree: " + t.getNodeId());
        }
        if (cmd.data() != null) {
            fieldEditApplier.applyNodeFieldEdits(pi, cur, cmd.data(), operatorId);
        }

        t.setStatus("APPROVED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        if (v2 != null && v2.active(pi)) {
            t.setActionFormRevisionId(pi.getCurrentFormRevisionId());
        }
        taskLocker.updateTaskOrConflict(t);
        support.insertHistory(t, null, t.getNodeId(),
            historyAction != null ? historyAction : force ? "FORCE_APPROVE" : "APPROVE",
            operatorId, cmd.comment());

        if (v2 != null && v2.active(pi) && t.getNodeInstanceId() != null) {
            if ("ADD_BEFORE".equals(t.getOperationKind())) {
                v2.completeBeforeSign(t);
                return;
            }
            if (v2.activateAfterSign(t)) return;
            WorkflowRuntimeV2.Decision decision = v2.approve(t, cur);
            if (!decision.newTaskIds().isEmpty()) {
                nodeLander.notifyAssigned(pi.getId(), decision.newTaskIds());
            }
            v2Settler.settleV2Decision(decision, cmd, operatorId, force, t, pi, root, cur);
            return;
        }

        String mode = WorkflowRuntimeV2.mode(cur);
        if ("ALL".equals(mode) || "RATIO".equals(mode)) {
            List<TaskEntity> votes = legacySettler.legacyNodeVotes(t);
            if (votes.stream().anyMatch(task -> "PENDING".equals(task.getStatus()))) return;
            long approved = votes.stream()
                .filter(task -> "APPROVED".equals(task.getStatus())).count();
            boolean passed = "ALL".equals(mode)
                ? approved == votes.size()
                : approved >= LegacyDecisionSettler.requiredApprovals(votes.size(), cur);
            if (!passed) {
                TaskEntity rejection = votes.stream()
                    .filter(task -> "REJECTED".equals(task.getStatus()))
                    .findFirst().orElse(t);
                legacySettler.settleLegacyRejection(
                    new CompleteCmd(rejection.getId(), "REJECT", rejection.getComment(), null),
                    rejection.getApprovedBy() == null ? operatorId : rejection.getApprovedBy(),
                    force, rejection, pi, root);
                return;
            }
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
                support.insertHistoryOnInstance(pi.getId(), t.getNodeId(), sib.getNodeId(),
                    "SKIP", operatorId, "OR-sign short-circuit");
            }
        }

        JsonNode formData = support.readFormData(pi.getFormDataId());
        if (t.getParallelId() != null) {
            nodeLander.advanceParallelBranch(root, pi, formData, t.getParallelId(),
                t.getBranchId(), cur);
            return;
        }
        // 仅首轮 start 时传入过 selfSelected；后续（理论上不会出现）传空 map。
        nodeLander.resolveAndLand(root, pi, formData, pi.getStartedBy(), Map.of(), cur);
    }

    void reject(CompleteCmd cmd, long operatorId, boolean force) {
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        LockedTask locked = taskLocker.lockPendingTask(cmd.taskId());
        TaskEntity t = locked.task();
        ProcessInstance pi = locked.instance();
        if ("REWORK".equals(t.getTaskType())) {
            throw new BizException("BAD_TASK_TYPE", "Rework task cannot be rejected as approval");
        }
        if (!force && t.getParallelId() != null
            && (v2 == null || !v2.active(pi))) {
            throw new BizException("PARALLEL_REJECT_DISABLED", "并行审批任务不允许驳回");
        }
        if (!force && !Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }

        JsonNode root = support.readProcessTree(pi);
        JsonNode cur = ProcessTreeNav.findById(root, t.getNodeId());
        if (cur == null) {
            throw new BizException("BAD_FLOW", "current node not in tree: " + t.getNodeId());
        }

        t.setStatus("REJECTED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        if (v2 != null && v2.active(pi)) {
            t.setActionFormRevisionId(pi.getCurrentFormRevisionId());
        }
        taskLocker.updateTaskOrConflict(t);
        support.insertHistory(t, null, t.getNodeId(), force ? "FORCE_REJECT_VOTE" : "REJECT_VOTE",
            operatorId, cmd.comment());

        if (v2 != null && v2.active(pi) && t.getNodeInstanceId() != null) {
            WorkflowRuntimeV2.Decision decision = t.getOperationKind() != null
                && t.getOperationKind().startsWith("ADD_")
                ? v2.rejectAdditional(t)
                : v2.rejectVote(t, cur);
            v2Settler.settleV2Decision(decision, cmd, operatorId, force, t, pi, root, cur);
            return;
        }

        String mode = WorkflowRuntimeV2.mode(cur);
        if (!force && ("ALL".equals(mode) || "RATIO".equals(mode))) {
            List<TaskEntity> votes = legacySettler.legacyNodeVotes(t);
            if (votes.stream().anyMatch(task -> "PENDING".equals(task.getStatus()))) return;
            long approved = votes.stream()
                .filter(task -> "APPROVED".equals(task.getStatus())).count();
            if ("RATIO".equals(mode) && approved >= LegacyDecisionSettler.requiredApprovals(votes.size(), cur)) {
                JsonNode formData = support.readFormData(pi.getFormDataId());
                nodeLander.resolveAndLand(root, pi, formData, pi.getStartedBy(), Map.of(), cur);
                return;
            }
            TaskEntity rejection = votes.stream()
                .filter(task -> "REJECTED".equals(task.getStatus()))
                .findFirst().orElse(t);
            legacySettler.settleLegacyRejection(
                new CompleteCmd(rejection.getId(), "REJECT", rejection.getComment(), null),
                rejection.getApprovedBy() == null ? operatorId : rejection.getApprovedBy(),
                false, rejection, pi, root);
            return;
        }
        legacySettler.settleLegacyRejection(cmd, operatorId, force, t, pi, root);
    }
}

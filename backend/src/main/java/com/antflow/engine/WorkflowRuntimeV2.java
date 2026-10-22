package com.antflow.engine;

import com.antflow.engine.handler.NodeContext;
import com.antflow.form.runtime.FormData;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.process.ProcessDefinition;
import com.antflow.automation.WorkflowJob;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * V2 运行时（{@code engine_version = 2}）的**门面**。
 *
 * <p>本类只做转发，不再写任何 SQL：实现分别落在实例生命周期、节点实例状态、决策计算、并行分支、
 * 审批人解析、任务参与六簇协作者里（见 {@code docs/class-decomposition-process-engine-2026-10-22.md}
 * 第七节）。保留这个门面是因为 {@code ProcessEngine} 的 13 个协作者、{@code task} 与 {@code mobile}
 * 两个包共 46 个调用点都按名调它——**公开签名一个都不能改**。
 *
 * <p>{@code t_process_instance} 的原始 SQL 写入只剩 {@link ProcessInstanceWriter#repointCurrentNode}
 * 一处；门面里出现 {@code jdbc.update} 就说明拆分纪律被破坏了。
 */
@Service
public class WorkflowRuntimeV2 {
    /** 共享底座：事务守卫 / V2 判定 / 版本与 Schema 读取 / outbox / 节点锁。 */
    private final V2RuntimeSupport support;
    /** 节点实例状态机（进入/完成/有效性校验/网关定位）。 */
    private final NodeInstanceState nodeState;
    /** 审批人/抄送人解析与任务绑定。 */
    private final ApprovalParticipants participants;
    /** 审批模式（ANY/ALL/RATIO/SEQUENTIAL）的票数与决策。 */
    private final DecisionCalculator decisions;
    /** 并行分支的注册、join 与跨分支清理。 */
    private final ParallelBranchRuntime parallel;
    /** 任务级协作动作（抄送/超时/加签/改派）。 */
    private final TaskParticipationOps participation;
    /** 实例级生命周期（轮次/终止/撤回/失效/追回）。 */
    private final InstanceLifecycleOps lifecycle;

    /**
     * 显式构造器（原 Lombok {@code @RequiredArgsConstructor}）：协作者不是 bean，
     * 必须在构造期按依赖顺序装配；Spring 仍然只注入这三个既有 bean。
     */
    public WorkflowRuntimeV2(JdbcTemplate jdbc, DefinitionVersionRepository versions,
                             ObjectMapper json) {
        this.support = new V2RuntimeSupport(jdbc, versions);
        this.nodeState = new NodeInstanceState(support, jdbc);
        this.participants = new ApprovalParticipants(jdbc, json);
        this.decisions = new DecisionCalculator(support, nodeState, jdbc, json);
        this.parallel = new ParallelBranchRuntime(support, nodeState, jdbc);
        this.participation = new TaskParticipationOps(support, jdbc);
        this.lifecycle = new InstanceLifecycleOps(support, new ProcessInstanceWriter(jdbc),
            jdbc, versions);
    }

    public boolean active(ProcessInstance instance) {
        return V2RuntimeSupport.isV2(instance);
    }

    public String processTree(ProcessInstance instance) {
        return support.processTree(instance);
    }

    public String formSchema(ProcessInstance instance) {
        return support.formSchema(instance);
    }

    public StartState prepareStart(FormData data, ProcessDefinition process, long actorId) {
        return lifecycle.prepareStart(data, process, actorId);
    }

    public long enterNode(ProcessInstance instance, JsonNode node, NodeContext context) {
        return nodeState.enterNode(instance, node, context);
    }

    public void completeNode(long nodeInstanceId, String status) {
        nodeState.completeNode(nodeInstanceId, status);
    }

    public List<Assignment> assignments(ProcessInstance instance, JsonNode node,
                                        NodeContext context, List<Long> responsibleUsers,
                                        String source) {
        return participants.assignments(instance, node, context, responsibleUsers, source);
    }

    /** Resolves the task recipients that would be activated without writing runtime state. */
    public List<Assignment> previewAssignments(long formDefId, JsonNode node,
                                               List<Long> responsibleUsers) {
        return participants.previewAssignments(formDefId, node, responsibleUsers);
    }

    public void bindTask(TaskEntity task, NodeContext context, Assignment assignment,
                         ProcessInstance instance, JsonNode node) {
        participants.bindTask(task, context, assignment, instance, node);
    }

    public void scheduleTimeout(TaskEntity task, JsonNode node) {
        participants.scheduleTimeout(task, node);
    }

    public List<Long> fieldUsers(JsonNode node, NodeContext context) {
        return participants.fieldUsers(node, context);
    }

    /** Resolves non-blocking CC recipients; invalid or unavailable values intentionally disappear. */
    public List<Long> ccUsers(JsonNode node, NodeContext context) {
        return participants.ccUsers(node, context);
    }

    public List<Long> fallbackUsers(JsonNode root, JsonNode node) {
        return participants.fallbackUsers(root, node);
    }

    public BizException fallbackUnavailable(JsonNode node) {
        return participants.fallbackUnavailable(node);
    }

    public boolean requiresNodeFallbackPolicy(JsonNode root) {
        return participants.requiresNodeFallbackPolicy(root);
    }

    public boolean shouldAutoPass(JsonNode root, ProcessInstance instance, List<Long> users) {
        return participants.shouldAutoPass(root, instance, users);
    }

    public boolean shouldAutoPassPreview(JsonNode root, long starterId, List<Long> users) {
        return participants.shouldAutoPassPreview(root, starterId, users);
    }

    public boolean shouldSkipResubmittedNode(JsonNode root, ProcessInstance instance,
                                             JsonNode node) {
        return participants.shouldSkipResubmittedNode(root, instance, node);
    }

    /** Mirrors DIFF_CONTINUE before the candidate rework revision is persisted. */
    public boolean shouldSkipResubmittedNodePreview(JsonNode root, ProcessInstance instance,
                                                    JsonNode node, JsonNode candidateData) {
        return participants.shouldSkipResubmittedNodePreview(root, instance, node, candidateData);
    }

    public Decision approve(TaskEntity task, JsonNode node) {
        return decisions.approve(task, node);
    }

    public Decision rejectVote(TaskEntity task, JsonNode node) {
        return decisions.rejectVote(task, node);
    }

    public Decision rejectAdditional(TaskEntity task) {
        return decisions.rejectAdditional(task);
    }

    public void outbox(long instanceId, Long recipientId, String type, Long taskId) {
        support.outbox(instanceId, recipientId, type, taskId);
    }

    public void recordCc(ProcessInstance instance, Long nodeInstanceId, long recipientId) {
        participation.recordCc(instance, nodeInstanceId, recipientId);
    }

    public void registerParallelBranch(ProcessInstance instance, Long gatewayNodeInstanceId,
                                       String branchId) {
        parallel.registerParallelBranch(instance, gatewayNodeInstanceId, branchId);
    }

    public boolean parallelBranchPassed(ProcessInstance instance, JsonNode root,
                                        String parallelId, String branchId) {
        return parallel.parallelBranchPassed(instance, root, parallelId, branchId);
    }

    public ParallelDecision parallelBranchRejected(TaskEntity task, ProcessInstance instance,
                                                     JsonNode root) {
        return parallel.parallelBranchRejected(task, instance, root);
    }

    public long createRevision(FormData data, String status, String reason, long actorId) {
        return support.createRevision(data, status, reason, actorId);
    }

    public void timeoutReminder(WorkflowJob job, TaskEntity task) {
        participation.timeoutReminder(job, task);
    }

    public void timeoutEscalate(WorkflowJob job, TaskEntity task) {
        participation.timeoutEscalate(job, task);
    }

    public void copyRuntimeTask(TaskEntity parent, TaskEntity child) {
        participation.copyRuntimeTask(parent, child);
    }

    public void reassignParticipant(TaskEntity parent, long actualUserId) {
        participation.reassignParticipant(parent, actualUserId);
    }

    public void requireActiveAssignee(long userId) {
        participation.requireActiveAssignee(userId);
    }

    public void requireActiveTask(TaskEntity task, ProcessInstance instance) {
        nodeState.requireActiveTask(task, instance);
    }

    public void recallChild(TaskEntity child, TaskEntity parent) {
        participation.recallChild(child, parent);
    }

    public int addParticipant(TaskEntity parent, long userId, boolean before) {
        return participation.addParticipant(parent, userId, before);
    }

    public boolean activateAfterSign(TaskEntity parent) {
        return participation.activateAfterSign(parent);
    }

    public void completeBeforeSign(TaskEntity child) {
        participation.completeBeforeSign(child);
    }

    public void recallApproval(TaskEntity task, ProcessInstance instance, long operatorId) {
        lifecycle.recallApproval(task, instance, operatorId);
    }

    public void adminReassign(TaskEntity task, long targetUserId, long operatorId,
                              String reason) {
        participation.adminReassign(task, targetUserId, operatorId, reason);
    }

    public void terminate(ProcessInstance instance, long operatorId, String reason) {
        lifecycle.terminate(instance, operatorId, reason);
    }

    /**
     * 撤回后的 V2 收尾：本轮仍 ACTIVE 的节点、WAITING/PENDING 的参与者、以及分支状态一并置 CANCELLED。
     *
     * <p>为什么必须做：{@code withdraw} 原来只把任务置 SKIPPED，节点与参与者留在 ACTIVE/PENDING——
     * 按参与者聚合的"在办/超时"统计会长期偏高，审批人侧也看不到任务是怎么消失的（见 code-review A3）。
     * {@code beginRound} 只在重提时收口节点、且不处理参与者，不能靠它兜底。
     */
    public void withdrawToRework(ProcessInstance instance) {
        lifecycle.withdrawToRework(instance);
    }

    /**
     * 撤回时被跳过的那条任务：补一条 CANCEL 历史 + outbox。
     * 原来只把 status 改成 SKIPPED，审批人侧任务凭空消失、也没有任何记录。
     */
    public void taskSkippedByWithdraw(ProcessInstance instance, TaskEntity task) {
        lifecycle.taskSkippedByWithdraw(instance, task);
    }

    public void beginRound(ProcessInstance instance, String reason) {
        lifecycle.beginRound(instance, reason);
    }

    public void invalidateApprovedNodes(ProcessInstance instance, List<String> nodeIds,
                                        String reason) {
        lifecycle.invalidateApprovedNodes(instance, nodeIds, reason);
    }

    public static String mode(JsonNode node) {
        return NodePolicies.mode(node);
    }

    public record StartState(long processVersionId, long revisionId) { }
    public record Assignment(long responsibleUserId, long actualUserId, int sequenceNo) { }
    public record Decision(boolean advance, boolean reject, List<Long> newTaskIds) {
        static Decision advanceNode() { return new Decision(true, false, List.of()); }
        static Decision rejectNode() { return new Decision(false, true, List.of()); }
        static Decision waitFor(List<Long> ids) { return new Decision(false, false, ids); }
    }
    public enum ParallelDecision { WAIT, ADVANCE, REJECT }
}

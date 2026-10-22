package com.antflow.engine;

import com.antflow.automation.WorkflowJob;
import com.antflow.automation.WorkflowJobMapper;
import com.antflow.engine.handler.NodeContext;
import com.antflow.engine.handler.NodeHandler;
import com.antflow.engine.handler.NodeOutcome;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.notify.NotificationEvent;
import com.antflow.notify.NotificationPublisher;
import com.antflow.task.ProcessInstance;
import com.antflow.task.ProcessInstanceMapper;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 节点落地：从"刚完成的节点"沿流程树往前走，直到落到需要建任务的 APPROVAL 节点、走完末端、或实例结束。
 *
 * <p>为什么整组搬迁：{@code resolveAndLand} / {@code resolveAndLandFromNode} / {@code advanceParallelBranch}
 * 共用同一个 {@code pickHandler} + {@link NodeOutcome} 循环，还共用 {@code notifyAssigned}。
 * 只搬一半会出现"落地逻辑在两处、handler 解析在两处"——第一次改节点类型就会漏改其中一边。
 *
 * <p>五个易错点：
 * <ol>
 *   <li>**不能加 {@code @Transactional}**：所有入口（start / approve / 结算 / 自动化）都已经在事务里。
 *       这里再开一层会让"节点推进 + 任务创建"与外层分成两段提交。</li>
 *   <li>{@code resolveAndLandFromNode} 是从 target **自身**开始推进（驳回到节点专用），
 *       fromNode 传 null；{@code resolveAndLand} 则是从 {@code next()} 开始。两者不能合并。</li>
 *   <li>节点类型认不出来时**必须抛错**（{@code BAD_NODE_TYPE}），不能当 END 跳过——
 *       跳过等于静默把流程走完。</li>
 *   <li>并行分支走完后先查"本并行组是否还有 PENDING / 活跃阻塞作业"，再决定是否汇合；
 *       漏掉 {@code hasActiveParallelAutomation} 会让延时/触发器节点的分支提前汇合。</li>
 *   <li>通知事件的 source 现在是本类实例（原来是 ProcessEngine）。source 只被
 *       {@code ApplicationEvent} 持有，全仓没有读取方；类型、接收人、文案都没变。</li>
 * </ol>
 */
final class NodeLander {

    private final EngineSupport support;
    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final WorkflowJobMapper workflowJobMapper;
    private final List<NodeHandler> nodeHandlers;
    private final NotificationPublisher notifier;
    private final Supplier<WorkflowRuntimeV2> runtimeV2;

    NodeLander(EngineSupport support, TaskMapper taskMapper,
               ProcessInstanceMapper processInstanceMapper, WorkflowJobMapper workflowJobMapper,
               List<NodeHandler> nodeHandlers, NotificationPublisher notifier,
               Supplier<WorkflowRuntimeV2> runtimeV2) {
        this.support = support;
        this.taskMapper = taskMapper;
        this.processInstanceMapper = processInstanceMapper;
        this.workflowJobMapper = workflowJobMapper;
        this.nodeHandlers = nodeHandlers;
        this.notifier = notifier;
        this.runtimeV2 = runtimeV2;
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
    List<Long> resolveAndLand(JsonNode root, ProcessInstance pi,
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
    List<Long> resolveAndLandFromNode(JsonNode root, ProcessInstance pi,
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
        // runtimeV2 是字段注入 + 测试会在构造后替换，所以取当前值（只取一次，方法体内语义等价）。
        WorkflowRuntimeV2 v2 = runtimeV2.get();
        JsonNode node = startNode;
        NodeContext ctx = new NodeContext(starterId, formData, selfSelected, fromNode == null ? null : fromNode.path("id").asText(null), null, null);
        while (true) {
            if (node == null) {
                // 末端 → 实例 APPROVED
                pi.setStatus("APPROVED");
                pi.setFinishedAt(OffsetDateTime.now());
                pi.setCurrentNodeId(null);
                processInstanceMapper.updateById(pi);
                support.insertHistoryOnInstance(pi.getId(),
                    fromNode == null ? null : fromNode.path("id").asText(null),
                    null, "COMPLETE", pi.getStartedBy(), null);
                if (v2 != null && v2.active(pi)) {
                    v2.outbox(pi.getId(), pi.getStartedBy(), "INSTANCE_APPROVED", null);
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
            long nodeInstanceId = v2 == null ? 0 : v2.enterNode(pi, node, ctx);
            NodeOutcome outcome = handler.handle(root, node, pi,
                nodeInstanceId == 0 ? ctx : ctx.atNode(nodeInstanceId));
            switch (outcome.type()) {
                case NEXT:
                    if (v2 != null) v2.completeNode(nodeInstanceId, "PASSED");
                    String nextFromId = node.path("id").asText(null);
                    fromNode = node;
                    node = outcome.node();
                    ctx = new NodeContext(starterId, formData, selfSelected, nextFromId, null, null);
                    continue;
                case JUMP:
                    if (v2 != null) v2.completeNode(nodeInstanceId, "PASSED");
                    String jumpFromId = node.path("id").asText(null);
                    fromNode = node;
                    node = outcome.node();
                    ctx = new NodeContext(starterId, formData, selfSelected, jumpFromId, null, null);
                    continue;
                case END:
                    if (v2 != null) v2.completeNode(nodeInstanceId, "PASSED");
                    processInstanceMapper.updateById(pi);
                    support.insertHistoryOnInstance(pi.getId(), node.path("id").asText(null),
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

    void advanceParallelBranch(JsonNode root, ProcessInstance instance,
                               JsonNode formData, String parallelId,
                               String branchId, JsonNode completedNode) {
        WorkflowRuntimeV2 v2 = runtimeV2.get();
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
            long nodeInstanceId = v2 == null ? 0
                : v2.enterNode(instance, handled, context);
            NodeOutcome outcome = handler.handle(root, handled, instance,
                nodeInstanceId == 0 ? context : context.atNode(nodeInstanceId));
            if (outcome.type() == NodeOutcome.Type.HALT) {
                if (outcome instanceof NodeOutcome.Halt halt) {
                    notifyAssigned(instance.getId(), halt.newTaskIds());
                }
                return;
            }
            if (v2 != null) v2.completeNode(nodeInstanceId, "PASSED");
            if (outcome.type() == NodeOutcome.Type.END) return;
            node = outcome.node();
            context = new NodeContext(
                instance.getStartedBy(), formData, Map.of(), handled.path("id").asText(null),
                parallelId, branchId
            );
        }

        if (v2 != null && v2.active(instance)) {
            v2.parallelBranchPassed(instance, root, parallelId, branchId);
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

    void notifyAssigned(Long instanceId, List<Long> taskIds) {
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
}

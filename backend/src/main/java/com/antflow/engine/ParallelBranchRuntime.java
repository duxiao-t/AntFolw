package com.antflow.engine;

import com.antflow.engine.WorkflowRuntimeV2.ParallelDecision;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并行分支（{@code t_parallel_branch_state}）的注册、收口与跨分支清理。
 *
 * <p>为什么单独一簇：并行 join 的规则（ALL 等齐 / ANY 先到先过）与串行审批互不影响，
 * 混在决策类里会让"这一票是不是在分支里"这类判断散落。
 *
 * <p>四个易错点：
 * <ol>
 *   <li>**ANY 先完成时要取消其它分支的"人"**：任务、参与者、分支状态、节点实例四张表都要清，
 *       只改分支状态会留下永远无法处理的 PENDING 任务（{@code cancelGatewayWork} 里逐张表处理）。</li>
 *   <li>**{@code branch_id IS DISTINCT FROM ?}** 而不是 {@code <>}：分支 id 可能为 null（网关自身），
 *       用 {@code <>} 会把 null 行漏掉。</li>
 *   <li>**ALL 模式下最后一个分支通过时才完成网关**：{@code branchCounts(...).active() == 0} 才
 *       {@code completeNode}，提前完成会让后续分支的 {@code enterNode} 找不到 ACTIVE 网关。</li>
 *   <li>**驳回时"已有人通过"算 ADVANCE 而不是 REJECT**：ANY join 的语义是"有人过就算过"，
 *       这里返回 ADVANCE 让结算方继续推进，不要改成 REJECT。</li>
 * </ol>
 */
final class ParallelBranchRuntime {

    private final V2RuntimeSupport support;
    private final NodeInstanceState nodeState;
    private final JdbcTemplate jdbc;

    ParallelBranchRuntime(V2RuntimeSupport support, NodeInstanceState nodeState,
                          JdbcTemplate jdbc) {
        this.support = support;
        this.nodeState = nodeState;
        this.jdbc = jdbc;
    }

    void registerParallelBranch(ProcessInstance instance, Long gatewayNodeInstanceId,
                                String branchId) {
        if (!V2RuntimeSupport.isV2(instance) || gatewayNodeInstanceId == null) return;
        jdbc.update("""
            INSERT INTO t_parallel_branch_state(gateway_node_instance_id, branch_id)
            VALUES (?, ?) ON CONFLICT DO NOTHING
            """, gatewayNodeInstanceId, branchId);
    }

    boolean parallelBranchPassed(ProcessInstance instance, JsonNode root,
                                 String parallelId, String branchId) {
        V2RuntimeSupport.requireTransaction();
        if (!V2RuntimeSupport.isV2(instance)) return false;
        Long gatewayId = nodeState.gatewayNodeInstance(instance.getId(), parallelId,
            instance.getRoundNo());
        if (gatewayId == null) return false;
        support.lockNode(gatewayId);
        jdbc.update("""
            UPDATE t_parallel_branch_state
            SET status = 'PASSED', completed_at = now()
            WHERE gateway_node_instance_id = ? AND branch_id = ? AND status = 'ACTIVE'
            """, gatewayId, branchId);
        JsonNode gateway = ProcessTreeNav.findById(root, parallelId);
        if (!"ANY".equals(NodePolicies.joinMode(gateway))) {
            if (branchCounts(gatewayId).active() == 0) nodeState.completeNode(gatewayId, "PASSED");
            return false;
        }
        cancelGatewayWork(instance.getId(), gatewayId, parallelId, branchId,
            "parallel ANY branch completed", false);
        nodeState.completeNode(gatewayId, "PASSED");
        return true;
    }

    ParallelDecision parallelBranchRejected(TaskEntity task, ProcessInstance instance,
                                            JsonNode root) {
        V2RuntimeSupport.requireTransaction();
        if (!V2RuntimeSupport.isV2(instance) || task.getParallelId() == null) return ParallelDecision.REJECT;
        Long gatewayId = nodeState.gatewayNodeInstance(instance.getId(), task.getParallelId(),
            instance.getRoundNo());
        if (gatewayId == null) return ParallelDecision.REJECT;
        support.lockNode(gatewayId);
        jdbc.update("""
            UPDATE t_parallel_branch_state
            SET status = 'REJECTED', completed_at = now()
            WHERE gateway_node_instance_id = ? AND branch_id = ? AND status = 'ACTIVE'
            """, gatewayId, task.getBranchId());
        JsonNode gateway = ProcessTreeNav.findById(root, task.getParallelId());
        if (!"ANY".equals(NodePolicies.joinMode(gateway))) {
            cancelGatewayWork(instance.getId(), gatewayId, task.getParallelId(),
                task.getBranchId(), "parallel ALL rejected", true);
            nodeState.completeNode(gatewayId, "REJECTED");
            return ParallelDecision.REJECT;
        }
        BranchCounts counts = branchCounts(gatewayId);
        if (counts.passed() > 0) return ParallelDecision.ADVANCE;
        return counts.active() > 0 ? ParallelDecision.WAIT : ParallelDecision.REJECT;
    }

    private BranchCounts branchCounts(long gatewayId) {
        return jdbc.query("""
            SELECT COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active,
                   COUNT(*) FILTER (WHERE status = 'PASSED') AS passed,
                   COUNT(*) FILTER (WHERE status = 'REJECTED') AS rejected
            FROM t_parallel_branch_state WHERE gateway_node_instance_id = ?
            """, rs -> rs.next()
                ? new BranchCounts(rs.getInt("active"), rs.getInt("passed"), rs.getInt("rejected"))
                : new BranchCounts(0, 0, 0), gatewayId);
    }

    private void cancelGatewayWork(long instanceId, long gatewayId, String parallelId,
                                   String exceptBranchId, String reason,
                                   boolean invalidateApproved) {
        List<TaskRow> cancelled = jdbc.query("""
            UPDATE t_task SET status = 'CANCELLED', comment = COALESCE(comment, ?)
            WHERE proc_inst_id = ? AND parallel_id = ? AND status = 'PENDING'
              AND branch_id IS DISTINCT FROM ?
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
            reason, instanceId, parallelId, exceptBranchId);
        for (TaskRow task : cancelled) {
            jdbc.update("""
                INSERT INTO t_task_history(proc_inst_id, task_id, action, comment)
                VALUES (?, ?, 'CANCEL', ?)
                """, instanceId, task.id(), reason);
            support.outbox(instanceId, task.assigneeId(), "TASK_CANCELLED", task.id());
        }
        if (invalidateApproved) {
            List<TaskRow> invalidated = jdbc.query("""
                UPDATE t_task SET operation_kind = 'INVALIDATED'
                WHERE proc_inst_id = ? AND parallel_id = ? AND status = 'APPROVED'
                  AND branch_id IS DISTINCT FROM ?
                RETURNING id, assignee_id
                """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
                instanceId, parallelId, exceptBranchId);
            for (TaskRow task : invalidated) {
                jdbc.update("""
                    INSERT INTO t_task_history(proc_inst_id, task_id, action, comment)
                    VALUES (?, ?, 'INVALIDATE', ?)
                    """, instanceId, task.id(), reason);
                support.outbox(instanceId, task.assigneeId(), "APPROVAL_INVALIDATED", task.id());
            }
        }
        jdbc.update("""
            UPDATE t_parallel_branch_state SET status = 'CANCELLED', completed_at = now()
            WHERE gateway_node_instance_id = ? AND status IN ('ACTIVE', 'PASSED')
              AND branch_id IS DISTINCT FROM ?
            """, gatewayId, exceptBranchId);
        jdbc.update("""
            UPDATE t_process_node_instance SET status = 'CANCELLED', completed_at = now()
            WHERE gateway_node_instance_id = ? AND status IN ('ACTIVE', 'PASSED')
              AND branch_id IS DISTINCT FROM ?
            """, gatewayId, exceptBranchId);
        jdbc.update("""
            UPDATE t_node_participant participant SET status = 'CANCELLED'
            FROM t_process_node_instance node
            WHERE participant.node_instance_id = node.id
              AND node.gateway_node_instance_id = ?
              AND node.branch_id IS DISTINCT FROM ?
              AND participant.status IN ('WAITING', 'PENDING', 'APPROVED')
            """, gatewayId, exceptBranchId);
    }

    private record BranchCounts(int active, int passed, int rejected) { }
}

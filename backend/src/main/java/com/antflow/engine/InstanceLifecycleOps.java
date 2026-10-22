package com.antflow.engine;

import com.antflow.engine.WorkflowRuntimeV2.StartState;
import com.antflow.form.runtime.FormData;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.process.ProcessDefinition;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 实例级生命周期：发起准备、轮次切换、终止、撤回收口、节点失效、追回。
 *
 * <p>为什么单独一簇：这一组动作改变的是"这次流程走到哪一轮、还活着吗"，影响面是整个实例；
 * 而节点状态/决策/并行只影响一次审批动作。混在一起时，"终止要不要清分支状态"这类问题
 * 每次都要在 1200 行里找。
 *
 * <p>五个易错点：
 * <ol>
 *   <li>**终止与撤回都要清"参与者"**，不只是任务与节点：按参与者聚合的在办/超时统计会长期偏高，
 *       审批人侧也看不到任务是怎么消失的（见 code-review A3）。</li>
 *   <li>**{@code beginRound} 不处理参与者**：它只收口节点与分支、把轮次 +1；
 *       参与者由 {@code withdrawToRework} 单独清理，两者不要合并。</li>
 *   <li>**追回的条件是"下一节点还没动"**：{@code created_at > approved_at} 的任务里有
 *       APPROVED/REJECTED/RESUBMITTED 就直接拒绝，不能用"是否有 PENDING"来判断。</li>
 *   <li>**追回要同时把实例当前节点指回去**，且这是全仓唯一一条写主表的原始 SQL——
 *       走 {@link ProcessInstanceWriter}，不要在这里内联。</li>
 *   <li>**{@code invalidateApprovedNodes} 的 IN 占位符是字符串拼接的**（值仍走参数绑定），
 *       节点数量来自流程树，不是用户输入；不要把它改成拼接值。</li>
 * </ol>
 */
final class InstanceLifecycleOps {

    private final V2RuntimeSupport support;
    private final ProcessInstanceWriter instanceWriter;
    private final JdbcTemplate jdbc;
    private final DefinitionVersionRepository versions;

    InstanceLifecycleOps(V2RuntimeSupport support, ProcessInstanceWriter instanceWriter,
                         JdbcTemplate jdbc, DefinitionVersionRepository versions) {
        this.support = support;
        this.instanceWriter = instanceWriter;
        this.jdbc = jdbc;
        this.versions = versions;
    }

    StartState prepareStart(FormData data, ProcessDefinition process, long actorId) {
        long revisionId = versions.createRevision(data, "SUBMITTED", "INITIAL_SUBMIT", actorId);
        long processVersionId = versions.processVersionId(process.getId(), process.getVersion());
        return new StartState(processVersionId, revisionId);
    }

    void terminate(ProcessInstance instance, long operatorId, String reason) {
        V2RuntimeSupport.requireTransaction();
        List<TaskRow> cancelled = jdbc.query("""
            UPDATE t_task SET status = 'CANCELLED', comment = ?
            WHERE proc_inst_id = ? AND status IN ('PENDING', 'BLOCKED')
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
            reason, instance.getId());
        jdbc.update("""
            UPDATE t_process_node_instance SET status = 'CANCELLED', completed_at = now()
            WHERE proc_inst_id = ? AND status = 'ACTIVE'
            """, instance.getId());
        jdbc.update("""
            UPDATE t_node_participant participant SET status = 'CANCELLED'
            FROM t_process_node_instance node
            WHERE participant.node_instance_id = node.id AND node.proc_inst_id = ?
              AND participant.status IN ('WAITING', 'PENDING')
            """, instance.getId());
        jdbc.update("""
            UPDATE t_workflow_job SET status = 'CANCELLED', completed_at = now(),
                                      locked_at = NULL, locked_by = NULL
            WHERE proc_inst_id = ? AND status IN ('SCHEDULED', 'RUNNING')
            """, instance.getId());
        cancelled.forEach(task -> support.outbox(instance.getId(), task.assigneeId(),
            "TASK_CANCELLED", task.id()));
        jdbc.update("""
            INSERT INTO t_task_history(proc_inst_id, action, operator_id, comment)
            VALUES (?, 'ADMIN_TERMINATE', ?, ?)
            """, instance.getId(), operatorId, reason);
        support.outbox(instance.getId(), instance.getStartedBy(), "INSTANCE_REJECTED", null);
    }

    void withdrawToRework(ProcessInstance instance) {
        V2RuntimeSupport.requireTransaction();
        if (!V2RuntimeSupport.isV2(instance)) return;
        jdbc.update("""
            UPDATE t_process_node_instance
            SET status = 'CANCELLED', completed_at = now(), version = version + 1
            WHERE proc_inst_id = ? AND status = 'ACTIVE'
            """, instance.getId());
        jdbc.update("""
            UPDATE t_parallel_branch_state branch SET status = 'CANCELLED', completed_at = now()
            FROM t_process_node_instance gateway
            WHERE branch.gateway_node_instance_id = gateway.id
              AND gateway.proc_inst_id = ? AND branch.status = 'ACTIVE'
            """, instance.getId());
        jdbc.update("""
            UPDATE t_node_participant participant SET status = 'CANCELLED'
            FROM t_process_node_instance node
            WHERE participant.node_instance_id = node.id
              AND node.proc_inst_id = ?
              AND participant.status IN ('WAITING', 'PENDING')
            """, instance.getId());
    }

    void taskSkippedByWithdraw(ProcessInstance instance, TaskEntity task) {
        V2RuntimeSupport.requireTransaction();
        if (!V2RuntimeSupport.isV2(instance)) return;
        jdbc.update("""
            INSERT INTO t_task_history(proc_inst_id, task_id, from_node_id, to_node_id,
                                       action, comment)
            VALUES (?, ?, ?, '__rework__', 'CANCEL', ?)
            """, instance.getId(), task.getId(), task.getNodeId(), "发起人撤回");
        support.outbox(instance.getId(), task.getAssigneeId(), "TASK_CANCELLED", task.getId());
    }

    void beginRound(ProcessInstance instance, String reason) {
        V2RuntimeSupport.requireTransaction();
        if (!V2RuntimeSupport.isV2(instance)) return;
        jdbc.update("""
            UPDATE t_process_node_instance
            SET status = 'CANCELLED', completed_at = now(), version = version + 1
            WHERE proc_inst_id = ? AND status = 'ACTIVE'
            """, instance.getId());
        jdbc.update("""
            UPDATE t_parallel_branch_state branch SET status = 'CANCELLED', completed_at = now()
            FROM t_process_node_instance gateway
            WHERE branch.gateway_node_instance_id = gateway.id
              AND gateway.proc_inst_id = ? AND branch.status = 'ACTIVE'
            """, instance.getId());
        instance.setRoundNo((instance.getRoundNo() == null ? 1 : instance.getRoundNo()) + 1);
        instance.setCurrentNodeInstanceId(null);
        support.outbox(instance.getId(), instance.getStartedBy(), "ROUND_STARTED", null);
    }

    void invalidateApprovedNodes(ProcessInstance instance, List<String> nodeIds,
                                        String reason) {
        if (!V2RuntimeSupport.isV2(instance) || nodeIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(nodeIds.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(instance.getId());
        arguments.add(instance.getRoundNo());
        arguments.addAll(nodeIds);
        List<TaskRow> invalidated = jdbc.query("""
            UPDATE t_task task SET operation_kind = 'INVALIDATED'
            FROM t_process_node_instance node
            WHERE task.node_instance_id = node.id AND task.proc_inst_id = ?
              AND node.round_no = ? AND task.status = 'APPROVED'
              AND task.node_id IN (
            """ + placeholders + ") RETURNING task.id, task.assignee_id",
            (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)), arguments.toArray());
        for (TaskRow task : invalidated) {
            jdbc.update("""
                INSERT INTO t_task_history(proc_inst_id, task_id, action, comment)
                VALUES (?, ?, 'INVALIDATE', ?)
                """, instance.getId(), task.id(), reason);
            support.outbox(instance.getId(), task.assigneeId(), "APPROVAL_INVALIDATED", task.id());
        }
    }

    void recallApproval(TaskEntity task, ProcessInstance instance, long operatorId) {
        V2RuntimeSupport.requireTransaction();
        if (!V2RuntimeSupport.isV2(instance) || task.getNodeInstanceId() == null || task.getParallelId() != null) {
            throw new BizException("BAD_RECALL_STATE", "仅支持追回 V2 串行审批节点");
        }
        support.lockNode(task.getNodeInstanceId());
        Integer acted = jdbc.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND created_at > ?
              AND status IN ('APPROVED', 'REJECTED', 'RESUBMITTED')
            """, Integer.class, task.getProcInstId(), task.getApprovedAt());
        if (acted != null && acted > 0) {
            throw new BizException("RECALL_TOO_LATE", "下一节点已处理，无法追回");
        }
        List<TaskRow> downstream = jdbc.query("""
            UPDATE t_task SET status = 'CANCELLED', comment = 'upstream approval recalled'
            WHERE proc_inst_id = ? AND created_at > ? AND status = 'PENDING'
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
            task.getProcInstId(), task.getApprovedAt());
        jdbc.update("""
            UPDATE t_process_node_instance SET status = 'CANCELLED', completed_at = now()
            WHERE proc_inst_id = ? AND started_at > (
              SELECT completed_at FROM t_process_node_instance WHERE id = ?
            ) AND status = 'ACTIVE'
            """, task.getProcInstId(), task.getNodeInstanceId());
        jdbc.update("""
            UPDATE t_process_node_instance
            SET status = 'ACTIVE', completed_at = NULL, version = version + 1
            WHERE id = ? AND status IN ('PASSED', 'AUTO_PASSED')
            """, task.getNodeInstanceId());
        jdbc.update("""
            UPDATE t_node_participant SET status = 'PENDING'
            WHERE node_instance_id = ? AND sequence_no = ? AND status = 'APPROVED'
            """, task.getNodeInstanceId(), task.getSequenceNo());
        jdbc.update("""
            UPDATE t_task SET status = 'PENDING', approved_by = NULL, approved_at = NULL,
                              comment = NULL, operation_kind = 'RECALLED_APPROVAL'
            WHERE id = ? AND status = 'APPROVED'
            """, task.getId());
        // 全仓唯一一条写 t_process_instance 的原始 SQL，收口在 ProcessInstanceWriter。
        instanceWriter.repointCurrentNode(task.getProcInstId(), task.getNodeId(),
            task.getNodeInstanceId());
        for (TaskRow row : downstream) {
            jdbc.update("""
                INSERT INTO t_task_history(proc_inst_id, task_id, action, comment)
                VALUES (?, ?, 'CANCEL', 'upstream approval recalled')
                """, task.getProcInstId(), row.id());
            support.outbox(task.getProcInstId(), row.assigneeId(), "TASK_CANCELLED", row.id());
        }
        jdbc.update("""
            INSERT INTO t_task_history(proc_inst_id, task_id, from_node_id, to_node_id,
                                       action, operator_id, comment)
            VALUES (?, ?, ?, ?, 'RECALL_APPROVAL', ?, 'approval recalled')
            """, task.getProcInstId(), task.getId(), task.getNodeId(), task.getNodeId(), operatorId);
        support.outbox(task.getProcInstId(), task.getAssigneeId(), "TASK_ASSIGNED", task.getId());
    }
}

package com.antflow.engine;

import com.antflow.automation.WorkflowJob;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 任务级协作动作：抄送记录、超时提醒/升级、加签（前/后）与撤回、管理改派、参与者改派。
 *
 * <p>为什么单独一簇：这些动作都围绕"一条 t_task 的接单人变化"，与节点状态机、票数决策无关；
 * 它们的调用方也集中在 {@code task.TaskOperationService} 与 {@code AutomationCompleter}。
 *
 * <p>四个易错点：
 * <ol>
 *   <li>**升级失败要退化成提醒**：没有上级或上级就是本人时，不能静默什么都不做——
 *       任务会一直挂在原审批人那里直到超时作业重试耗尽。</li>
 *   <li>**前加签先把父任务置 BLOCKED**：撤回时要把父任务恢复 PENDING、父参与者恢复 PENDING，
 *       只删子参与者会让父任务永远卡在 BLOCKED（见 code-review A7）。</li>
 *   <li>**{@code addParticipant} 返回的是新 sequence_no**：调用方拿它写子任务，
 *       返回 1 表示"没有节点实例"（V1 或未落节点），不是失败。</li>
 *   <li>**要求目标用户 ACTIVE 只在这里校验**：本类不知道调用方是管理端还是自动化，
 *       校验放在最靠近写库的位置，避免漏掉某条入口。</li>
 * </ol>
 */
final class TaskParticipationOps {

    private final V2RuntimeSupport support;
    private final JdbcTemplate jdbc;

    TaskParticipationOps(V2RuntimeSupport support, JdbcTemplate jdbc) {
        this.support = support;
        this.jdbc = jdbc;
    }

    void recordCc(ProcessInstance instance, Long nodeInstanceId, long recipientId) {
        if (!V2RuntimeSupport.isV2(instance)) return;
        int created = jdbc.update("""
            INSERT INTO t_cc_record(proc_inst_id, node_instance_id, recipient_id)
            VALUES (?, ?, ?) ON CONFLICT (node_instance_id, recipient_id) DO NOTHING
            """, instance.getId(), nodeInstanceId, recipientId);
        if (created == 0) return;
        int roundNo = instance.getRoundNo() == null ? 1 : instance.getRoundNo();
        int claimed = jdbc.update("""
            INSERT INTO t_cc_notification_batch(proc_inst_id, round_no, recipient_id)
            VALUES (?, ?, ?) ON CONFLICT (proc_inst_id, round_no, recipient_id) DO NOTHING
            """, instance.getId(), roundNo, recipientId);
        if (claimed > 0) support.outbox(instance.getId(), recipientId, "CC_ASSIGNED", null, roundNo);
    }

    void timeoutReminder(WorkflowJob job, TaskEntity task) {
        support.outbox(task.getProcInstId(), task.getAssigneeId(), "TASK_TIMEOUT_REMINDER", task.getId());
    }

    void timeoutEscalate(WorkflowJob job, TaskEntity task) {
        Long manager = jdbc.query("""
            SELECT manager.id FROM t_user assignee
            JOIN t_user manager ON manager.id = assignee.manager_id AND manager.status = 'ACTIVE'
            WHERE assignee.id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, task.getAssigneeId());
        if (manager == null || Objects.equals(manager, task.getAssigneeId())) {
            timeoutReminder(job, task);
            return;
        }
        int cancelled = jdbc.update("""
            UPDATE t_task SET status = 'CANCELLED', comment = 'timeout escalated'
            WHERE id = ? AND status = 'PENDING'
            """, task.getId());
        if (cancelled == 0) return;
        jdbc.update("""
            UPDATE t_node_participant SET actual_user_id = ?
            WHERE node_instance_id = ? AND actual_user_id = ? AND sequence_no = ?
              AND status = 'PENDING'
            """, manager, task.getNodeInstanceId(), task.getAssigneeId(), task.getSequenceNo());
        Long newTaskId = jdbc.query("""
            INSERT INTO t_task(
                proc_inst_id, node_instance_id, node_id, assignee_id, task_type,
                status, approval_mode, parent_task_id, delegated_from, sequence_no,
                operation_kind, action_form_revision_id, parallel_id, branch_id)
            VALUES (?, ?, ?, ?, 'APPROVAL', 'PENDING', ?, ?, ?, ?,
                    'TIMEOUT_ESCALATION', ?, ?, ?)
            RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null,
            task.getProcInstId(), task.getNodeInstanceId(), task.getNodeId(), manager,
            task.getApprovalMode(), task.getId(), task.getAssigneeId(), task.getSequenceNo(),
            task.getActionFormRevisionId(), task.getParallelId(), task.getBranchId());
        jdbc.update("""
            INSERT INTO t_task_history(proc_inst_id, task_id, from_node_id, to_node_id,
                                       action, comment)
            VALUES (?, ?, ?, ?, 'TIMEOUT_ESCALATE', ?)
            """, task.getProcInstId(), task.getId(), task.getNodeId(), task.getNodeId(),
            "escalated to user " + manager);
        support.outbox(task.getProcInstId(), task.getAssigneeId(), "TASK_CANCELLED", task.getId());
        support.outbox(task.getProcInstId(), manager, "TASK_ASSIGNED", newTaskId);
    }

    void copyRuntimeTask(TaskEntity parent, TaskEntity child) {
        if (parent.getNodeInstanceId() == null) return;
        child.setNodeInstanceId(parent.getNodeInstanceId());
        child.setActionFormRevisionId(parent.getActionFormRevisionId());
        child.setSequenceNo(parent.getSequenceNo());
    }

    void reassignParticipant(TaskEntity parent, long actualUserId) {
        if (parent.getNodeInstanceId() == null) return;
        jdbc.update("""
            UPDATE t_node_participant SET actual_user_id = ?
            WHERE node_instance_id = ? AND sequence_no = ? AND status = 'PENDING'
            """, actualUserId, parent.getNodeInstanceId(), parent.getSequenceNo());
    }

    void requireActiveAssignee(long userId) {
        Long active = jdbc.query("SELECT id FROM t_user WHERE id = ? AND status = 'ACTIVE'",
            rs -> rs.next() ? rs.getLong(1) : null, userId);
        if (active == null) throw new BizException("BAD_ASSIGNEE", "target user is not active");
    }

    void recallChild(TaskEntity child, TaskEntity parent) {
        if (parent == null || !Objects.equals(parent.getNodeInstanceId(), child.getNodeInstanceId())) {
            throw new BizException("BAD_RECALL_STATE", "子任务与父任务不匹配");
        }
        if ("ADD_BEFORE".equals(child.getOperationKind())
            || "ADD_AFTER".equals(child.getOperationKind())) {
            if (!List.of("PENDING", "BLOCKED").contains(parent.getStatus())) {
                throw new BizException("BAD_RECALL_STATE", "原审批已处理，不能撤回加签");
            }
            jdbc.update("""
                UPDATE t_node_participant SET status = 'CANCELLED'
                WHERE node_instance_id = ? AND sequence_no = ? AND status IN ('PENDING', 'WAITING')
                """, child.getNodeInstanceId(), child.getSequenceNo());
            if ("ADD_BEFORE".equals(child.getOperationKind())) {
                jdbc.update("UPDATE t_task SET status = 'PENDING' WHERE id = ? AND status = 'BLOCKED'",
                    parent.getId());
                jdbc.update("""
                    UPDATE t_node_participant SET status = 'PENDING'
                    WHERE node_instance_id = ? AND sequence_no = ? AND status = 'WAITING'
                    """, parent.getNodeInstanceId(), parent.getSequenceNo());
                support.outbox(parent.getProcInstId(), parent.getAssigneeId(), "TASK_ASSIGNED", parent.getId());
            }
        } else {
            reassignParticipant(parent, parent.getAssigneeId());
        }
        support.outbox(child.getProcInstId(), child.getAssigneeId(), "TASK_CANCELLED", child.getId());
    }

    int addParticipant(TaskEntity parent, long userId, boolean before) {
        if (parent.getNodeInstanceId() == null) return 1;
        Integer sequence = jdbc.queryForObject("""
            SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM t_node_participant
            WHERE node_instance_id = ?
            """, Integer.class, parent.getNodeInstanceId());
        int value = sequence == null ? 1 : sequence;
        jdbc.update("""
            INSERT INTO t_node_participant(
                node_instance_id, responsible_user_id, actual_user_id,
                sequence_no, status, source)
            VALUES (?, ?, ?, ?, ?, 'ADD_SIGN')
            """, parent.getNodeInstanceId(), userId, userId, value,
            before ? "PENDING" : "WAITING");
        if (before) {
            jdbc.update("""
                UPDATE t_node_participant SET status = 'WAITING'
                WHERE node_instance_id = ? AND sequence_no = ? AND status = 'PENDING'
                """, parent.getNodeInstanceId(), parent.getSequenceNo());
        }
        return value;
    }

    boolean activateAfterSign(TaskEntity parent) {
        if (parent.getNodeInstanceId() == null) return false;
        List<TaskRow> children = jdbc.query("""
            UPDATE t_task SET status = 'PENDING'
            WHERE parent_task_id = ? AND operation_kind = 'ADD_AFTER' AND status = 'BLOCKED'
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)), parent.getId());
        if (children.isEmpty()) return false;
        jdbc.update("""
            UPDATE t_node_participant SET status = 'APPROVED'
            WHERE node_instance_id = ? AND sequence_no = ? AND status = 'PENDING'
            """, parent.getNodeInstanceId(), parent.getSequenceNo());
        jdbc.update("""
            UPDATE t_node_participant participant SET status = 'PENDING'
            FROM t_task task
            WHERE task.parent_task_id = ? AND task.operation_kind = 'ADD_AFTER'
              AND participant.node_instance_id = task.node_instance_id
              AND participant.sequence_no = task.sequence_no
              AND participant.status = 'WAITING'
            """, parent.getId());
        children.forEach(child -> support.outbox(parent.getProcInstId(), child.assigneeId(),
            "TASK_ASSIGNED", child.id()));
        return true;
    }

    void completeBeforeSign(TaskEntity child) {
        if (child.getNodeInstanceId() == null) return;
        jdbc.update("""
            UPDATE t_node_participant SET status = 'APPROVED'
            WHERE node_instance_id = ? AND sequence_no = ? AND status = 'PENDING'
            """, child.getNodeInstanceId(), child.getSequenceNo());
        List<TaskRow> parents = jdbc.query("""
            UPDATE t_task SET status = 'PENDING'
            WHERE id = ? AND status = 'BLOCKED'
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
            child.getParentTaskId());
        jdbc.update("""
            UPDATE t_node_participant SET status = 'PENDING'
            WHERE node_instance_id = ? AND sequence_no = (
              SELECT sequence_no FROM t_task WHERE id = ?
            ) AND status = 'WAITING'
            """, child.getNodeInstanceId(), child.getParentTaskId());
        parents.forEach(parent -> support.outbox(child.getProcInstId(), parent.assigneeId(),
            "TASK_ASSIGNED", parent.id()));
    }

    void adminReassign(TaskEntity task, long targetUserId, long operatorId,
                              String reason) {
        V2RuntimeSupport.requireTransaction();
        requireActiveAssignee(targetUserId);
        long previous = task.getAssigneeId();
        jdbc.update("""
            UPDATE t_task SET assignee_id = ?, delegated_from = ?,
                              operation_kind = 'ADMIN_REASSIGN', comment = ?
            WHERE id = ? AND status = 'PENDING'
            """, targetUserId, previous, reason, task.getId());
        if (task.getNodeInstanceId() != null) {
            jdbc.update("""
                UPDATE t_node_participant SET actual_user_id = ?
                WHERE node_instance_id = ? AND actual_user_id = ? AND sequence_no = ?
                  AND status = 'PENDING'
                """, targetUserId, task.getNodeInstanceId(), previous, task.getSequenceNo());
        }
        jdbc.update("""
            INSERT INTO t_task_history(proc_inst_id, task_id, from_node_id, to_node_id,
                                       action, operator_id, comment)
            VALUES (?, ?, ?, ?, 'ADMIN_REASSIGN', ?, ?)
            """, task.getProcInstId(), task.getId(), task.getNodeId(), task.getNodeId(),
            operatorId, reason);
        support.outbox(task.getProcInstId(), previous, "TASK_CANCELLED", task.getId());
        support.outbox(task.getProcInstId(), targetUserId, "TASK_ASSIGNED", task.getId());
    }
}

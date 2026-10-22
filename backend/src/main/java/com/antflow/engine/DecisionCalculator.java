package com.antflow.engine;

import com.antflow.engine.WorkflowRuntimeV2.Decision;
import com.antflow.task.TaskEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审批决策计算：把"这一票投完，节点该前进还是驳回"算出来。
 *
 * <p>为什么单独一簇：ANY / ALL / RATIO / SEQUENTIAL 四种模式的票数规则是本类变化最频繁的部分，
 * 而它不负责"决策落地"（推进、历史、实例状态都在 {@code V2DecisionSettler} 与 {@code NodeLander}）。
 *
 * <p>四个易错点：
 * <ol>
 *   <li>**先锁节点再改参与者**：{@code lockNode} 是这一簇唯一防并发双投的手段，
 *       顺序反了会出现两次 {@code updated != 1} 之外的竞态。</li>
 *   <li>**SEQUENTIAL 的"还有下一人"必须先激活新任务再返回 WAIT**：返回 WAIT 但没建任务，
 *       节点就永久停住（历史 bug）。</li>
 *   <li>**RATIO 的驳回判定是"没达到通过线"**，不是"有人驳回"：{@code rejectVote} 里
 *       {@code passed = RATIO && approved >= required}，其它模式一律 false。</li>
 *   <li>**顺序节点的超时策略读的是进入节点时的 {@code policy_snapshot}**，不是当前流程定义——
 *       流程改过之后，老节点仍按当时的策略计时。</li>
 * </ol>
 *
 * <p>注意：返回值是门面的 {@code Decision} record（外部契约），本类不持有 {@code WorkflowRuntimeV2}
 * 实例，只依赖它的类型。
 */
@Slf4j
final class DecisionCalculator {

    private final V2RuntimeSupport support;
    private final NodeInstanceState nodeState;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    DecisionCalculator(V2RuntimeSupport support, NodeInstanceState nodeState,
                       JdbcTemplate jdbc, ObjectMapper json) {
        this.support = support;
        this.nodeState = nodeState;
        this.jdbc = jdbc;
        this.json = json;
    }

    Decision approve(TaskEntity task, JsonNode node) {
        V2RuntimeSupport.requireTransaction();
        if (task.getNodeInstanceId() == null) return Decision.advanceNode();
        support.lockNode(task.getNodeInstanceId());
        int updated = jdbc.update("""
            UPDATE t_node_participant SET status = 'APPROVED'
            WHERE node_instance_id = ? AND actual_user_id = ? AND sequence_no = ?
              AND status = 'PENDING'
            """, task.getNodeInstanceId(), task.getAssigneeId(), task.getSequenceNo());
        if (updated != 1) {
            throw new BizException("BAD_TASK_STATE", "审批参与人状态已变更，请刷新");
        }
        Counts counts = counts(task.getNodeInstanceId());
        String mode = NodePolicies.mode(node);
        if ("SEQUENTIAL".equals(mode) && counts.waiting() > 0) {
            Long nextTask = activateNext(task);
            return Decision.waitFor(nextTask == null ? List.of() : List.of(nextTask));
        }
        if ("ANY".equals(mode) || "SEQUENTIAL".equals(mode)) {
            cancelPending(task, "decision completed");
            nodeState.completeNode(task.getNodeInstanceId(), "PASSED");
            return Decision.advanceNode();
        }
        if (counts.pending() > 0 || counts.waiting() > 0) {
            return Decision.waitFor(List.of());
        }
        boolean passed = "RATIO".equals(mode)
            ? counts.approved() >= NodePolicies.required(counts.total(), node)
            : counts.rejected() == 0;
        nodeState.completeNode(task.getNodeInstanceId(), passed ? "PASSED" : "REJECTED");
        return passed ? Decision.advanceNode() : Decision.rejectNode();
    }

    Decision rejectVote(TaskEntity task, JsonNode node) {
        V2RuntimeSupport.requireTransaction();
        if (task.getNodeInstanceId() == null) return Decision.rejectNode();
        support.lockNode(task.getNodeInstanceId());
        int updated = jdbc.update("""
            UPDATE t_node_participant SET status = 'REJECTED'
            WHERE node_instance_id = ? AND actual_user_id = ? AND sequence_no = ?
              AND status = 'PENDING'
            """, task.getNodeInstanceId(), task.getAssigneeId(), task.getSequenceNo());
        if (updated != 1) {
            throw new BizException("BAD_TASK_STATE", "审批参与人状态已变更，请刷新");
        }
        Counts counts = counts(task.getNodeInstanceId());
        String mode = NodePolicies.mode(node);
        if ("ANY".equals(mode) || "SEQUENTIAL".equals(mode)) {
            cancelPending(task, "node rejected");
            nodeState.completeNode(task.getNodeInstanceId(), "REJECTED");
            return Decision.rejectNode();
        }
        if (counts.pending() > 0 || counts.waiting() > 0) {
            return Decision.waitFor(List.of());
        }
        boolean passed = "RATIO".equals(mode)
            && counts.approved() >= NodePolicies.required(counts.total(), node);
        nodeState.completeNode(task.getNodeInstanceId(), passed ? "PASSED" : "REJECTED");
        return passed ? Decision.advanceNode() : Decision.rejectNode();
    }

    Decision rejectAdditional(TaskEntity task) {
        if (task.getNodeInstanceId() == null) return Decision.rejectNode();
        support.lockNode(task.getNodeInstanceId());
        int updated = jdbc.update("""
            UPDATE t_node_participant SET status = 'REJECTED'
            WHERE node_instance_id = ? AND actual_user_id = ? AND sequence_no = ?
              AND status IN ('PENDING', 'WAITING')
            """, task.getNodeInstanceId(), task.getAssigneeId(), task.getSequenceNo());
        if (updated != 1) {
            throw new BizException("BAD_TASK_STATE", "审批参与人状态已变更，请刷新");
        }
        cancelPending(task, "additional reviewer rejected");
        nodeState.completeNode(task.getNodeInstanceId(), "REJECTED");
        return Decision.rejectNode();
    }

    private Long activateNext(TaskEntity previous) {
        Participant next = jdbc.query("""
            SELECT responsible_user_id, actual_user_id, sequence_no
            FROM t_node_participant
            WHERE node_instance_id = ? AND status = 'WAITING'
            ORDER BY sequence_no LIMIT 1
            FOR UPDATE
            """, rs -> rs.next() ? participant(rs) : null, previous.getNodeInstanceId());
        if (next == null) return null;
        jdbc.update("""
            UPDATE t_node_participant SET status = 'PENDING'
            WHERE node_instance_id = ? AND sequence_no = ?
            """, previous.getNodeInstanceId(), next.sequenceNo());
        Long id = jdbc.query("""
            INSERT INTO t_task(
                proc_inst_id, node_instance_id, node_id, assignee_id, task_type,
                status, approval_mode, delegated_from, sequence_no,
                action_form_revision_id, parallel_id, branch_id)
            VALUES (?, ?, ?, ?, 'APPROVAL', 'PENDING', 'SEQUENTIAL', ?, ?, ?, ?, ?)
            RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null,
            previous.getProcInstId(), previous.getNodeInstanceId(), previous.getNodeId(),
            next.actualUserId(), next.actualUserId() == next.responsibleUserId()
                ? null : next.responsibleUserId(), next.sequenceNo(),
            previous.getActionFormRevisionId(), previous.getParallelId(), previous.getBranchId());
        if (id != null) {
            support.outbox(previous.getProcInstId(), next.actualUserId(), "TASK_ASSIGNED", id);
            scheduleSequentialTimeout(previous, id);
        }
        return id;
    }

    private void scheduleSequentialTimeout(TaskEntity previous, long taskId) {
        String props = jdbc.query("""
            SELECT policy_snapshot::text FROM t_process_node_instance WHERE id = ?
            """, rs -> rs.next() ? rs.getString(1) : null, previous.getNodeInstanceId());
        try {
            JsonNode policy = json.readTree(props == null ? "{}" : props).path("timeoutPolicy");
            long minutes = policy.path("afterMinutes").asLong(0);
            if (minutes <= 0) return;
            OffsetDateTime due = OffsetDateTime.now().plusMinutes(minutes);
            jdbc.update("UPDATE t_task SET timeout_at = ? WHERE id = ?", due, taskId);
            jdbc.update("""
                INSERT INTO t_workflow_job(
                    proc_inst_id, task_id, node_instance_id, node_id, job_type,
                    action_key, scheduled_at, status, attempts, max_attempts,
                    payload, blocking)
                VALUES (?, ?, ?, ?, 'TASK_TIMEOUT', ?, ?, 'SCHEDULED', 0, 8, ?::jsonb, false)
                ON CONFLICT (task_id, action_key) WHERE job_type = 'TASK_TIMEOUT' DO NOTHING
                """, previous.getProcInstId(), taskId, previous.getNodeInstanceId(),
                previous.getNodeId(), policy.path("action").asText("REMIND"), due,
                policy.toString());
        } catch (Exception error) {
            // 原来这里静默吞掉：t_task.timeout_at 已写、作业却没建，任务永不提醒/升级且无痕迹。
            // 保持"坏策略不阻断审批"的取舍，但要留日志 + 一条历史，便于事后追。
            log.warn("schedule timeout for task {} failed: {}", taskId, error.toString());
            insertTimeoutUnavailableHistory(previous, error);
        }
    }

    /** 超时作业没能建出来时留一条历史（吞异常不再等于无痕迹）。 */
    private void insertTimeoutUnavailableHistory(TaskEntity task, Exception error) {
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (reason.length() > 200) reason = reason.substring(0, 200);
        jdbc.update("""
            INSERT INTO t_task_history(proc_inst_id, task_id, from_node_id,
                                       to_node_id, action, comment)
            VALUES (?, ?, ?, ?, 'TIMEOUT_UNAVAILABLE', ?)
            """, task.getProcInstId(), task.getId(), task.getNodeId(), task.getNodeId(),
            "超时作业未创建：" + reason);
    }

    private void cancelPending(TaskEntity acted, String reason) {
        // 含 BLOCKED：前加签会把父任务置 BLOCKED，只清 PENDING 会让它永久卡住
        // （既不在待办、也无法操作，见 code-review A7）。
        List<TaskRow> cancelled = jdbc.query("""
            UPDATE t_task SET status = 'CANCELLED', comment = COALESCE(comment, ?)
            WHERE node_instance_id = ? AND status IN ('PENDING', 'BLOCKED') AND id <> ?
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
            reason, acted.getNodeInstanceId(), acted.getId());
        jdbc.update("""
            UPDATE t_node_participant SET status = 'CANCELLED'
            WHERE node_instance_id = ? AND status IN ('WAITING', 'PENDING')
            """, acted.getNodeInstanceId());
        for (TaskRow task : cancelled) {
            jdbc.update("""
                INSERT INTO t_task_history(proc_inst_id, task_id, from_node_id,
                                           to_node_id, action, comment)
                VALUES (?, ?, ?, ?, 'CANCEL', ?)
                """, acted.getProcInstId(), task.id(), acted.getNodeId(), acted.getNodeId(), reason);
            support.outbox(acted.getProcInstId(), task.assigneeId(), "TASK_CANCELLED", task.id());
        }
    }

    private Counts counts(long nodeInstanceId) {
        return jdbc.query("""
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE status = 'APPROVED') AS approved,
                   COUNT(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                   COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                   COUNT(*) FILTER (WHERE status = 'WAITING') AS waiting
            FROM t_node_participant WHERE node_instance_id = ? AND status <> 'CANCELLED'
            """, rs -> rs.next() ? new Counts(rs.getInt("total"), rs.getInt("approved"),
                rs.getInt("rejected"), rs.getInt("pending"), rs.getInt("waiting"))
                : new Counts(0, 0, 0, 0, 0), nodeInstanceId);
    }

    private static Participant participant(ResultSet rs) throws SQLException {
        return new Participant(rs.getLong("responsible_user_id"), rs.getLong("actual_user_id"),
            rs.getInt("sequence_no"));
    }

    private record Participant(long responsibleUserId, long actualUserId, int sequenceNo) { }
    private record Counts(int total, int approved, int rejected, int pending, int waiting) { }
}

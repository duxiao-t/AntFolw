package com.antflow.engine;

import com.antflow.engine.handler.NodeContext;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 节点实例（{@code t_process_node_instance}）的显式状态迁移。
 *
 * <p>为什么单独一簇：{@code enterNode}/{@code completeNode} 是 V2 状态机唯一允许的"进入/完成"写法，
 * 决策计算和并行分支都会调它们；如果它们留在门面里，另外两簇就得反向依赖门面。
 *
 * <p>三个易错点：
 * <ol>
 *   <li>**进入是幂等的**：同一 {@code (proc_inst_id, node_id, round_no)} 已有 ACTIVE 行时直接复用，
 *       否则并行分支重入会造出两条 ACTIVE 节点，join 永远等不齐。</li>
 *   <li>**完成只对 ACTIVE 生效**：{@code completeNode} 里 {@code WHERE status = 'ACTIVE'}，
 *       重复完成返回 0 行是**正常**的（自动化与人工可能都来一次），不要改成断言失败。</li>
 *   <li>**status 是外部传进来的字符串**（PASSED / AUTO_PASSED / REJECTED），这里不做白名单校验——
 *       历史数据里存在只被 V1 写过的取值，拦下来会让老实例无法收口。</li>
 * </ol>
 */
final class NodeInstanceState {

    private final V2RuntimeSupport support;
    private final JdbcTemplate jdbc;

    NodeInstanceState(V2RuntimeSupport support, JdbcTemplate jdbc) {
        this.support = support;
        this.jdbc = jdbc;
    }

    long enterNode(ProcessInstance instance, JsonNode node, NodeContext context) {
        V2RuntimeSupport.requireTransaction();
        if (!V2RuntimeSupport.isV2(instance)) return 0;
        String nodeId = node.path("id").asText();
        Long existing = jdbc.query("""
            SELECT id FROM t_process_node_instance
            WHERE proc_inst_id = ? AND node_id = ? AND round_no = ? AND status = 'ACTIVE'
            ORDER BY attempt_no DESC LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null,
            instance.getId(), nodeId, instance.getRoundNo());
        if (existing != null) return existing;
        Integer attempt = jdbc.queryForObject("""
            SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM t_process_node_instance
            WHERE proc_inst_id = ? AND node_id = ?
            """, Integer.class, instance.getId(), nodeId);
        Long gatewayId = context.parallelId() == null ? null : jdbc.query("""
            SELECT id FROM t_process_node_instance
            WHERE proc_inst_id = ? AND node_id = ? AND round_no = ? AND status = 'ACTIVE'
            ORDER BY attempt_no DESC LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null,
            instance.getId(), context.parallelId(), instance.getRoundNo());
        Long id = jdbc.query("""
            INSERT INTO t_process_node_instance(
                proc_inst_id, node_id, node_type, round_no, attempt_no,
                gateway_node_instance_id, branch_id, status, policy_snapshot,
                form_revision_id_at_enter)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?::jsonb, ?)
            RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null,
            instance.getId(), nodeId, node.path("type").asText(), instance.getRoundNo(),
            attempt == null ? 1 : attempt, gatewayId, context.branchId(),
            node.path("props").isObject() ? node.path("props").toString() : "{}",
            instance.getCurrentFormRevisionId());
        if (id == null) throw new BizException("NODE_STATE_FAILED", "node instance was not created");
        instance.setCurrentNodeInstanceId(id);
        support.outbox(instance.getId(), null, "NODE_ENTERED", null);
        return id;
    }

    void completeNode(long nodeInstanceId, String status) {
        V2RuntimeSupport.requireTransaction();
        if (nodeInstanceId <= 0) return;
        int updated = jdbc.update("""
            UPDATE t_process_node_instance
            SET status = ?, completed_at = now(), version = version + 1
            WHERE id = ? AND status = 'ACTIVE'
            """, status, nodeInstanceId);
        if (updated > 0) {
            Long instanceId = jdbc.query("""
                SELECT proc_inst_id FROM t_process_node_instance WHERE id = ?
                """, rs -> rs.next() ? rs.getLong(1) : null, nodeInstanceId);
            if (instanceId != null) support.outbox(instanceId, null, "NODE_COMPLETED", null);
        }
    }

    /** V2 拒绝"任务还在、节点已死"的操作；REWORK 任务走另一条判定（节点 id 是 {@code __rework__}）。 */
    void requireActiveTask(TaskEntity task, ProcessInstance instance) {
        if (!V2RuntimeSupport.isV2(instance)) return;
        if ("REWORK".equals(task.getTaskType())) {
            if (!"__rework__".equals(instance.getCurrentNodeId())
                || !"__rework__".equals(task.getNodeId())
                || !Objects.equals(task.getAssigneeId(), instance.getStartedBy())) {
                throw new BizException("BAD_TASK_STATE", "待修改任务不属于当前流程阶段");
            }
            return;
        }
        Long node = jdbc.query("""
            SELECT id FROM t_process_node_instance
            WHERE id = ? AND proc_inst_id = ? AND node_id = ? AND round_no = ? AND status = 'ACTIVE'
            """, rs -> rs.next() ? rs.getLong(1) : null, task.getNodeInstanceId(),
            instance.getId(), task.getNodeId(), instance.getRoundNo());
        if (node == null) throw new BizException("BAD_TASK_STATE", "审批节点已失效，请刷新");
    }

    /** 当前轮次的网关节点实例；找不到返回 null（并行分支可能已被 ANY 提前收口）。 */
    Long gatewayNodeInstance(long instanceId, String parallelId, int roundNo) {
        return jdbc.query("""
            SELECT id FROM t_process_node_instance
            WHERE proc_inst_id = ? AND node_id = ? AND round_no = ?
              AND status = 'ACTIVE'
            ORDER BY attempt_no DESC LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null, instanceId, parallelId, roundNo);
    }
}

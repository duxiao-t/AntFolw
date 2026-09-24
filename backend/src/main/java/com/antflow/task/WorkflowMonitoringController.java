package com.antflow.task;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 运维视角：卡死、超时、驳回热点、兜底积压、事务消息积压。
 *
 * <p>{@code workflow:monitor:read} 在能力目录里是**可配范围**的（V40:59，default ALL），
 * 所以这五条查询必须自己按调用者的数据范围收窄——原来走的是裸 {@link JdbcTemplate}，
 * 能力里的范围完全没生效，任何拿到该能力的人（含 V40:132 从 workflow.instance.override
 * 映射过来的那批）看到的是全库。收窄口径统一落在**流程实例**上：发起人或发起部门，
 * 而不是任务的 assignee。
 */
@RestController
@RequestMapping("/api/workflow-monitor")
@RequiredArgsConstructor
public class WorkflowMonitoringController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    @GetMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.WORKFLOW_MONITOR_READ + "')")
    public Map<String, Object> overview(@RequestParam(defaultValue = "50") int limit) {
        authorization.requirePermission(PermissionCodes.WORKFLOW_MONITOR_READ);
        int safeLimit = Math.min(100, Math.max(1, limit));
        // 监控口径 = 流程实例的可见范围：发起人 / 发起部门。
        ScopeSql scope = instanceScope("instance");
        ScopeSql outboxScope = instanceScope("instance");

        List<Map<String, Object>> stuck = jdbc.queryForList("""
            SELECT instance.id, instance.current_node_id, instance.started_at
            FROM t_process_instance instance
            WHERE instance.status = 'RUNNING'
              AND NOT EXISTS (SELECT 1 FROM t_task task
                              WHERE task.proc_inst_id = instance.id
                                AND task.status IN ('PENDING', 'BLOCKED'))
              AND NOT EXISTS (SELECT 1 FROM t_workflow_job job
                              WHERE job.proc_inst_id = instance.id AND job.blocking = true
                                AND job.status IN ('SCHEDULED', 'RUNNING'))
              AND """ + scope.sql() + """
            ORDER BY instance.started_at LIMIT ?
            """, withLimit(scope.args(), safeLimit));

        // 超时任务按所属**实例**的发起人/发起部门收窄（不是 assignee——否则会漏掉跨部门代办）。
        List<Map<String, Object>> overdue = jdbc.queryForList("""
            SELECT task.id AS task_id, task.proc_inst_id AS instance_id, task.node_id,
                   task.assignee_id, task.timeout_at
            FROM t_task task
            JOIN t_process_instance instance ON instance.id = task.proc_inst_id
            WHERE task.status = 'PENDING' AND task.timeout_at < now()
              AND """ + scope.sql() + """
            ORDER BY task.timeout_at LIMIT ?
            """, withLimit(scope.args(), safeLimit));

        // 驳回率：过滤必须在 GROUP BY / 聚合之前，否则被排除的部门会算进分母。
        List<Map<String, Object>> rejectionRates = jdbc.queryForList("""
            SELECT task.node_id,
                   COUNT(*) FILTER (WHERE task.status = 'REJECTED') AS rejected,
                   COUNT(*) FILTER (WHERE task.status IN ('APPROVED', 'REJECTED')) AS decided,
                   ROUND(100.0 * COUNT(*) FILTER (WHERE task.status = 'REJECTED')
                     / NULLIF(COUNT(*) FILTER (
                         WHERE task.status IN ('APPROVED', 'REJECTED')), 0), 2) AS reject_rate
            FROM t_task task
            JOIN t_process_instance instance ON instance.id = task.proc_inst_id
            WHERE """ + scope.sql() + """
            GROUP BY task.node_id
            HAVING COUNT(*) FILTER (WHERE task.status IN ('APPROVED', 'REJECTED')) > 0
            ORDER BY reject_rate DESC NULLS LAST, decided DESC LIMIT ?
            """, withLimit(scope.args(), safeLimit));

        List<Map<String, Object>> fallbackBacklogs = jdbc.queryForList("""
            SELECT task.assignee_id,
                   COALESCE(user_row.display_name, user_row.username,
                            CONCAT('用户#', task.assignee_id)) AS assignee_name,
                   COUNT(*) AS pending_count,
                   MIN(task.created_at) AS oldest_pending_at,
                   COUNT(DISTINCT node.node_id) AS affected_node_count,
                   COUNT(DISTINCT task.proc_inst_id) AS affected_instance_count
            FROM t_task task
            JOIN t_process_instance instance ON instance.id = task.proc_inst_id
            JOIN t_process_node_instance node ON node.id = task.node_instance_id
            JOIN t_node_participant participant
              ON participant.node_instance_id = task.node_instance_id
             AND participant.actual_user_id = task.assignee_id
             AND participant.sequence_no = task.sequence_no
            LEFT JOIN t_user user_row ON user_row.id = task.assignee_id
            WHERE task.status = 'PENDING' AND participant.source LIKE 'FALLBACK%'
              AND """ + scope.sql() + """
            GROUP BY task.assignee_id, user_row.display_name, user_row.username
            ORDER BY pending_count DESC, oldest_pending_at LIMIT ?
            """, withLimit(scope.args(), safeLimit));

        // 事务消息只有 aggregate_type / aggregate_id，按「PROCESS_INSTANCE」关联到实例再收窄；
        // 其它聚合类型在受限范围下**显式排除**，免得将来新的写入方静默绕过范围。
        // 空范围时聚合天然给出 0 计数 + null 最早时间。
        Map<String, Object> outbox = jdbc.queryForMap("""
            SELECT COUNT(*) FILTER (WHERE outbox.status = 'PENDING') AS pending,
                   COUNT(*) FILTER (WHERE outbox.status = 'DEAD') AS dead,
                   MIN(outbox.created_at) FILTER (WHERE outbox.status = 'PENDING')
                       AS oldest_pending
            FROM t_workflow_outbox outbox
            JOIN t_process_instance instance
              ON outbox.aggregate_type = 'PROCESS_INSTANCE'
             AND instance.id = outbox.aggregate_id
            WHERE """ + outboxScope.sql(), outboxScope.args().toArray());

        return Map.of("stuckInstances", stuck, "overdueTasks", overdue,
            "nodeRejectionRates", rejectionRates, "fallbackBacklogs", fallbackBacklogs,
            "outbox", outbox);
    }

    /** 一段可直接拼进 WHERE 的谓词 + 它绑定的参数（参数顺序与占位符一致）。 */
    private record ScopeSql(String sql, List<Object> args) { }

    /**
     * 调用者在 {@code workflow:monitor:read} 上的行级谓词，作用在流程实例上。
     * 既不允许本人、也没有任何部门范围时返回恒假条件（fail-closed），不能退化成不过滤。
     */
    private ScopeSql instanceScope(String alias) {
        Optional<AuthorizationService.DataScopeFilter> current =
            authorization.currentDataScope(PermissionCodes.WORKFLOW_MONITOR_READ);
        // 空 = 没有请求主体（系统内部调用）；本端点是管理端接口，正常不会走到这里。
        if (current.isEmpty() || current.get().admin() || current.get().unrestricted()) {
            return new ScopeSql("1 = 1", List.of());
        }
        AuthorizationService.DataScopeFilter scope = current.get();
        List<String> parts = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (scope.selfAllowed()) {
            parts.add(alias + ".started_by = ?");
            args.add(scope.userId());
        }
        if (!scope.departmentIds().isEmpty()) {
            parts.add(alias + ".started_dept_id IN ("
                + String.join(",", Collections.nCopies(scope.departmentIds().size(), "?")) + ")");
            args.addAll(scope.departmentIds());
        }
        if (parts.isEmpty()) {
            return new ScopeSql("1 = 0", List.of());
        }
        return new ScopeSql("(" + String.join(" OR ", parts) + ")", List.copyOf(args));
    }

    private static Object[] withLimit(List<Object> args, int limit) {
        Object[] all = new Object[args.size() + 1];
        for (int i = 0; i < args.size(); i++) {
            all[i] = args.get(i);
        }
        all[args.size()] = limit;
        return all;
    }
}

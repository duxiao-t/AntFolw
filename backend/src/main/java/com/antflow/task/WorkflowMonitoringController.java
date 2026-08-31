package com.antflow.task;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Small operational view for stuck, overdue and rejection hot spots. */
@RestController
@RequestMapping("/api/workflow-monitor")
@RequiredArgsConstructor
public class WorkflowMonitoringController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    @GetMapping
    public Map<String, Object> overview(@RequestParam(defaultValue = "50") int limit) {
        authorization.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_OVERRIDE);
        int safeLimit = Math.min(100, Math.max(1, limit));
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
            ORDER BY instance.started_at LIMIT ?
            """, safeLimit);
        List<Map<String, Object>> overdue = jdbc.queryForList("""
            SELECT task.id AS task_id, task.proc_inst_id AS instance_id, task.node_id,
                   task.assignee_id, task.timeout_at
            FROM t_task task
            WHERE task.status = 'PENDING' AND task.timeout_at < now()
            ORDER BY task.timeout_at LIMIT ?
            """, safeLimit);
        List<Map<String, Object>> rejectionRates = jdbc.queryForList("""
            SELECT task.node_id,
                   COUNT(*) FILTER (WHERE task.status = 'REJECTED') AS rejected,
                   COUNT(*) FILTER (WHERE task.status IN ('APPROVED', 'REJECTED')) AS decided,
                   ROUND(100.0 * COUNT(*) FILTER (WHERE task.status = 'REJECTED')
                     / NULLIF(COUNT(*) FILTER (
                         WHERE task.status IN ('APPROVED', 'REJECTED')), 0), 2) AS reject_rate
            FROM t_task task
            GROUP BY task.node_id
            HAVING COUNT(*) FILTER (WHERE task.status IN ('APPROVED', 'REJECTED')) > 0
            ORDER BY reject_rate DESC NULLS LAST, decided DESC LIMIT ?
            """, safeLimit);
        Map<String, Object> outbox = jdbc.queryForMap("""
            SELECT COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                   COUNT(*) FILTER (WHERE status = 'DEAD') AS dead,
                   MIN(created_at) FILTER (WHERE status = 'PENDING') AS oldest_pending
            FROM t_workflow_outbox
            """);
        return Map.of("stuckInstances", stuck, "overdueTasks", overdue,
            "nodeRejectionRates", rejectionRates, "outbox", outbox);
    }
}

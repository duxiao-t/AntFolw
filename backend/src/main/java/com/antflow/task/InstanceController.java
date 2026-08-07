package com.antflow.task;

import com.antflow.automation.WorkflowJobService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.StartCmd;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/instances")
@RequiredArgsConstructor
public class InstanceController {
    private final ProcessEngine engine;
    private final ProcessInstanceMapper instanceMapper;
    private final TaskMapper taskMapper;
    private final TaskHistoryMapper historyMapper;
    private final WorkflowJobService workflowJobService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody StartCmd cmd) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_START);
        var p = PrincipalHolder.current().orElseThrow();
        return auditService.execute(() -> engine.start(cmd, p.userId()), result ->
            auditService.success("workflow.instance.start", "PROCESS_INSTANCE",
                result.get("instanceId"), AuditService.RiskLevel.NORMAL,
                Map.of("changedFields", List.of("status", "startedBy", "startedAt",
                    "startedDeptId")),
                Map.of("formCode", cmd.formCode(), "fieldCount", fieldCount(cmd.data()),
                    "selfSelectedNodeCount",
                    cmd.selfSelected() == null ? 0 : cmd.selfSelected().size())));
    }

    @GetMapping
    public List<ProcessInstance> list(@RequestParam(required = false) String status) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_READ);
        var p = PrincipalHolder.current().orElseThrow();
        var q = new QueryWrapper<ProcessInstance>().eq("started_by", p.userId());
        if (status != null) q.eq("status", status);
        return instanceMapper.selectList(q);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        authorizationService.requireReadableInstance(id);
        var pi = instanceMapper.selectById(id);
        if (pi == null) throw new BizException("NOT_FOUND", "instance not found");
        var tasks = taskMapper.selectList(new QueryWrapper<TaskEntity>().eq("proc_inst_id", id));
        var history = historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
            .eq("proc_inst_id", id).orderByAsc("created_at"));
        Map<String, Object> result = Map.of(
            "instance", pi,
            "tasks", tasks,
            "history", history,
            "automationJobs", workflowJobService.listViews(id)
        );
        auditService.success("workflow.instance.detail.read", "PROCESS_INSTANCE", id,
            AuditService.RiskLevel.HIGH, Map.of(), Map.of());
        return result;
    }

    @GetMapping("/{id}/history")
    public List<TaskHistoryEntity> history(@PathVariable Long id) {
        authorizationService.requireReadableInstance(id);
        return historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
            .eq("proc_inst_id", id).orderByAsc("created_at"));
    }

    @PostMapping("/{id}/withdraw")
    public void withdraw(@PathVariable Long id) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_WITHDRAW);
        var p = PrincipalHolder.current().orElseThrow();
        auditService.execute(() -> engine.withdraw(id, p.userId()),
            () -> auditService.success("workflow.instance.withdraw", "PROCESS_INSTANCE", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "endedAt")), Map.of()));
    }

    @PostMapping("/{id}/jobs/{jobId}/retry")
    public void retryAutomationJob(@PathVariable Long id, @PathVariable Long jobId) {
        authorizationService.requireManageInstance(id, PermissionCodes.WORKFLOW_AUTOMATION_RETRY);
        auditService.execute(() -> workflowJobService.retryFailed(id, jobId),
            () -> auditService.success("workflow.automation.retry", "WORKFLOW_JOB", jobId,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "scheduledAt", "attempts",
                    "lastError")),
                Map.of("processInstanceId", id)));
    }

    private static int fieldCount(Object data) {
        return data instanceof Map<?, ?> map ? map.size() : 0;
    }
}

package com.antflow.task;

import com.antflow.automation.WorkflowJobService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.StartCmd;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.process.DefinitionVersionRepository;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashMap;
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
    private final FormDefinitionService formDefinitionService;
    private final FormDataMapper formDataMapper;
    @Autowired(required = false)
    private DefinitionVersionRepository definitionVersions;

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody StartCmd cmd) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_START);
        authorizationService.requirePermission(PermissionCodes.FORM_RUNTIME_READ);
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
    public WorkflowPage<ProcessInstance> list(
            @RequestParam(defaultValue = "authorized") String scope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long startedBy,
            @RequestParam(required = false) String keyword) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_READ);
        var p = PrincipalHolder.current().orElseThrow();
        String normalizedScope = scope == null ? "authorized"
            : scope.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"authorized".equals(normalizedScope) && !"mine".equals(normalizedScope)) {
            throw new BizException("BAD_QUERY", "instance scope must be authorized or mine");
        }
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(100, Math.max(1, size));
        int offset = pageOffset(normalizedPage, normalizedSize);
        boolean admin = authorizationService.isAdmin();
        boolean canReadTasks = authorizationService.hasPermission(
            PermissionCodes.WORKFLOW_TASK_READ);
        boolean canReadInstances = authorizationService.hasPermission(
            PermissionCodes.WORKFLOW_INSTANCE_READ);
        String normalizedStatus = normalized(status);
        String normalizedKeyword = normalized(keyword);
        return new WorkflowPage<>(instanceMapper.selectInstancePage(p.userId(), admin,
                canReadTasks, canReadInstances, normalizedScope, normalizedStatus, startedBy,
                normalizedKeyword, normalizedSize, offset),
            instanceMapper.countInstancePage(p.userId(), admin, canReadTasks,
                canReadInstances, normalizedScope, normalizedStatus, startedBy,
                normalizedKeyword), normalizedPage, normalizedSize);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        var principal = PrincipalHolder.current().orElseThrow();
        var visibility = authorizationService.instanceVisibility(id, principal.userId());
        if (visibility == AuthorizationService.InstanceVisibility.NONE) {
            throw new com.antflow.authz.HiddenResourceException("instance not found");
        }
        var pi = instanceMapper.selectById(id);
        if (pi == null) throw new BizException("NOT_FOUND", "instance not found");
        var tasks = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", id).ne("status", "SKIPPED"));
        var history = historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
            .eq("proc_inst_id", id).ne("action", "SKIP").orderByAsc("created_at"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visibility", visibility.name());
        result.put("history", history);
        if (visibility == AuthorizationService.InstanceVisibility.SUMMARY) {
            result.put("instance", summaryInstance(pi));
            result.put("tasks", tasks.stream().map(InstanceController::summaryTask).toList());
        } else {
            var formData = formDataMapper.selectById(pi.getFormDataId());
            var form = formData == null ? null : formDefinitionService.getById(formData.getFormDefId());
            result.put("instance", pi);
            result.put("tasks", tasks);
            result.put("automationJobs", workflowJobService.listViews(id));
            String schema = pi.getCurrentFormRevisionId() != null && definitionVersions != null
                ? definitionVersions.revisionSchema(pi.getCurrentFormRevisionId()) : null;
            Object responseSchema = schema != null ? schema : form == null ? null : form.getSchema();
            Object responseData = formData == null ? null : formData.getData();
            if (!principal.isAdmin()
                && java.util.Objects.equals(pi.getStartedBy(), principal.userId())
                && responseSchema != null) {
                responseData = formDefinitionService.projectStarterData(
                    responseData, responseSchema, pi.getProcessSnapshot());
                responseSchema = formDefinitionService.projectStarterSchema(
                    responseSchema, pi.getProcessSnapshot());
            }
            result.put("schema", responseSchema);
            result.put("formData", responseData);
            result.put("formRevisions", definitionVersions == null
                ? List.of() : definitionVersions.revisions(id));
            result.put("nodeInstances", definitionVersions == null
                ? List.of() : definitionVersions.nodeInstances(id));
        }
        auditService.success("workflow.instance.detail.read", "PROCESS_INSTANCE", id,
            AuditService.RiskLevel.HIGH, Map.of(), Map.of());
        return result;
    }

    @GetMapping("/{id}/history")
    public List<TaskHistoryEntity> history(@PathVariable Long id) {
        authorizationService.requireReadableInstance(id);
        return historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
            .eq("proc_inst_id", id).ne("action", "SKIP").orderByAsc("created_at"));
    }

    @PostMapping("/{id}/withdraw")
    public void withdraw(@PathVariable Long id) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_WITHDRAW);
        var p = PrincipalHolder.current().orElseThrow();
        auditService.execute(() -> engine.withdraw(id, p.userId()),
            () -> auditService.success("workflow.instance.withdraw", "PROCESS_INSTANCE", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("currentNodeId", "formData.status",
                    "reworkTask")), Map.of()));
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

    @PostMapping("/{id}/terminate")
    public void terminate(@PathVariable Long id, @RequestBody AdminTerminateRequest request) {
        authorizationService.requireManageInstance(id, PermissionCodes.WORKFLOW_INSTANCE_OVERRIDE);
        if (request == null || request.ticketNo() == null || request.ticketNo().isBlank()
            || request.reason() == null || request.reason().isBlank()) {
            throw new BizException("OVERRIDE_JUSTIFICATION_REQUIRED",
                "ticket number and reason are required");
        }
        long operatorId = PrincipalHolder.current().orElseThrow().userId();
        String reason = "[" + request.ticketNo().trim() + "] " + request.reason().trim();
        auditService.execute(() -> engine.adminTerminate(id, operatorId, reason),
            () -> auditService.success("workflow.instance.admin_terminate",
                "PROCESS_INSTANCE", id, AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("status", "finishedAt")),
                Map.of("ticketNo", request.ticketNo())));
    }

    private static int fieldCount(Object data) {
        return data instanceof Map<?, ?> map ? map.size() : 0;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int pageOffset(int page, int size) {
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            throw new BizException("BAD_QUERY", "page offset is too large");
        }
        return (int) offset;
    }

    private static Map<String, Object> summaryInstance(ProcessInstance instance) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", instance.getId());
        result.put("status", instance.getStatus());
        result.put("currentNodeId", instance.getCurrentNodeId());
        result.put("startedBy", instance.getStartedBy());
        result.put("startedAt", instance.getStartedAt());
        result.put("finishedAt", instance.getFinishedAt());
        return result;
    }

    private static Map<String, Object> summaryTask(TaskEntity task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("nodeId", task.getNodeId());
        result.put("assigneeId", task.getAssigneeId());
        result.put("approvedBy", task.getApprovedBy());
        result.put("status", task.getStatus());
        result.put("approvedAt", task.getApprovedAt());
        return result;
    }

    public record AdminTerminateRequest(String ticketNo, String reason) { }
}

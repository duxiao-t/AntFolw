package com.antflow.task;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.CompleteCmd;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final ProcessEngine engine;
    private final TaskOperationService ops;
    private final AuthorizationService authorizationService;
    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper instanceMapper;
    private final AuditService auditService;

    @GetMapping
    public WorkflowPage<TaskEntity> myInbox(
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_READ);
        var p = PrincipalHolder.current().orElseThrow();
        String normalizedView = normalizeView(view, status);
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(100, Math.max(1, size));
        int offset = pageOffset(normalizedPage, normalizedSize);
        return new WorkflowPage<>(
            taskMapper.selectTaskPage(p.userId(), normalizedView, normalized(status),
                normalizedSize, offset),
            taskMapper.countTaskPage(p.userId(), normalizedView, normalized(status)),
            normalizedPage, normalizedSize);
    }

    @PostMapping("/{id}/approve")
    public void approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_APPROVE);
        var p = PrincipalHolder.current().orElseThrow();
        auditService.execute(() -> engine.approve(new CompleteCmd(id, "APPROVE",
                body == null ? null : asString(body.get("comment")), null,
                body == null ? null : body.get("data")), p.userId()),
            () -> auditService.success("workflow.task.approve", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "approvedBy", "approvedAt")),
                commentMetadata(body)));
    }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_REJECT);
        var p = PrincipalHolder.current().orElseThrow();
        auditService.execute(() -> engine.reject(new CompleteCmd(id, "REJECT",
                asString(body.get("comment")), asString(body.get("rejectToNodeId"))),
                p.userId()),
            () -> auditService.success("workflow.task.reject", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "approvedBy", "approvedAt")),
                Map.of("commentLength", lengthOf(body.get("comment")),
                    "rejectTargetPresent", body.get("rejectToNodeId") != null)));
    }

    @PostMapping("/instances/{id}/withdraw")
    public void withdraw(@PathVariable Long id) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_WITHDRAW);
        var p = PrincipalHolder.current().orElseThrow();
        auditService.execute(() -> engine.withdraw(id, p.userId()),
            () -> auditService.success("workflow.instance.withdraw", "PROCESS_INSTANCE", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("currentNodeId", "formData.status",
                    "reworkTask")), Map.of()));
    }

    /** 转交：把任务给另一个人。原任务 SKIPPED；新任务 PENDING。 */
    @PostMapping("/{id}/transfer")
    public Map<String, Object> transfer(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_TRANSFER);
        long targetUserId = Long.parseLong(asString(body.get("targetUserId")));
        String comment = asString(body.get("comment"));
        long newTaskId = auditService.execute(() -> ops.transfer(id, targetUserId, comment),
            createdTaskId -> auditService.success("workflow.task.transfer", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "childTaskId")),
                Map.of("targetUserId", targetUserId, "newTaskId", createdTaskId,
                    "commentLength", lengthOf(comment))));
        return Map.of("newTaskId", newTaskId);
    }

    /** 委托：把任务镜像给另一个人。原任务不动。 */
    @PostMapping("/{id}/delegate")
    public Map<String, Object> delegate(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_DELEGATE);
        long targetUserId = Long.parseLong(asString(body.get("targetUserId")));
        String comment = asString(body.get("comment"));
        long newTaskId = auditService.execute(() -> ops.delegate(id, targetUserId, comment),
            createdTaskId -> auditService.success("workflow.task.delegate", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("childTaskId")),
                Map.of("targetUserId", targetUserId, "newTaskId", createdTaskId,
                    "commentLength", lengthOf(comment))));
        return Map.of("newTaskId", newTaskId);
    }

    /** 加签：在原任务基础上加一个 PENDING 子任务，与原任务一起 OR/AND 判定。 */
    @PostMapping("/{id}/add-assignee")
    public Map<String, Object> addAssignee(@PathVariable Long id,
                                            @RequestBody Map<String, Object> body) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_ADD_ASSIGNEE);
        long targetUserId = Long.parseLong(asString(body.get("targetUserId")));
        String comment = asString(body.get("comment"));
        String position = "AFTER".equals(asString(body.get("position"))) ? "AFTER" : "BEFORE";
        long newTaskId = auditService.execute(
            () -> ops.addAssignee(id, targetUserId, position, comment),
            createdTaskId -> auditService.success("workflow.task.add_assignee", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("childTaskId")),
                Map.of("targetUserId", targetUserId, "position", position,
                    "newTaskId", createdTaskId,
                    "commentLength", lengthOf(comment))));
        return Map.of("newTaskId", newTaskId);
    }

    /** 撤回子任务。TRANSFER 类型会恢复父任务；DELEGATE/ADD_ASSIGNEE 仅关闭子任务。 */
    @PostMapping("/{id}/recall-child")
    public void recallChild(@PathVariable Long id,
                            @RequestBody(required = false) Map<String, Object> body) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_RECALL);
        String comment = asString(body == null ? null : body.get("comment"));
        auditService.execute(() -> ops.recallChild(id, comment),
            () -> auditService.success("workflow.task.recall", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status")),
                Map.of("commentLength", lengthOf(comment))));
    }

    /** 列出某父任务的所有子任务（用于详情页展开转交/加签链路）。 */
    @GetMapping("/{id}/children")
    public List<TaskEntity> children(@PathVariable Long id) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_READ);
        authorizationService.requireReadableTask(id);
        return ops.listChildren(id);
    }

    @PostMapping("/{id}/recall-approval")
    public void recallApproval(@PathVariable Long id) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_RECALL);
        var principal = PrincipalHolder.current().orElseThrow();
        auditService.execute(() -> engine.recallApproval(id, principal.userId()),
            () -> auditService.success("workflow.task.recall_approval", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "approvedBy", "approvedAt")),
                Map.of()));
    }

    @PostMapping("/{id}/override")
    public void override(@PathVariable Long id, @RequestBody OverrideRequest body) {
        authorizationService.requireManageTask(id, PermissionCodes.WORKFLOW_INSTANCE_OVERRIDE);
        if (body == null || body.action() == null
            || body.ticketNo() == null || body.ticketNo().isBlank()
            || body.reason() == null || body.reason().isBlank()) {
            throw new BizException("OVERRIDE_JUSTIFICATION_REQUIRED",
                "action, ticket number and reason are required");
        }
        var principal = PrincipalHolder.current().orElseThrow();
        TaskEntity task = taskMapper.selectById(id);
        if (task == null) throw new BizException("NOT_FOUND", "task not found");
        ProcessInstance instance = instanceMapper.selectById(task.getProcInstId());
        boolean ownInstance = instance != null
            && java.util.Objects.equals(instance.getStartedBy(), principal.userId());
        String comment = "[" + body.ticketNo().trim() + "] " + body.reason().trim();
        CompleteCmd command = new CompleteCmd(id, body.action().name(), comment,
            body.rejectToNodeId());
        auditService.execute(() -> {
            if (body.action() == OverrideAction.APPROVE) {
                engine.forceApprove(command, principal.userId());
            } else {
                engine.forceReject(command, principal.userId());
            }
        }, () -> auditService.success("workflow.instance.override", "TASK", id,
            ownInstance ? AuditService.RiskLevel.CRITICAL : AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("status", "approvedBy", "approvedAt")),
            Map.of("action", body.action().name(), "ticketNo", body.ticketNo().trim(),
                "originalAssigneeId", task.getAssigneeId(), "selfInitiated", ownInstance,
                "reasonLength", body.reason().trim().length())));
    }

    @PostMapping("/{id}/reassign")
    public void reassign(@PathVariable Long id, @RequestBody ReassignRequest body) {
        if (body == null || body.targetUserId() == null || body.ticketNo() == null
            || body.ticketNo().isBlank() || body.reason() == null || body.reason().isBlank()) {
            throw new BizException("OVERRIDE_JUSTIFICATION_REQUIRED",
                "target user, ticket number and reason are required");
        }
        authorizationService.requireManageTask(id, PermissionCodes.WORKFLOW_INSTANCE_OVERRIDE);
        long operatorId = PrincipalHolder.current().orElseThrow().userId();
        String reason = "[" + body.ticketNo().trim() + "] " + body.reason().trim();
        auditService.execute(
            () -> engine.adminReassign(id, body.targetUserId(), operatorId, reason),
            () -> auditService.success("workflow.task.admin_reassign", "TASK", id,
                AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("assigneeId")),
                Map.of("targetUserId", body.targetUserId(), "ticketNo", body.ticketNo())));
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    private static String normalizeView(String view, String status) {
        if (view == null || view.isBlank()) {
            return status == null || status.isBlank() || "PENDING".equals(status)
                ? "pending" : "done";
        }
        if (!"pending".equalsIgnoreCase(view) && !"done".equalsIgnoreCase(view)) {
            throw new BizException("BAD_QUERY", "task view must be pending or done");
        }
        return view.toLowerCase(java.util.Locale.ROOT);
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

    private static Map<String, Object> commentMetadata(Map<String, Object> body) {
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("commentLength", lengthOf(body == null ? null : body.get("comment")));
        Object data = body == null ? null : body.get("data");
        if (data instanceof Map<?, ?> map) {
            metadata.put("changedFieldCount", map.size());
            metadata.put("changedFieldIds", map.keySet().stream().map(String::valueOf).toList());
        }
        return metadata;
    }

    private static int lengthOf(Object value) {
        String string = asString(value);
        return string == null ? 0 : string.length();
    }

    public enum OverrideAction { APPROVE, REJECT }
    public record OverrideRequest(OverrideAction action, String ticketNo, String reason,
                                  String rejectToNodeId) { }
    public record ReassignRequest(Long targetUserId, String ticketNo, String reason) { }
}

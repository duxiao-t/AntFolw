package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
public class MobileWorkflowController {
    private final MobileDraftService draftService;
    private final MobileWorkflowService workflowService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    @PostMapping("/drafts")
    public Long createDraft(@RequestBody MobileDraftRequest request) {
        PrincipalHolder.Principal principal = principal();
        return auditService.execute(
            () -> draftService.create(request.formCode(), request.data(), principal.userId()),
            id -> auditService.success("form.draft.create", "FORM_DRAFT", id,
                AuditService.RiskLevel.NORMAL,
                Map.of("changedFields", dataFields(request.data())),
                Map.of("fieldCount", dataFieldCount(request.data()))));
    }

    @PutMapping("/drafts/{id}")
    public MobileDraftDto updateDraft(@PathVariable Long id,
                                      @RequestBody MobileDraftRequest request) {
        PrincipalHolder.Principal principal = principal();
        return auditService.execute(() -> draftService.get(
                draftService.update(id, request.data(), principal.userId()).getId(),
                principal.userId()),
            updated -> auditService.success("form.draft.update", "FORM_DRAFT", id,
                AuditService.RiskLevel.NORMAL,
                Map.of("changedFields", dataFields(request.data())),
                Map.of("fieldCount", dataFieldCount(request.data()))));
    }

    @DeleteMapping("/drafts/{id}")
    public void deleteDraft(@PathVariable Long id) {
        long userId = principal().userId();
        auditService.execute(() -> draftService.delete(id, userId),
            () -> auditService.success("form.draft.delete", "FORM_DRAFT", id,
                AuditService.RiskLevel.NORMAL,
                Map.of("changedFields", List.of("deleted")), Map.of()));
    }

    @GetMapping("/drafts")
    public List<MobileDraftDto> drafts() {
        return draftService.list(principal().userId());
    }

    @GetMapping("/drafts/{id}")
    public MobileDraftDto draft(@PathVariable Long id) {
        return draftService.get(id, principal().userId());
    }

    @GetMapping("/forms/{code}")
    public MobileFormDto form(@PathVariable String code) {
        principal();
        authorizationService.requirePermission(PermissionCodes.FORM_RUNTIME_READ);
        return workflowService.getMobileForm(code);
    }

    @PostMapping("/instances")
    public MobileStartResult start(@RequestBody StartMobileInstanceRequest request) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_START);
        authorizationService.requirePermission(PermissionCodes.FORM_RUNTIME_READ);
        long userId = principal().userId();
        return auditService.execute(() -> workflowService.start(request, userId),
            result -> auditService.success("workflow.instance.start", "PROCESS_INSTANCE",
                result.instanceId(), AuditService.RiskLevel.NORMAL,
                Map.of("changedFields", List.of("status", "startedBy", "startedAt",
                    "startedDeptId")),
                Map.of("formCode", request.formCode(),
                    "fieldCount", dataFieldCount(request.data()),
                    "fileCount", request.files() == null ? 0 : request.files().size(),
                    "selfSelectedNodeCount",
                    request.selfSelected() == null ? 0 : request.selfSelected().size())));
    }

    @GetMapping("/instances")
    public MobilePageDto<MobileInstanceDto> instances(@RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String status) {
        return workflowService.listInstances(principal().userId(), page, size, keyword, status);
    }

    @GetMapping("/instances/{id}")
    public MobileInstanceDetailDto instance(@PathVariable Long id) {
        PrincipalHolder.Principal principal = principal();
        MobileInstanceDetailDto detail = workflowService.getInstanceDetail(
            id, principal.userId(), principal.roles());
        auditService.success("workflow.instance.detail.read", "PROCESS_INSTANCE", id,
            AuditService.RiskLevel.HIGH, Map.of(), Map.of("client", "mobile"));
        return detail;
    }

    @PostMapping("/instances/{id}/withdraw")
    public void withdraw(@PathVariable Long id) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_INSTANCE_WITHDRAW);
        long userId = principal().userId();
        auditService.execute(() -> workflowService.withdraw(id, userId),
            () -> auditService.success("workflow.instance.withdraw", "PROCESS_INSTANCE", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "endedAt")),
                Map.of("client", "mobile")));
    }

    @GetMapping("/tasks")
    public MobilePageDto<MobileTaskDto> tasks(@RequestParam(defaultValue = "pending") String view,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_READ);
        return workflowService.listTasks(view, principal().userId(), page, size, keyword, status);
    }

    @GetMapping("/tasks/{id}")
    public MobileTaskDetailDto task(@PathVariable Long id) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_READ);
        PrincipalHolder.Principal principal = principal();
        MobileTaskDetailDto detail = workflowService.getTaskDetail(
            id, principal.userId(), principal.roles());
        auditService.success("workflow.task.detail.read", "TASK", id,
            AuditService.RiskLevel.HIGH, Map.of(), Map.of("client", "mobile"));
        return detail;
    }

    @PostMapping("/tasks/{id}/read")
    public void markTaskRead(@PathVariable Long id) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_READ);
        auditService.execute(() -> workflowService.markTaskRead(id, principal().userId()),
            () -> auditService.success("workflow.task.acknowledge", "TASK", id,
                AuditService.RiskLevel.NORMAL,
                Map.of("changedFields", List.of("readAt")), Map.of("client", "mobile")));
    }

    @PostMapping("/tasks/{id}/approve")
    public void approve(@PathVariable Long id,
                        @RequestBody(required = false) MobileTaskActionRequest request) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_APPROVE);
        long userId = principal().userId();
        auditService.execute(() -> workflowService.approve(id, request, userId),
            () -> auditService.success("workflow.task.approve", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "approvedBy", "approvedAt")),
                Map.of("client", "mobile", "commentLength", commentLength(request),
                    "changedFieldCount", dataFieldCount(request == null ? null : request.data()),
                    "changedFieldIds", dataFields(request == null ? null : request.data()))));
    }

    @PostMapping("/tasks/{id}/reject")
    public void reject(@PathVariable Long id,
                       @RequestBody(required = false) MobileTaskActionRequest request) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_TASK_REJECT);
        long userId = principal().userId();
        auditService.execute(() -> workflowService.reject(id, request, userId),
            () -> auditService.success("workflow.task.reject", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "approvedBy", "approvedAt")),
                Map.of("client", "mobile", "commentLength", commentLength(request))));
    }

    @GetMapping("/rework-tasks/{id}")
    public ReworkTaskDto reworkTask(@PathVariable Long id) {
        return workflowService.getReworkTask(id, principal().userId());
    }

    @PutMapping("/rework-tasks/{id}")
    public ReworkTaskDto saveReworkTask(@PathVariable Long id,
                                        @RequestBody ReworkTaskRequest request) {
        long userId = principal().userId();
        return auditService.execute(() -> workflowService.saveRework(id, request, userId),
            result -> auditService.success("workflow.rework.save", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", dataFields(request.data())),
                Map.of("client", "mobile", "fieldCount", dataFieldCount(request.data()),
                    "fileCount", request.files() == null ? 0 : request.files().size())));
    }

    @PostMapping("/rework-tasks/{id}/resubmit")
    public ReworkResult resubmitReworkTask(@PathVariable Long id,
                                           @RequestBody ReworkTaskRequest request) {
        long userId = principal().userId();
        return auditService.execute(() -> workflowService.resubmitRework(id, request, userId),
            result -> auditService.success("workflow.rework.resubmit", "TASK", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", dataFields(request.data())),
                Map.of("client", "mobile", "processInstanceId", result.instanceId(),
                    "fieldCount", dataFieldCount(request.data()),
                    "fileCount", request.files() == null ? 0 : request.files().size())));
    }

    private static PrincipalHolder.Principal principal() {
        return PrincipalHolder.current()
            .orElseThrow(() -> new AccessDeniedException("authentication required"));
    }

    private static List<String> dataFields(JsonNode data) {
        if (data == null || !data.isObject()) return List.of();
        List<String> fields = new ArrayList<>();
        data.fieldNames().forEachRemaining(fields::add);
        return List.copyOf(fields);
    }

    private static int dataFieldCount(JsonNode data) {
        return data == null || !data.isObject() ? 0 : data.size();
    }

    private static int commentLength(MobileTaskActionRequest request) {
        return request == null || request.comment() == null ? 0 : request.comment().length();
    }
}

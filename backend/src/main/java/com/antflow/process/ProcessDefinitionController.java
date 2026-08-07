package com.antflow.process;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/processes/definitions")
@RequiredArgsConstructor
public class ProcessDefinitionController {
    private final ProcessDefinitionService service;
    private final ProcessDefinitionMapper mapper;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    @GetMapping
    public List<ProcessDefinition> list() {
        authorizationService.requirePermission(PermissionCodes.FORM_DEFINITION_READ);
        var principal = PrincipalHolder.current().orElseThrow();
        return service.listAuthorized(principal.userId(), principal.isAdmin());
    }

    @GetMapping("/by-form/{formDefId}")
    public ProcessDefinition byForm(@PathVariable Long formDefId) {
        authorizationService.requireFormAction(formDefId, PermissionCodes.FORM_DEFINITION_READ);
        return service.latestPublishedForForm(formDefId);
    }

    @GetMapping("/draft/by-form/{formDefId}")
    public ProcessDefinition draftByForm(@PathVariable Long formDefId) {
        authorizationService.requireFormAction(formDefId, PermissionCodes.FORM_DEFINITION_DESIGN);
        return service.findByForm(formDefId);
    }

    @GetMapping("/{id}")
    public ProcessDefinition get(@PathVariable Long id) {
        ProcessDefinition definition = service.getById(id);
        if (definition == null) throw new com.antflow.authz.HiddenResourceException("process not found");
        authorizationService.requireFormAction(definition.getFormDefId(),
            PermissionCodes.FORM_DEFINITION_READ);
        return definition;
    }

    @PostMapping
    public ProcessDefinition save(@RequestBody SaveBody body) {
        var p = PrincipalHolder.current().orElseThrow();
        authorizationService.requireFormAction(body.formDefId(),
            PermissionCodes.FORM_DEFINITION_DESIGN);
        return auditService.execute(
            () -> service.saveOrUpdateDraft(body.id(), body.formDefId(), body.process(),
                p.userId()),
            saved -> auditService.success("workflow.definition.save", "PROCESS_DEFINITION",
                saved.getId(), AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("process", "status")),
                Map.of("formDefinitionId", body.formDefId(), "version", saved.getVersion())));
    }

    @PostMapping("/{id}/publish")
    public ProcessDefinition publish(@PathVariable Long id) {
        ProcessDefinition definition = service.getById(id);
        if (definition == null) throw new com.antflow.authz.HiddenResourceException("process not found");
        authorizationService.requireFormAction(definition.getFormDefId(),
            PermissionCodes.FORM_DEFINITION_PUBLISH);
        return auditService.execute(() -> service.publish(id),
            published -> auditService.success("workflow.definition.publish",
                "PROCESS_DEFINITION", id, AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "version")),
                Map.of("formDefinitionId", published.getFormDefId(),
                    "version", published.getVersion())));
    }

    @DeleteMapping("/by-form/{formDefId}")
    public void deleteByForm(@PathVariable Long formDefId) {
        authorizationService.requireFormAction(formDefId, PermissionCodes.FORM_DEFINITION_DELETE);
        ProcessDefinition existing = service.findByForm(formDefId);
        auditService.execute(() -> service.deleteByForm(formDefId),
            () -> auditService.success("workflow.definition.delete", "PROCESS_DEFINITION",
                existing == null ? null : existing.getId(), AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("deleted")),
                Map.of("formDefinitionId", formDefId)));
    }

    public record SaveBody(Long id, Long formDefId, Object process) {}
}

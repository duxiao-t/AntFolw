package com.antflow.form;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/forms/definitions")
@RequiredArgsConstructor
public class FormDefinitionController {
    private final FormDefinitionService service;
    private final FormDefinitionMapper mapper;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final FormProcessPublishService formProcessPublishService;

    @GetMapping
    public Page<FormDefinition> list(@RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "20") long size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status) {
        authorizationService.requirePermission(PermissionCodes.FORM_DEFINITION_READ);
        var principal = PrincipalHolder.current().orElseThrow();
        return service.list(page, size, keyword, status,
            principal.userId(), principal.isAdmin());
    }

    @GetMapping("/{id}")
    public FormDefinition get(@PathVariable Long id) {
        authorizationService.requireFormAction(id, PermissionCodes.FORM_DEFINITION_READ);
        FormDefinition definition = mapper.selectById(id);
        if (definition == null) throw new com.antflow.authz.HiddenResourceException("form not found");
        return definition;
    }

    @GetMapping("/by-code/{code}")
    public FormDefinition byCode(@PathVariable String code) {
        FormDefinition fd = service.getPublishedByCode(code);
        if (fd == null) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + code);
        }
        authorizationService.requireFormAction(fd.getId(), PermissionCodes.FORM_RUNTIME_READ);
        return fd;
    }

    @PostMapping
    public FormDefinition save(@RequestBody SaveBody body) {
        var p = PrincipalHolder.current().orElseThrow();
        boolean creating = body.id() == null;
        if (creating) {
            authorizationService.requirePermission(PermissionCodes.FORM_DEFINITION_CREATE);
        } else {
            authorizationService.requireFormAction(body.id(), PermissionCodes.FORM_DEFINITION_DESIGN);
        }
        return auditService.execute(
            () -> service.saveDraft(body.id(), body.code(), body.name(), body.description(),
                body.schema(), body.settings(), p.userId()),
            saved -> auditService.success(
                creating ? "form.definition.create" : "form.definition.save",
                "FORM_DEFINITION", saved.getId(), AuditService.RiskLevel.HIGH,
                Map.of("changedFields", changedFields(body, creating)),
                Map.of("version", saved.getVersion())));
    }

    @PutMapping("/{id}")
    public FormDefinition update(@PathVariable Long id, @RequestBody SaveBody body) {
        authorizationService.requireFormAction(id, PermissionCodes.FORM_DEFINITION_DESIGN);
        return auditService.execute(
            () -> service.update(id, body.name(), body.description(), body.status(),
                body.schema(), body.settings()),
            updated -> auditService.success("form.definition.save", "FORM_DEFINITION", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", changedFields(body, false)),
                Map.of("version", updated.getVersion())));
    }

    @PostMapping("/{id}/publish")
    public FormDefinition publish(@PathVariable Long id) {
        authorizationService.requireFormAction(id, PermissionCodes.FORM_DEFINITION_PUBLISH);
        return auditService.execute(() -> service.publish(id),
            published -> auditService.success("form.definition.publish", "FORM_DEFINITION", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "version")),
                Map.of("version", published.getVersion())));
    }

    @PostMapping("/{id}/publish-with-process")
    public FormProcessPublishService.PublishResult publishWithProcess(
            @PathVariable Long id, @RequestBody PublishWithProcessBody body) {
        authorizationService.requireFormAction(id, PermissionCodes.FORM_DEFINITION_PUBLISH);
        if (body == null || body.processDefinitionId() == null) {
            throw new BizException("PROCESS_DEFINITION_REQUIRED",
                "processDefinitionId is required");
        }
        return formProcessPublishService.publish(id, body.processDefinitionId());
    }

    @PostMapping("/{id}/disable")
    public FormDefinition disable(@PathVariable Long id) {
        authorizationService.requireFormAction(id, PermissionCodes.FORM_DEFINITION_PUBLISH);
        return auditService.execute(() -> service.disable(id),
            disabled -> auditService.success("form.definition.disable", "FORM_DEFINITION", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status")), Map.of()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        authorizationService.requireFormAction(id, PermissionCodes.FORM_DEFINITION_DELETE);
        auditService.execute(() -> service.softDelete(id),
            () -> auditService.success("form.definition.delete", "FORM_DEFINITION", id,
                AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("deleted")), Map.of()));
    }

    private static List<String> changedFields(SaveBody body, boolean includeCode) {
        List<String> fields = new ArrayList<>();
        if (includeCode && body.code() != null) fields.add("code");
        if (body.name() != null) fields.add("name");
        if (body.description() != null) fields.add("description");
        if (body.status() != null) fields.add("status");
        if (body.schema() != null) fields.add("schema");
        if (body.settings() != null) fields.add("settings");
        return fields;
    }

    public record SaveBody(Long id, String code, String name, String description,
                           String status, Object schema, Object settings) {}
    public record PublishWithProcessBody(Long processDefinitionId) {}
}

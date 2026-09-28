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
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/forms/definitions")
@RequiredArgsConstructor
public class FormDefinitionController {
    private final FormDefinitionService service;
    private final FormDefinitionMapper mapper;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final FormProcessPublishService formProcessPublishService;

    /**
     * 表单清单。除表单管理员外，选项数据源管理员也要挑表单来绑定数据源，
     * 早先只认 FORM_DEFINITION_READ，导致数据源页的"绑定表单"下拉直接 403。
     * 可见范围没有放宽：非 admin 仍只看到自己维护的表单（见 selectSummaryPage）。
     */
    @GetMapping
    @PreAuthorize("@authz.consoleAny('" + PermissionCodes.FORM_DEFINITION_READ
        + "', '" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public Page<FormDefinitionMapper.Summary> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        var principal = PrincipalHolder.current().orElseThrow();
        return service.list(page, size, keyword, status,
            principal.userId(), principal.isAdmin());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.consoleAny('" + PermissionCodes.FORM_DEFINITION_READ
        + "', '" + PermissionCodes.WORKFLOW_DEFINITION_READ + "')")
    public FormDefinition get(@PathVariable Long id) {
        authorizationService.requireFormMaintenanceAny(id, PermissionCodes.FORM_DEFINITION_READ,
            PermissionCodes.WORKFLOW_DEFINITION_READ);
        FormDefinition definition = mapper.selectById(id);
        if (definition == null) throw new com.antflow.authz.HiddenResourceException("form not found");
        return definition;
    }

    @GetMapping("/by-code/{code}")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_RUNTIME_READ + "')")
    public FormDefinition byCode(@PathVariable String code) {
        FormDefinition fd = service.getPublishedByCode(code);
        if (fd == null) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + code);
        }
        authorizationService.requireFormUse(fd.getId());
        return fd;
    }

    @PostMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DEFINITION_MANAGE + "')")
    public FormDefinition save(@RequestBody SaveBody body) {
        var p = PrincipalHolder.current().orElseThrow();
        boolean creating = body.id() == null;
        if (!creating) {
            authorizationService.requireFormMaintenance(body.id(), PermissionCodes.FORM_DEFINITION_MANAGE);
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
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DEFINITION_MANAGE + "')")
    public FormDefinition update(@PathVariable Long id, @RequestBody SaveBody body) {
        authorizationService.requireFormMaintenance(id, PermissionCodes.FORM_DEFINITION_MANAGE);
        return auditService.execute(
            () -> service.update(id, body.name(), body.description(), body.status(),
                body.schema(), body.settings()),
            updated -> auditService.success("form.definition.save", "FORM_DEFINITION", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", changedFields(body, false)),
                Map.of("version", updated.getVersion())));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DEFINITION_PUBLISH + "')")
    public FormDefinition publish(@PathVariable Long id) {
        authorizationService.requireFormMaintenance(id, PermissionCodes.FORM_DEFINITION_PUBLISH);
        return auditService.execute(() -> service.publish(id),
            published -> auditService.success("form.definition.publish", "FORM_DEFINITION", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status", "version")),
                Map.of("version", published.getVersion())));
    }

    @PostMapping("/{id}/publish-with-process")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DEFINITION_PUBLISH + "')")
    public FormProcessPublishService.PublishResult publishWithProcess(
            @PathVariable Long id, @RequestBody PublishWithProcessBody body) {
        authorizationService.requireFormMaintenance(id, PermissionCodes.FORM_DEFINITION_PUBLISH);
        if (body == null || body.processDefinitionId() == null) {
            throw new BizException("PROCESS_DEFINITION_REQUIRED",
                "processDefinitionId is required");
        }
        return formProcessPublishService.publish(id, body.processDefinitionId());
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DEFINITION_PUBLISH + "')")
    public FormDefinition disable(@PathVariable Long id) {
        authorizationService.requireFormMaintenance(id, PermissionCodes.FORM_DEFINITION_PUBLISH);
        return auditService.execute(() -> service.disable(id),
            disabled -> auditService.success("form.definition.disable", "FORM_DEFINITION", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("status")), Map.of()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DEFINITION_DELETE + "')")
    public void delete(@PathVariable Long id) {
        authorizationService.requireFormMaintenance(id, PermissionCodes.FORM_DEFINITION_DELETE);
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

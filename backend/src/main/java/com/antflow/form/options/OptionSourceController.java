package com.antflow.form.options;

import com.antflow.authz.PermissionCodes;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class OptionSourceController {
    private final OptionSourceService service;

    @PostMapping(path = "/api/option-sources/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public OptionSourceService.ImportPreview inspect(@RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String text, @RequestParam(required = false) String sheetName) {
        return service.inspect(file, text, sheetName);
    }

    @GetMapping("/api/option-sources")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public List<OptionSourceService.SourceSummary> list() { return service.list(); }

    @GetMapping("/api/option-sources/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public OptionSourceService.SourceDetail detail(@PathVariable long id) { return service.detail(id); }

    @PostMapping("/api/option-sources")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public OptionSourceService.SourceDetail create(@RequestBody OptionSourceService.SourceWrite request) {
        return service.create(request);
    }

    @DeleteMapping("/api/option-sources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public void delete(@PathVariable long id) { service.delete(id); }

    @PutMapping("/api/option-sources/{id}/grants")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public OptionSourceService.SourceDetail grants(@PathVariable long id,
                                                    @RequestBody OptionSourceService.GrantWrite request) {
        return service.replaceGrants(id, request);
    }

    @PostMapping(path = "/api/option-sources/{id}/versions/import",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public OptionSourceService.VersionView importDraft(
            @PathVariable long id,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String sheetName) {
        return service.importDraft(id, file, text, sheetName);
    }

    @PostMapping("/api/option-sources/{id}/versions/{versionId}/publish")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public OptionSourceService.VersionView publish(@PathVariable long id, @PathVariable long versionId) {
        return service.publish(id, versionId);
    }

    @PostMapping("/api/option-sources/{id}/disable")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public OptionSourceService.SourceSummary disable(@PathVariable long id) { return service.disable(id); }

    @GetMapping("/api/option-sources/{id}/versions/{versionId}/rows")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_OPTION_SOURCE_MANAGE + "')")
    public List<Map<String, Object>> rows(@PathVariable long id, @PathVariable long versionId,
                                          @RequestParam(defaultValue = "20") int limit) {
        return service.previewRows(id, versionId, limit);
    }

    @GetMapping("/api/forms/{formId}/option-sources")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_DEFINITION_MANAGE + "')")
    public List<OptionSourceService.BindableSource> bindable(@PathVariable long formId) {
        return service.bindable(formId);
    }
}

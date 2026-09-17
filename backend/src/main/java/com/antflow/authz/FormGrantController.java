package com.antflow.authz;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/forms")
@RequiredArgsConstructor
public class FormGrantController {
    private final FormGrantService formGrantService;

    @GetMapping("/{formId}/grants")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_AUTHORIZATION_MANAGE + "')")
    public FormGrantService.FormGrantDto get(@PathVariable long formId) {
        return formGrantService.get(formId);
    }

    @GetMapping("/{formId}/grants/candidates")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_AUTHORIZATION_MANAGE + "')")
    public FormGrantService.FormGrantCandidates candidates(@PathVariable long formId) {
        return formGrantService.candidates(formId);
    }

    @GetMapping("/grant-candidates")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_AUTHORIZATION_MANAGE + "')")
    public FormGrantService.FormGrantCandidates candidates() {
        return formGrantService.candidates();
    }

    @GetMapping("/{formId}/grants/user-candidates")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_AUTHORIZATION_MANAGE + "')")
    public FormGrantService.GrantUserPage userCandidates(
            @PathVariable long formId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long departmentId) {
        return formGrantService.userCandidates(formId, page, size, keyword, departmentId);
    }

    @GetMapping("/grant-user-candidates")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_AUTHORIZATION_MANAGE + "')")
    public FormGrantService.GrantUserPage userCandidates(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long departmentId) {
        return formGrantService.userCandidates(null, page, size, keyword, departmentId);
    }

    @PutMapping("/{formId}/grants")
    @PreAuthorize("@authz.console('" + PermissionCodes.FORM_AUTHORIZATION_MANAGE + "')")
    public FormGrantService.FormGrantDto replace(
            @PathVariable long formId,
            @RequestBody FormGrantService.FormGrantWriteRequest request) {
        return formGrantService.replace(formId, request);
    }
}

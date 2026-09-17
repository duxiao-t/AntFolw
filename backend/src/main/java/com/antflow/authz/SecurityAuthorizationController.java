package com.antflow.authz;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class SecurityAuthorizationController {
    private final RoleAdminService roleAdminService;
    private final AuthorizationService authorizationService;

    @GetMapping("/permissions")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_PERMISSION_READ + "')")
    public List<RoleAdminService.PermissionDto> permissions() {
        return roleAdminService.permissions();
    }

    @GetMapping("/roles")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_ROLE_READ + "')")
    public List<RoleAdminService.RoleDto> roles() {
        return roleAdminService.roles();
    }

    @GetMapping("/roles/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_ROLE_READ + "')")
    public RoleAdminService.RoleDto role(@PathVariable long id) {
        return roleAdminService.role(id);
    }

    @PostMapping("/roles")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_ROLE_MANAGE + "')")
    public RoleAdminService.RoleDto create(@RequestBody RoleAdminService.RoleWriteRequest request) {
        return roleAdminService.create(request);
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_ROLE_MANAGE + "')")
    public RoleAdminService.RoleDto update(@PathVariable long id,
                                           @RequestBody RoleAdminService.RoleWriteRequest request) {
        return roleAdminService.update(id, request);
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_ROLE_MANAGE + "')")
    public void delete(@PathVariable long id, @RequestParam int version) {
        roleAdminService.delete(id, version);
    }

    @GetMapping("/users")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_USER_ROLE_READ + "')")
    public RoleAdminService.UserRolePage users(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String keyword) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_USER_ROLE_READ);
        return roleAdminService.userAssignments(page, size, keyword);
    }

    @GetMapping("/role-department-candidates")
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_ROLE_MANAGE + "')")
    public List<RoleAdminService.DepartmentCandidate> roleDepartmentCandidates() {
        return roleAdminService.departmentCandidates();
    }

    @GetMapping("/effective/users/{id}")
    @AuthenticatedOnly
    public RoleAdminService.EffectivePermissionDto effective(@PathVariable long id) {
        return roleAdminService.effective(id);
    }
}

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

@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class SecurityAuthorizationController {
    private final RoleAdminService roleAdminService;
    private final AuthorizationService authorizationService;

    @GetMapping("/permissions")
    public List<RoleAdminService.PermissionDto> permissions() {
        return roleAdminService.permissions();
    }

    @GetMapping("/roles")
    public List<RoleAdminService.RoleDto> roles() {
        return roleAdminService.roles();
    }

    @GetMapping("/roles/{id}")
    public RoleAdminService.RoleDto role(@PathVariable long id) {
        return roleAdminService.role(id);
    }

    @PostMapping("/roles")
    public RoleAdminService.RoleDto create(@RequestBody RoleAdminService.RoleWriteRequest request) {
        return roleAdminService.create(request);
    }

    @PutMapping("/roles/{id}")
    public RoleAdminService.RoleDto update(@PathVariable long id,
                                           @RequestBody RoleAdminService.RoleWriteRequest request) {
        return roleAdminService.update(id, request);
    }

    @DeleteMapping("/roles/{id}")
    public void delete(@PathVariable long id, @RequestParam int version) {
        roleAdminService.delete(id, version);
    }

    @GetMapping("/users")
    public RoleAdminService.UserRolePage users(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String keyword) {
        authorizationService.requireAdmin();
        return roleAdminService.userAssignments(page, size, keyword);
    }

    @GetMapping("/role-department-candidates")
    public List<RoleAdminService.DepartmentCandidate> roleDepartmentCandidates() {
        return roleAdminService.departmentCandidates();
    }

    @GetMapping("/effective/users/{id}")
    public RoleAdminService.EffectivePermissionDto effective(@PathVariable long id) {
        return roleAdminService.effective(id);
    }
}

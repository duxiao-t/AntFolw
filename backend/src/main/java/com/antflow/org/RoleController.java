package com.antflow.org;

import com.antflow.authz.RoleAdminService;
import com.antflow.authz.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import com.antflow.authz.PermissionCodes;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {
    private final RoleAdminService roleAdminService;
    private final AuthorizationService authorizationService;

    @GetMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.SECURITY_ROLE_READ + "')")
    public List<RoleAdminService.RoleDto> all() {
        authorizationService.requirePermission(
            com.antflow.authz.PermissionCodes.SECURITY_ROLE_READ);
        return roleAdminService.roles();
    }
}

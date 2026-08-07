package com.antflow.org;

import com.antflow.authz.RoleAdminService;
import com.antflow.authz.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {
    private final RoleAdminService roleAdminService;
    private final AuthorizationService authorizationService;

    @GetMapping
    public List<RoleAdminService.RoleDto> all() {
        authorizationService.requireAdmin();
        return roleAdminService.roles();
    }
}

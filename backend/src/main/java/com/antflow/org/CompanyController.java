package com.antflow.org;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {
    private final CompanyMapper mapper;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_COMPANY_READ + "')")
    public List<Company> all() {
        authorizationService.requirePermission(PermissionCodes.ORG_COMPANY_READ);
        return mapper.selectList(null);
    }

    @PostMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.SYSTEM_COMPANY_MANAGE + "')")
    public Company create(@RequestBody Company c) {
        authorizationService.requirePermission(PermissionCodes.SYSTEM_COMPANY_MANAGE);
        return auditService.execute(() -> {
            mapper.insert(c);
            return c;
        }, created -> auditService.success("org.company.create", "COMPANY", created.getId(),
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("name")), Map.of()));
    }
}

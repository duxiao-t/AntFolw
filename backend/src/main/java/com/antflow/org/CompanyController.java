package com.antflow.org;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {
    private final CompanyMapper mapper;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    @GetMapping
    public List<Company> all() {
        if (!authorizationService.hasPermission(PermissionCodes.ORG_DEPARTMENT_READ)) {
            authorizationService.requirePermission(PermissionCodes.ORG_COMPANY_MANAGE);
        }
        return mapper.selectList(null);
    }

    @PostMapping
    public Company create(@RequestBody Company c) {
        authorizationService.requirePermission(PermissionCodes.ORG_COMPANY_MANAGE);
        return auditService.execute(() -> {
            mapper.insert(c);
            return c;
        }, created -> auditService.success("org.company.create", "COMPANY", created.getId(),
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("name")), Map.of()));
    }
}

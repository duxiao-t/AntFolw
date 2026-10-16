package com.antflow.org;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
    private final OrgCompanyAccess companyAccess;

    @GetMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_COMPANY_READ + "')")
    public List<Company> all() {
        authorizationService.requirePermission(PermissionCodes.ORG_COMPANY_READ);
        QueryWrapper<Company> query = new QueryWrapper<Company>().orderByAsc("id");
        if (!authorizationService.isAdmin()) {
            Long companyId = companyAccess.currentCompanyId();
            if (companyId == null) return List.of();
            query.eq("id", companyId);
        }
        return mapper.selectList(query);
    }

    @PostMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.SYSTEM_COMPANY_MANAGE + "')")
    public Company create(@RequestBody CreateCompanyRequest request) {
        authorizationService.requirePermission(PermissionCodes.SYSTEM_COMPANY_MANAGE);
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 128) {
            throw new BizException("BAD_COMPANY_NAME", "公司名称须为 1～128 个字符");
        }
        Company c = new Company();
        c.setName(name);
        return auditService.execute(() -> {
            mapper.insert(c);
            return c;
        }, created -> auditService.success("org.company.create", "COMPANY", created.getId(),
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("name")), Map.of()));
    }

    public record CreateCompanyRequest(String name) { }
}

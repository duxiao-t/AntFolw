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
    private final DepartmentMapper departmentMapper;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final OrgCompanyAccess companyAccess;

    /**
     * 公司列表。非管理员只返回自己所属的那家公司（数据范围收敛）；没有归属时返回空列表而不是全量，
     * 避免"查不到就放开"的越权路径。
     */
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

    @GetMapping("/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_COMPANY_READ + "')")
    public Company one(@PathVariable Long id) {
        authorizationService.requirePermission(PermissionCodes.ORG_COMPANY_READ);
        companyAccess.requireCompany(id);
        return requireActiveCompany(id);
    }

    @PostMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.SYSTEM_COMPANY_MANAGE + "')")
    public Company create(@RequestBody CreateCompanyRequest request) {
        authorizationService.requirePermission(PermissionCodes.SYSTEM_COMPANY_MANAGE);
        String name = validatedName(request.name());
        Company c = new Company();
        c.setName(name);
        return auditService.execute(() -> {
            mapper.insert(c);
            return c;
        }, created -> auditService.success("org.company.create", "COMPANY", created.getId(),
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("name")), Map.of()));
    }

    /** 仅改名称：公司归属、部门与历史数据都不受影响。 */
    @PutMapping("/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.SYSTEM_COMPANY_MANAGE + "')")
    public Company update(@PathVariable Long id, @RequestBody UpdateCompanyRequest request) {
        authorizationService.requirePermission(PermissionCodes.SYSTEM_COMPANY_MANAGE);
        companyAccess.requireCompany(id);
        Company existing = requireActiveCompany(id);
        String name = validatedName(request.name());
        if (name.equals(existing.getName())) {
            return existing; // 名称未变化：不写库、不记审计
        }
        existing.setName(name);
        return auditService.execute(() -> {
            mapper.updateById(existing);
            return existing;
        }, updated -> auditService.success("org.company.update", "COMPANY", id,
            AuditService.RiskLevel.HIGH,
            Map.of("changedFields", List.of("name")), Map.of()));
    }

    /**
     * 停用（软删除）。公司被部门/用户/流程实例/填报记录引用，硬删会丢失归属；
     * 因此这里只把 deleted 置 1，历史数据仍可读，列表与选择器不再返回它。
     *
     * <p>两道守卫的顺序是有意的：**先**判断"是否最后一家"（全局条件，与关联数据无关），
     * **再**判断"是否还有部门"（局部条件）。这样最后一家公司的报错永远是更准确的
     * {@code LAST_COMPANY}，而不是被部门数量掩盖。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.SYSTEM_COMPANY_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        authorizationService.requirePermission(PermissionCodes.SYSTEM_COMPANY_MANAGE);
        companyAccess.requireCompany(id);
        requireActiveCompany(id);
        // 全部停用后没人能登录、菜单与数据归属也会落空，因此至少保留一个。
        Long active = mapper.selectCount(new QueryWrapper<>());
        if (active != null && active <= 1) {
            throw new BizException("LAST_COMPANY", "至少保留一家公司，无法停用最后一家");
        }
        long departments = departmentMapper.selectCount(
            new QueryWrapper<Department>().eq("company_id", id));
        if (departments > 0) {
            throw new BizException("COMPANY_IN_USE",
                "该公司下仍有 " + departments + " 个部门，请先转移或删除部门后再停用");
        }
        auditService.execute(() -> mapper.deleteById(id),
            () -> auditService.success("org.company.delete", "COMPANY", id,
                AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("deleted")), Map.of()));
    }

    /**
     * 取一家仍然有效的公司。
     *
     * <p>逻辑删除生效时 {@code selectById} 会自动过滤 {@code deleted = 1}，所以返回 null 同时覆盖
     * "不存在"与"已停用"两种情况——对外统一按 {@code NOT_FOUND} 处理，不向外暴露停用状态。
     */
    private Company requireActiveCompany(Long id) {
        Company company = mapper.selectById(id);
        if (company == null) {
            throw new BizException("NOT_FOUND", "公司不存在或已停用");
        }
        return company;
    }

    /**
     * 名称校验的唯一入口，创建与改名共用，避免两条路径规则漂移。
     *
     * <p>按 **码点** 而不是 `String.length()` 计数：后者数的是 UTF-16 码元，"中"字会被算成 1、
     * emoji 会被算成 2，128 个汉字的合法名称会被误判超长。
     */
    private static String validatedName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 128) {
            throw new BizException("BAD_COMPANY_NAME", "公司名称须为 1～128 个字符");
        }
        return name;
    }

    public record CreateCompanyRequest(String name) { }

    public record UpdateCompanyRequest(String name) { }
}

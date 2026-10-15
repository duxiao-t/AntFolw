package com.antflow.org;

import com.antflow.authz.AuthorizationService;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import com.antflow.authz.PermissionCodes;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {
    private final DepartmentMapper mapper;
    private final DepartmentService service;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final OrgCompanyAccess companyAccess;

    /** 公司下完整部门树（含 ltree 子树递归） */
    @GetMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_DEPARTMENT_READ + "')")
    public List<Department> tree(@RequestParam Long companyId) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_READ);
        companyAccess.requireCompany(companyId);
        List<Department> all = service.tree(companyId);
        Set<Long> departmentRead = authorizationService.visibleDepartments(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_READ);
        Set<Long> userRead = authorizationService.hasPermission(
            com.antflow.authz.PermissionCodes.ORG_USER_READ)
            ? authorizationService.visibleDepartments(com.antflow.authz.PermissionCodes.ORG_USER_READ)
            : Set.of();
        Set<Long> direct = new LinkedHashSet<>(departmentRead);
        direct.addAll(userRead);
        Set<Long> included = withAncestors(all, direct);
        Set<Long> departmentWrite = manageableDepartments(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE);
        Set<Long> userWrite = manageableDepartments(
            com.antflow.authz.PermissionCodes.ORG_USER_MANAGE);
        return all.stream().filter(department -> included.contains(department.getId()))
            .peek(department -> {
                department.setContextOnly(!direct.contains(department.getId()));
                department.setCanReadMembers(userRead.contains(department.getId()));
                department.setCanManageDepartment(departmentWrite.contains(department.getId()));
                department.setCanManageUsers(userWrite.contains(department.getId()));
            }).toList();
    }

    @PostMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_DEPARTMENT_MANAGE + "')")
    public Department create(@RequestBody Department d) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE);
        companyAccess.requireCompany(d.getCompanyId());
        if (d.getParentId() == null) {
            authorizationService.requireAllDataScope(com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE);
        } else {
            authorizationService.requireManageableDepartment(
                com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE, d.getParentId());
        }
        return auditService.execute(() -> service.create(d),
            created -> auditService.success("org.department.create", "DEPARTMENT",
                created.getId(), AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("companyId", "parentId", "name", "leaderIds")),
                Map.of("leaderCount", created.getLeaderIds().size())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_DEPARTMENT_MANAGE + "')")
    public Department update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        companyAccess.requireDepartment(id);
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE, id);
        if (body.containsKey("parentId")) {
            Long parentId = body.get("parentId") == null ? null
                : ((Number) body.get("parentId")).longValue();
            if (parentId == null) {
                authorizationService.requireAllDataScope(
                    com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE);
            } else {
                companyAccess.requireDepartment(parentId);
                authorizationService.requireManageableDepartment(
                    com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE, parentId);
            }
        }
        return auditService.execute(() -> updateDepartment(id, body),
            updated -> auditService.success("org.department.update", "DEPARTMENT", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", changedFields(body,
                    List.of("name", "leaderId", "leaderIds", "parentId"))),
                Map.of("leaderCount", updated.getLeaderIds().size())));
    }

    @PutMapping("/{id}/order")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_DEPARTMENT_MANAGE + "')")
    public Department moveOrder(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        companyAccess.requireDepartment(id);
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE, id);
        return auditService.execute(
            () -> service.moveOrder(id, String.valueOf(body.get("direction"))),
            updated -> auditService.success("org.department.reorder", "DEPARTMENT", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("sortOrder")), Map.of()));
    }

    @PutMapping("/{id}/position")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_DEPARTMENT_MANAGE + "')")
    public Department movePosition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        companyAccess.requireDepartment(id);
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE, id);
        Object targetId = body.get("targetId");
        if (targetId == null) {
            throw new BizException("BAD_TARGET", "目标部门不能为空");
        }
        long targetDepartmentId = ((Number) targetId).longValue();
        companyAccess.requireDepartment(targetDepartmentId);
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE, targetDepartmentId);
        return auditService.execute(
            () -> service.movePosition(id, targetDepartmentId,
                String.valueOf(body.get("placement"))),
            updated -> auditService.success("org.department.reorder", "DEPARTMENT", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("sortOrder")),
                Map.of("targetDepartmentId", targetDepartmentId)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_DEPARTMENT_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        companyAccess.requireDepartment(id);
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_MANAGE, id);
        auditService.execute(() -> service.delete(id),
            () -> auditService.success("org.department.delete", "DEPARTMENT", id,
                AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("deleted")), Map.of()));
    }

    @GetMapping("/{id}/path")
    @PreAuthorize("@authz.console('" + PermissionCodes.ORG_DEPARTMENT_READ + "')")
    public List<Department> path(@PathVariable Long id) {
        companyAccess.requireDepartment(id);
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_READ);
        if (!authorizationService.visibleDepartments(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_READ).contains(id)) {
            throw new org.springframework.security.access.AccessDeniedException(
                "department is outside the permitted data scope");
        }
        return service.pathToRoot(id);
    }

    private Set<Long> manageableDepartments(String permission) {
        if (!authorizationService.hasPermission(permission)) {
            return Set.of();
        }
        return authorizationService.manageableDepartments(
            authorizationService.currentSnapshot(), permission);
    }

    private static Set<Long> withAncestors(List<Department> departments, Set<Long> direct) {
        Map<Long, Department> byId = departments.stream().collect(java.util.stream.Collectors.toMap(
            Department::getId, department -> department));
        Set<Long> result = new LinkedHashSet<>();
        for (Long id : direct) {
            Department current = byId.get(id);
            while (current != null && result.add(current.getId())) {
                current = current.getParentId() == null ? null : byId.get(current.getParentId());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> toLongList(Object o) {
        if (o == null) {
            return List.of();
        }
        return ((List<Number>) o).stream().map(Number::longValue).toList();
    }

    private Department updateDepartment(Long id, Map<String, Object> body) {
        Department department = mapper.selectById(id);
        if (department == null) throw new BizException("NOT_FOUND", "部门不存在");
        if (body.containsKey("companyId") && (body.get("companyId") == null
            || !department.getCompanyId().equals(((Number) body.get("companyId")).longValue()))) {
            throw new BizException("DEPARTMENT_COMPANY_IMMUTABLE", "部门所属公司创建后不可修改");
        }
        if (body.containsKey("parentId")) {
            Long newParentId = body.get("parentId") == null
                ? null : ((Number) body.get("parentId")).longValue();
            department = service.move(id, newParentId);
        }
        if (body.containsKey("name")) department.setName((String) body.get("name"));
        if (body.containsKey("leaderId")) {
            Object leaderId = body.get("leaderId");
            service.setLeaders(id, leaderId == null ? List.of()
                : List.of(((Number) leaderId).longValue()));
            department.setLeaderId(leaderId == null ? null : ((Number) leaderId).longValue());
        }
        if (body.containsKey("leaderIds")) {
            Department leaders = service.setLeaders(id, toLongList(body.get("leaderIds")));
            department.setLeaderId(leaders.getLeaderId());
        }
        mapper.updateById(department);
        return service.withLeaderIds(department);
    }

    private static List<String> changedFields(Map<String, Object> body, List<String> allowed) {
        return allowed.stream().filter(body::containsKey).toList();
    }
}

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

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {
    private final DepartmentMapper mapper;
    private final DepartmentService service;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    /** 公司下完整部门树（含 ltree 子树递归） */
    @GetMapping
    public List<Department> tree(@RequestParam Long companyId) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_READ);
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
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE);
        Set<Long> userWrite = manageableDepartments(
            com.antflow.authz.PermissionCodes.ORG_USER_WRITE);
        return all.stream().filter(department -> included.contains(department.getId()))
            .peek(department -> {
                department.setContextOnly(!direct.contains(department.getId()));
                department.setCanReadMembers(userRead.contains(department.getId()));
                department.setCanManageDepartment(departmentWrite.contains(department.getId()));
                department.setCanManageUsers(userWrite.contains(department.getId()));
            }).toList();
    }

    @PostMapping
    public Department create(@RequestBody Department d) {
        authorizationService.requirePermission(com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE);
        if (d.getParentId() == null) {
            authorizationService.requireAllDataScope(com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE);
        } else {
            authorizationService.requireManageableDepartment(
                com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE, d.getParentId());
        }
        return auditService.execute(() -> service.create(d),
            created -> auditService.success("org.department.create", "DEPARTMENT",
                created.getId(), AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("companyId", "parentId", "name", "leaderIds")),
                Map.of("leaderCount", created.getLeaderIds().size())));
    }

    @PutMapping("/{id}")
    public Department update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE, id);
        if (body.containsKey("parentId")) {
            Long parentId = body.get("parentId") == null ? null
                : ((Number) body.get("parentId")).longValue();
            if (parentId == null) {
                authorizationService.requireAllDataScope(
                    com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE);
            } else {
                authorizationService.requireManageableDepartment(
                    com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE, parentId);
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
    public Department moveOrder(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE, id);
        return auditService.execute(
            () -> service.moveOrder(id, String.valueOf(body.get("direction"))),
            updated -> auditService.success("org.department.reorder", "DEPARTMENT", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("sortOrder")), Map.of()));
    }

    @PutMapping("/{id}/position")
    public Department movePosition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE, id);
        Object targetId = body.get("targetId");
        if (targetId == null) {
            throw new BizException("BAD_TARGET", "目标部门不能为空");
        }
        long targetDepartmentId = ((Number) targetId).longValue();
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE, targetDepartmentId);
        return auditService.execute(
            () -> service.movePosition(id, targetDepartmentId,
                String.valueOf(body.get("placement"))),
            updated -> auditService.success("org.department.reorder", "DEPARTMENT", id,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("sortOrder")),
                Map.of("targetDepartmentId", targetDepartmentId)));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        authorizationService.requireManageableDepartment(
            com.antflow.authz.PermissionCodes.ORG_DEPARTMENT_WRITE, id);
        auditService.execute(() -> service.delete(id),
            () -> auditService.success("org.department.delete", "DEPARTMENT", id,
                AuditService.RiskLevel.CRITICAL,
                Map.of("changedFields", List.of("deleted")), Map.of()));
    }

    @GetMapping("/{id}/path")
    public List<Department> path(@PathVariable Long id) {
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
        if (body.containsKey("name")) department.setName((String) body.get("name"));
        if (body.containsKey("leaderId")) {
            Object leaderId = body.get("leaderId");
            department.setLeaderId(leaderId == null ? null : ((Number) leaderId).longValue());
        }
        if (body.containsKey("leaderIds")) {
            department = service.setLeaders(id, toLongList(body.get("leaderIds")));
        }
        if (body.containsKey("parentId")) {
            Long newParentId = body.get("parentId") == null
                ? null : ((Number) body.get("parentId")).longValue();
            if (!java.util.Objects.equals(department.getParentId(), newParentId)) {
                return service.move(id, newParentId);
            }
        }
        mapper.updateById(department);
        return service.withLeaderIds(department);
    }

    private static List<String> changedFields(Map<String, Object> body, List<String> allowed) {
        return allowed.stream().filter(body::containsKey).toList();
    }
}

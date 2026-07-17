package com.antflow.org;

import com.antflow.engine.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class DepartmentController {
    private final DepartmentMapper mapper;
    private final DepartmentService service;

    /** 公司下完整部门树（含 ltree 子树递归） */
    @GetMapping
    public List<Department> tree(@RequestParam Long companyId) {
        return service.tree(companyId);
    }

    @PostMapping
    public Department create(@RequestBody Department d) {
        return service.create(d);
    }

    @PutMapping("/{id}")
    public Department update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Department d = mapper.selectById(id);
        if (d == null) throw new BizException("NOT_FOUND", "部门不存在");
        if (body.containsKey("name")) d.setName((String) body.get("name"));
        if (body.containsKey("leaderId")) {
            Object lid = body.get("leaderId");
            d.setLeaderId(lid == null ? null : ((Number) lid).longValue());
        }
        if (body.containsKey("leaderIds")) {
            d = service.setLeaders(id, toLongList(body.get("leaderIds")));
        }
        if (body.containsKey("parentId")) {
            Long newParentId = body.get("parentId") == null ? null : ((Number) body.get("parentId")).longValue();
            if (!java.util.Objects.equals(d.getParentId(), newParentId)) {
                return service.move(id, newParentId);
            }
        }
        mapper.updateById(d);
        return service.withLeaderIds(d);
    }

    @PutMapping("/{id}/order")
    public Department moveOrder(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return service.moveOrder(id, String.valueOf(body.get("direction")));
    }

    @PutMapping("/{id}/position")
    public Department movePosition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object targetId = body.get("targetId");
        if (targetId == null) {
            throw new BizException("BAD_TARGET", "目标部门不能为空");
        }
        return service.movePosition(id, ((Number) targetId).longValue(), String.valueOf(body.get("placement")));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/{id}/path")
    public List<Department> path(@PathVariable Long id) {
        return service.pathToRoot(id);
    }

    @SuppressWarnings("unchecked")
    private static List<Long> toLongList(Object o) {
        if (o == null) {
            return List.of();
        }
        return ((List<Number>) o).stream().map(Number::longValue).toList();
    }
}

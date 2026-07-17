package com.antflow.org;

import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DepartmentService {
    private final DepartmentMapper mapper;

    @Transactional
    public Department create(Department d) {
        if (d.getParentId() == null) {
            d.setPath(slugOf(d.getName()));
        } else {
            Department parent = mapper.selectById(d.getParentId());
            if (parent == null) throw new BizException("NOT_FOUND", "父部门不存在");
            d.setPath(parent.getPath() + "." + slugOf(d.getName()));
        }
        mapper.insert(d);
        return d;
    }

    /** 移动部门到新父节点：重算 ltree path 并传播到所有子孙。 */
    @Transactional
    public Department move(Long id, Long newParentId) {
        Department d = mapper.selectById(id);
        if (d == null) throw new BizException("NOT_FOUND", "部门不存在");
        if (Objects.equals(d.getParentId(), newParentId)) return d;
        // 防止把自己移到自己后代下
        String oldPath = d.getPath();
        if (newParentId != null) {
            Department newParent = mapper.selectById(newParentId);
            if (newParent == null) throw new BizException("NOT_FOUND", "目标父部门不存在");
            if (newParent.getPath().startsWith(oldPath + ".")) {
                throw new BizException("BAD_MOVE", "不能移动到自己的子部门下");
            }
            d.setPath(newParent.getPath() + "." + slugOf(d.getName()));
            d.setParentId(newParentId);
        } else {
            d.setPath(slugOf(d.getName()));
            d.setParentId(null);
        }
        mapper.updateById(d);
        // 递归更新所有子孙的 path
        List<Department> descendants = mapper.subtree(oldPath);
        for (Department child : descendants) {
            if (child.getId().equals(d.getId())) continue;
            String newChildPath = d.getPath() + child.getPath().substring(oldPath.length());
            child.setPath(newChildPath);
            mapper.updateById(child);
        }
        return d;
    }

    @Transactional
    public void delete(Long id) {
        Department d = mapper.selectById(id);
        if (d == null) throw new BizException("NOT_FOUND", "部门不存在");
        // 检查是否有子部门
        var children = mapper.selectList(new QueryWrapper<Department>().eq("parent_id", id));
        if (!children.isEmpty()) throw new BizException("HAS_CHILDREN", "请先删除子部门");
        mapper.deleteById(id);
    }

    /** 从根部门到当前部门的路径（面包屑用） */
    public List<Department> pathToRoot(Long id) {
        Department d = mapper.selectById(id);
        if (d == null || d.getPath() == null) return List.of();
        // ltree path 格式: root.dev.eng → 按 "."split 逐段查
        String[] parts = d.getPath().split("\\.");
        List<String> prefixes = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!sb.isEmpty()) sb.append(".");
            sb.append(p);
            prefixes.add(sb.toString());
        }
        if (prefixes.isEmpty()) return List.of();
        List<Department> all = mapper.selectList(
            new QueryWrapper<Department>().in("path", prefixes).orderByAsc("nlevel(path)"));
        return all;
    }

    /** 公司下的完整部门树 */
    public List<Department> tree(long companyId) {
        List<Department> roots = mapper.selectList(new QueryWrapper<Department>()
            .eq("company_id", companyId).isNull("parent_id"));
        if (roots.isEmpty()) return List.of();
        List<Department> all = new ArrayList<>();
        for (Department root : roots) {
            all.addAll(mapper.subtree(root.getPath()));
        }
        return all;
    }

    private String slugOf(String name) {
        return name.toLowerCase()
                   .replaceAll("[^a-z0-9]+", "_")
                   .replaceAll("^_|_$", "");
    }
}

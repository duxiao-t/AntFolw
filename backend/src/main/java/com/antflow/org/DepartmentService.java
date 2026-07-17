package com.antflow.org;

import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class DepartmentService {
    private final DepartmentMapper mapper;
    private final DepartmentLeaderMapper leaderMapper;
    private static final String DIRECTION_UP = "UP";
    private static final String DIRECTION_DOWN = "DOWN";
    private static final String PLACEMENT_BEFORE = "BEFORE";
    private static final String PLACEMENT_AFTER = "AFTER";

    @Transactional(rollbackFor = Exception.class)
    public Department create(Department d) {
        Long parentId = d.getParentId();
        Department parent = null;
        if (parentId != null) {
            parent = mapper.selectById(parentId);
            if (parent == null) {
                throw new BizException("NOT_FOUND", "父部门不存在");
            }
        }
        d.setPath("pending");
        d.setSortOrder(nextSortOrder(parentId));
        mapper.insert(d);
        d.setPath(buildPath(parent, d.getId()));
        mapper.updateById(d);
        return withLeaderIds(d);
    }

    private String buildPath(Department parent, Long id) {
        String segment = "d_" + id;
        if (parent == null) {
            return segment;
        }
        return parent.getPath() + "." + segment;
    }

    /** 移动部门到新父节点：重算 ltree path 并传播到所有子孙。 */
    @Transactional(rollbackFor = Exception.class)
    public Department move(Long id, Long newParentId) {
        Department d = mapper.selectById(id);
        if (d == null) {
            throw new BizException("NOT_FOUND", "部门不存在");
        }
        if (Objects.equals(d.getParentId(), newParentId)) {
            return d;
        }
        // 防止把自己移到自己后代下
        String oldPath = d.getPath();
        if (newParentId != null) {
            Department newParent = mapper.selectById(newParentId);
            if (newParent == null) {
                throw new BizException("NOT_FOUND", "目标父部门不存在");
            }
            if (newParent.getPath().startsWith(oldPath + ".")) {
                throw new BizException("BAD_MOVE", "不能移动到自己的子部门下");
            }
            d.setPath(buildPath(newParent, d.getId()));
            d.setParentId(newParentId);
        } else {
            d.setPath(buildPath(null, d.getId()));
            d.setParentId(null);
        }
        d.setSortOrder(nextSortOrder(newParentId));
        mapper.updateById(d);
        // 递归更新所有子孙的 path
        List<Department> descendants = mapper.subtree(oldPath);
        for (Department child : descendants) {
            if (child.getId().equals(d.getId())) {
                continue;
            }
            String newChildPath = d.getPath() + child.getPath().substring(oldPath.length());
            child.setPath(newChildPath);
            mapper.updateById(child);
        }
        return withLeaderIds(d);
    }

    @Transactional(rollbackFor = Exception.class)
    public Department moveOrder(Long id, String direction) {
        Department d = mapper.selectById(id);
        if (d == null) {
            throw new BizException("NOT_FOUND", "部门不存在");
        }
        List<Department> siblings = siblingsOf(d.getParentId());
        int index = -1;
        for (int i = 0; i < siblings.size(); i += 1) {
            if (Objects.equals(siblings.get(i).getId(), id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return withLeaderIds(d);
        }
        int targetIndex;
        if (DIRECTION_UP.equalsIgnoreCase(direction)) {
            targetIndex = index - 1;
        } else if (DIRECTION_DOWN.equalsIgnoreCase(direction)) {
            targetIndex = index + 1;
        } else {
            throw new BizException("BAD_DIRECTION", "排序方向错误");
        }
        if (targetIndex < 0 || targetIndex >= siblings.size()) {
            return withLeaderIds(d);
        }
        Department target = siblings.get(targetIndex);
        Integer currentOrder = d.getSortOrder();
        d.setSortOrder(target.getSortOrder());
        target.setSortOrder(currentOrder);
        mapper.updateById(target);
        mapper.updateById(d);
        return withLeaderIds(d);
    }

    @Transactional(rollbackFor = Exception.class)
    public Department movePosition(Long id, Long targetId, String placement) {
        Department d = mapper.selectById(id);
        Department target = mapper.selectById(targetId);
        if (d == null || target == null) {
            throw new BizException("NOT_FOUND", "部门不存在");
        }
        if (Objects.equals(id, targetId)) {
            return withLeaderIds(d);
        }
        if (!Objects.equals(d.getParentId(), target.getParentId())) {
            throw new BizException("BAD_SORT_TARGET", "只能在同级部门内排序");
        }

        List<Department> siblings = new ArrayList<>(siblingsOf(d.getParentId()));
        siblings.removeIf(item -> Objects.equals(item.getId(), id));
        int targetIndex = -1;
        for (int i = 0; i < siblings.size(); i += 1) {
            if (Objects.equals(siblings.get(i).getId(), targetId)) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) {
            return withLeaderIds(d);
        }
        if (PLACEMENT_AFTER.equalsIgnoreCase(placement)) {
            targetIndex += 1;
        } else if (!PLACEMENT_BEFORE.equalsIgnoreCase(placement)) {
            throw new BizException("BAD_PLACEMENT", "排序位置错误");
        }
        siblings.add(targetIndex, d);
        for (int i = 0; i < siblings.size(); i += 1) {
            Department sibling = siblings.get(i);
            int nextOrder = i + 1;
            if (!Objects.equals(sibling.getSortOrder(), nextOrder)) {
                sibling.setSortOrder(nextOrder);
                mapper.updateById(sibling);
            }
        }
        return withLeaderIds(d);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Department d = mapper.selectById(id);
        if (d == null) {
            throw new BizException("NOT_FOUND", "部门不存在");
        }
        // 检查是否有子部门
        var children = mapper.selectList(new QueryWrapper<Department>().eq("parent_id", id));
        if (!children.isEmpty()) {
            throw new BizException("HAS_CHILDREN", "请先删除子部门");
        }
        mapper.deleteById(id);
    }

    /** 从根部门到当前部门的路径（面包屑用） */
    public List<Department> pathToRoot(Long id) {
        List<Department> path = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Department current = mapper.selectById(id);
        while (current != null) {
            if (!seen.add(current.getId())) {
                throw new BizException("BAD_DEPT_TREE", "部门父级存在循环");
            }
            path.add(current);
            Long parentId = current.getParentId();
            current = parentId == null ? null : mapper.selectById(parentId);
        }
        Collections.reverse(path);
        fillLeaderIds(path);
        return path;
    }

    /** 公司下的完整部门树 */
    public List<Department> tree(long companyId) {
        List<Department> roots = mapper.selectList(new QueryWrapper<Department>()
            .eq("company_id", companyId).isNull("parent_id")
            .orderByAsc("sort_order").orderByAsc("id"));
        if (roots.isEmpty()) {
            return List.of();
        }
        List<Department> all = new ArrayList<>();
        for (Department root : roots) {
            all.addAll(flattenBySortOrder(root));
        }
        fillLeaderIds(all);
        return all;
    }

    @Transactional(rollbackFor = Exception.class)
    public Department setLeaders(Long departmentId, List<Long> userIds) {
        Department d = mapper.selectById(departmentId);
        if (d == null) {
            throw new BizException("NOT_FOUND", "部门不存在");
        }
        List<Long> distinctIds = userIds == null ? List.of() : userIds.stream().distinct().toList();
        leaderMapper.delete(new QueryWrapper<DepartmentLeader>().eq("department_id", departmentId));
        for (Long userId : distinctIds) {
            leaderMapper.insert(new DepartmentLeader(departmentId, userId));
        }
        d.setLeaderId(distinctIds.isEmpty() ? null : distinctIds.get(0));
        mapper.updateById(d);
        d.setLeaderIds(distinctIds);
        return d;
    }

    public Department withLeaderIds(Department d) {
        if (d == null) {
            return null;
        }
        d.setLeaderIds(leaderIdsOf(d.getId()));
        return d;
    }

    private void fillLeaderIds(List<Department> departments) {
        for (Department department : departments) {
            department.setLeaderIds(leaderIdsOf(department.getId()));
        }
    }

    private List<Long> leaderIdsOf(Long departmentId) {
        return leaderMapper.selectList(new QueryWrapper<DepartmentLeader>().eq("department_id", departmentId))
            .stream()
            .map(DepartmentLeader::getUserId)
            .toList();
    }

    private Integer nextSortOrder(Long parentId) {
        List<Department> siblings = siblingsOf(parentId);
        return siblings.stream()
            .map(Department::getSortOrder)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0) + 1;
    }

    private List<Department> siblingsOf(Long parentId) {
        QueryWrapper<Department> query = new QueryWrapper<Department>()
            .orderByAsc("sort_order").orderByAsc("id");
        if (parentId == null) {
            query.isNull("parent_id");
        } else {
            query.eq("parent_id", parentId);
        }
        return mapper.selectList(query);
    }

    private List<Department> flattenBySortOrder(Department root) {
        List<Department> result = new ArrayList<>();
        result.add(root);
        List<Department> children = siblingsOf(root.getId());
        for (Department child : children) {
            result.addAll(flattenBySortOrder(child));
        }
        return result;
    }
}

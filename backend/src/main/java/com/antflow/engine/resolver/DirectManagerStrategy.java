package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 制单人的第 N 级直属上级：assignedType=DIRECT_MANAGER。 */
@Component
@Order(31)
@RequiredArgsConstructor
public class DirectManagerStrategy implements AssigneeStrategy {
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;

    @Override public boolean supports(String type) { return "DIRECT_MANAGER".equals(type); }

    @Override
    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        int level = spec.hierarchyLevel();
        if (level < 1 || level > 10) {
            throw new NoAssigneeFoundException(nodeId, "直属上级层级必须为 1 到 10");
        }
        User starter = spec.starterId() == null ? null : userMapper.selectById(spec.starterId());
        Department starterDepartment = starter == null || starter.getDeptId() == null
            ? null : departmentMapper.selectById(starter.getDeptId());
        if (starter == null || starterDepartment == null) {
            throw new NoAssigneeFoundException(nodeId, "制单人没有有效的所属部门");
        }

        User current = starter;
        Set<Long> seen = new HashSet<>();
        seen.add(starter.getId());
        for (int currentLevel = 1; currentLevel <= level; currentLevel++) {
            if (current.getManagerId() == null) {
                throw missing(nodeId, currentLevel);
            }
            current = userMapper.selectById(current.getManagerId());
            if (current == null) throw missing(nodeId, currentLevel);
            if (!seen.add(current.getId())) {
                throw new NoAssigneeFoundException(nodeId, "直属上级关系存在循环");
            }
            if (!"ACTIVE".equals(current.getStatus())) {
                throw new NoAssigneeFoundException(nodeId,
                    "制单人的第 " + currentLevel + " 级直属上级已停用");
            }
            Department managerDepartment = current.getDeptId() == null
                ? null : departmentMapper.selectById(current.getDeptId());
            if (managerDepartment == null || !Objects.equals(starterDepartment.getCompanyId(),
                managerDepartment.getCompanyId())) {
                throw new NoAssigneeFoundException(nodeId,
                    "制单人的第 " + currentLevel + " 级直属上级不属于同一公司");
            }
        }
        return List.of(current.getId());
    }

    private static NoAssigneeFoundException missing(String nodeId, int level) {
        return new NoAssigneeFoundException(nodeId,
            "制单人缺少第 " + level + " 级直属上级");
    }
}

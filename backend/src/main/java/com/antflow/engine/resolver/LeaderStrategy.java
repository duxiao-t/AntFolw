package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 第 N 级主管：assignedType=LEADER，从发起人所在部门向上走 leaderLevel 级取 leader */
@Component
@Order(30)
@RequiredArgsConstructor
public class LeaderStrategy implements AssigneeStrategy {
    private final UserMapper userMapper;
    private final DepartmentMapper deptMapper;

    @Override public boolean supports(String type) { return "LEADER".equals(type); }

    @Override
    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        Long starterId = spec.starterId();
        if (starterId == null) throw new NoAssigneeFoundException(nodeId, "no starter for leader");
        User u = userMapper.selectById(starterId);
        if (u == null || u.getDeptId() == null) {
            throw new NoAssigneeFoundException(nodeId, "starter has no dept");
        }
        Department dept = deptMapper.selectById(u.getDeptId());
        int level = Math.max(1, spec.leaderLevel());
        for (int i = 1; i < level && dept != null; i++) {
            dept = dept.getParentId() == null ? null : deptMapper.selectById(dept.getParentId());
        }
        if (dept == null || dept.getLeaderId() == null) {
            throw new NoAssigneeFoundException(nodeId, "no leader at level " + level);
        }
        User leader = userMapper.selectById(dept.getLeaderId());
        if (leader == null || !"ACTIVE".equals(leader.getStatus())) {
            throw new NoAssigneeFoundException(nodeId, "leader inactive");
        }
        return List.of(leader.getId());
    }
}
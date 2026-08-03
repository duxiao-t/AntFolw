package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 连续多级主管：assignedType=LEADER_TOP，从发起人所在部门向上找 1..level 级所有
 * leader 作为多人审批人（OR-sign 模式）。用于 HR 场景：依次找直接主管、主管的主管等。
 *
 * <p>Sprint 3 新增的 assignedType 与 {@link LeaderStrategy}（单级）的区别：
 * <ul>
 *   <li>LEADER：取发起人部门向上 level 级那一个 leader（单点）</li>
 *   <li>LEADER_TOP：取 1..level 各级部门的 leader，作为多个审批人</li>
 * </ul>
 */
@Component
@Order(35)
@RequiredArgsConstructor
public class LeaderTopStrategy implements AssigneeStrategy {

    private final UserMapper userMapper;
    private final DepartmentMapper deptMapper;

    @Override public boolean supports(String type) { return "LEADER_TOP".equals(type); }

    @Override
    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        Long starterId = spec.starterId();
        if (starterId == null) throw new NoAssigneeFoundException(nodeId, "no starter for leader_top");
        User starter = userMapper.selectById(starterId);
        if (starter == null || starter.getDeptId() == null) {
            throw new NoAssigneeFoundException(nodeId, "starter has no dept");
        }
        int level = Math.max(1, spec.leaderLevel());
        var bag = new ArrayList<Long>();
        Department dept = deptMapper.selectById(starter.getDeptId());
        for (int i = 0; i < level && dept != null; i++) {
            if (dept.getLeaderId() != null) {
                User leader = userMapper.selectById(dept.getLeaderId());
                if (leader != null && "ACTIVE".equals(leader.getStatus())) {
                    bag.add(leader.getId());
                }
            }
            dept = dept.getParentId() == null ? null : deptMapper.selectById(dept.getParentId());
        }
        if (bag.isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no leaders found in chain up to level " + level);
        }
        return bag.stream().distinct().toList();
    }
}
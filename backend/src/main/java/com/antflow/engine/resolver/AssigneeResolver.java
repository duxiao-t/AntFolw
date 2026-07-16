package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.RoleMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.org.UserRole;
import com.antflow.org.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AssigneeResolver {
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final DepartmentMapper deptMapper;

    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        return switch (spec.type()) {
            case "ASSIGN_USER" -> resolveUsers(nodeId, spec.ids());
            case "ROLE"        -> resolveRoles(nodeId, spec.ids());
            case "LEADER"      -> resolveLeader(nodeId, spec.starterId(), spec.leaderLevel());
            case "SELF"        -> List.of(requireStarter(nodeId, spec.starterId()));
            case "SELF_SELECT" -> requireNonEmpty(nodeId, spec.selfSelected());
            default -> throw new IllegalArgumentException("unknown assignee type: " + spec.type());
        };
    }

    private Long requireStarter(String nodeId, Long starterId) {
        if (starterId == null) throw new NoAssigneeFoundException(nodeId, "no starter");
        return starterId;
    }

    private List<Long> requireNonEmpty(String nodeId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new NoAssigneeFoundException(nodeId, "self-select empty");
        return ids;
    }

    /** 从发起人所在部门向上走 level 级，取该级部门的 leaderId。 */
    private List<Long> resolveLeader(String nodeId, Long starterId, int level) {
        if (starterId == null) throw new NoAssigneeFoundException(nodeId, "no starter for leader");
        User u = userMapper.selectById(starterId);
        if (u == null || u.getDeptId() == null) throw new NoAssigneeFoundException(nodeId, "starter has no dept");
        Department dept = deptMapper.selectById(u.getDeptId());
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

    private List<Long> resolveUsers(String nodeId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no users specified");
        }
        var active = userMapper.selectBatchIds(ids).stream()
            .filter(u -> "ACTIVE".equals(u.getStatus()))
            .map(u -> u.getId())
            .toList();
        if (active.isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no active users among ids");
        }
        return active;
    }

    private List<Long> resolveRoles(String nodeId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no roles specified");
        }
        var bag = new ArrayList<Long>();
        for (Long rid : ids) {
            userRoleMapper.selectList(new QueryWrapper<UserRole>().eq("role_id", rid))
                .forEach(ur -> {
                    var u = userMapper.selectById(ur.getUserId());
                    if (u != null && "ACTIVE".equals(u.getStatus())) bag.add(u.getId());
                });
        }
        if (bag.isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no active users in role");
        }
        return bag.stream().distinct().toList();
    }
}

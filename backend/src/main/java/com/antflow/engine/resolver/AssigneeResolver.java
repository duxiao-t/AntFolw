package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.org.RoleMapper;
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

    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        return switch (spec.type()) {
            case "user"        -> resolveUsers(nodeId, spec);
            case "role"        -> resolveRoles(nodeId, spec);
            case "dept_leader" -> throw new IllegalStateException(
                "dept_leader resolver is wired in a later phase");
            default            -> throw new IllegalArgumentException(
                "unknown assignee type: " + spec.type());
        };
    }

    private List<Long> resolveUsers(String nodeId, AssigneeSpec spec) {
        if (spec.ids() == null || spec.ids().isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no users specified");
        }
        var ids = spec.ids().stream().map(Number.class::cast).map(Number::longValue).toList();
        var active = userMapper.selectBatchIds(ids).stream()
            .filter(u -> "ACTIVE".equals(u.getStatus()))
            .map(u -> u.getId())
            .toList();
        if (active.isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no active users among ids");
        }
        return active;
    }

    private List<Long> resolveRoles(String nodeId, AssigneeSpec spec) {
        if (spec.ids() == null || spec.ids().isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no roles specified");
        }
        var bag = new ArrayList<Long>();
        for (Object ridObj : spec.ids()) {
            long rid = ((Number) ridObj).longValue();
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

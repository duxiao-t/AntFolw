package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.org.UserRole;
import com.antflow.org.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 角色：assignedType=ROLE，按 role id 取所有活跃用户 */
@Component
@Order(20)
@RequiredArgsConstructor
public class RoleStrategy implements AssigneeStrategy {
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;

    @Override public boolean supports(String type) { return "ROLE".equals(type); }

    @Override
    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        if (spec.ids() == null || spec.ids().isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no roles specified");
        }
        var bag = new ArrayList<Long>();
        for (Long rid : spec.ids()) {
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
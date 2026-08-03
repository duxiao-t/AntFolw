package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 指定成员：assignedType=ASSIGN_USER，按 ids 列表取活跃用户 */
@Component
@Order(10)
@RequiredArgsConstructor
public class AssignUserStrategy implements AssigneeStrategy {
    private final UserMapper userMapper;

    @Override public boolean supports(String type) { return "ASSIGN_USER".equals(type); }

    @Override
    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        if (spec.ids() == null || spec.ids().isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no users specified");
        }
        var active = userMapper.selectBatchIds(spec.ids()).stream()
            .filter(u -> "ACTIVE".equals(u.getStatus()))
            .map(User::getId)
            .toList();
        if (active.isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no active users among ids");
        }
        return active;
    }
}
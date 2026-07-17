package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 发起人自选：assignedType=SELF_SELECT，selfSelected 来自 start() 上送的 selfSelected[nodeId] */
@Component
@Order(50)
public class SelfSelectStrategy implements AssigneeStrategy {

    @Override public boolean supports(String type) { return "SELF_SELECT".equals(type); }

    @Override
    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        var ids = spec.selfSelected();
        if (ids == null || ids.isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "self-select empty");
        }
        return ids;
    }
}
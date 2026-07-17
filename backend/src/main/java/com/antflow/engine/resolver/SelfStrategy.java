package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 发起人本人：assignedType=SELF */
@Component
@Order(40)
public class SelfStrategy implements AssigneeStrategy {

    @Override public boolean supports(String type) { return "SELF".equals(type); }

    @Override
    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        if (spec.starterId() == null) {
            throw new NoAssigneeFoundException(nodeId, "no starter");
        }
        return List.of(spec.starterId());
    }
}
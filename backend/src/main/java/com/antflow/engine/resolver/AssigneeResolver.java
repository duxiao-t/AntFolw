package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审批人解析器：Spring 自动注入所有 {@link AssigneeStrategy} 实现，按 Order 排序，
 * 第一个 {@code supports(assignedType)} 匹配的执行解析。新增类型只需加一个
 * 带 {@code @Component} 的 strategy，无需修改本类。
 *
 * <p>Sprint 2 C1 重构后已无 switch-case；旧的 resolveUsers/resolveRoles/resolveLeader
 * 被移到对应的 strategy 类里。
 */
@Component
@RequiredArgsConstructor
public class AssigneeResolver {

    private final List<AssigneeStrategy> strategies;

    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        for (AssigneeStrategy s : strategies) {
            if (s.supports(spec.type())) {
                return s.resolve(nodeId, spec);
            }
        }
        throw new NoAssigneeFoundException(nodeId, "unknown assignee type: " + spec.type());
    }
}
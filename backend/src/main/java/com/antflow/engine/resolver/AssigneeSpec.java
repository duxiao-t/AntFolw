package com.antflow.engine.resolver;

import java.util.List;

public record AssigneeSpec(String type, List<?> ids) {
    public static AssigneeSpec user(List<Long> ids) { return new AssigneeSpec("user", ids); }
    public static AssigneeSpec role(List<Long> ids) { return new AssigneeSpec("role", ids); }
    public static AssigneeSpec deptLeader() { return new AssigneeSpec("dept_leader", List.of()); }
}

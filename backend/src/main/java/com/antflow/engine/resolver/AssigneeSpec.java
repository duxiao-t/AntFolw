package com.antflow.engine.resolver;

import java.util.List;

/**
 * 审批人解析输入。
 * @param type ASSIGN_USER | ROLE | LEADER | DIRECT_MANAGER | SELF | SELF_SELECT
 * @param ids  ASSIGN_USER→用户id；ROLE→角色id
 * @param hierarchyLevel LEADER、DIRECT_MANAGER 的层级
 * @param starterId   发起人（SELF、LEADER、DIRECT_MANAGER 起点）
 * @param selfSelected SELF_SELECT 时该节点上发起人已选的用户
 */
public record AssigneeSpec(String type, List<Long> ids, int hierarchyLevel,
                           Long starterId, List<Long> selfSelected) {
    public static AssigneeSpec of(String type, List<Long> ids) {
        return new AssigneeSpec(type, ids, 1, null, List.of());
    }
}

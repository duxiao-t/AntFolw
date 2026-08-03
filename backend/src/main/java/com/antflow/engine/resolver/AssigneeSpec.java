package com.antflow.engine.resolver;

import java.util.List;

/**
 * 审批人解析输入。
 * @param type ASSIGN_USER | ROLE | LEADER | SELF | SELF_SELECT
 * @param ids  ASSIGN_USER→用户id；ROLE→角色id
 * @param leaderLevel LEADER 的层级（1=直接主管）
 * @param starterId   发起人（SELF、LEADER 起点）
 * @param selfSelected SELF_SELECT 时该节点上发起人已选的用户
 */
public record AssigneeSpec(String type, List<Long> ids, int leaderLevel,
                           Long starterId, List<Long> selfSelected) {
    public static AssigneeSpec of(String type, List<Long> ids) {
        return new AssigneeSpec(type, ids, 1, null, List.of());
    }
}

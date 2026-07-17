package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;

import java.util.List;

/**
 * 审批人解析策略。
 *
 * <p>每个实现类只关心一种 {@code assignedType}；新增类型只需加一个带
 * {@link org.springframework.stereotype.Component} 的 {@code @Component}，
 * 无需修改 {@link AssigneeResolver} 的 switch。这是 Sprint 2 的 C1 抽象目标。
 *
 * <p>实现类应当抛出 {@link NoAssigneeFoundException}（而不是返回空列表）
 * 以让引擎走到 props.nobody.handler (TO_PASS / TO_REFUSE) 分支。
 */
public interface AssigneeStrategy {
    /** 是否支持该 assignedType；engine 通过遍历 strategies 找到第一个匹配的来路由 */
    boolean supports(String assignedType);

    /**
     * @param nodeId 当前审批节点 id（用于错误信息）
     * @param spec   审批人参数（ids / leaderLevel / starterId / selfSelected）
     * @return 用户 id 列表；为空时引擎会按 nobody.handler 处理
     */
    List<Long> resolve(String nodeId, AssigneeSpec spec);
}
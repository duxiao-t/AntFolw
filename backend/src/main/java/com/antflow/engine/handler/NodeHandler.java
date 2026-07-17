package com.antflow.engine.handler;

import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 节点处理器接口 — Sprint 2 C2 抽象。
 *
 * <p>每个实现对应一种节点类型 (APPROVAL / CC / CONDITIONS / CONCURRENTS / DELAY ...)；
 * 新增节点类型只需加一个带 {@code @Component} 的 handler，引擎入口
 * {@code ProcessEngine.resolveAndLandLoop} 会按 supports 顺序路由。
 *
 * <p>handler 负责"当前节点如何被处理"——建任务、跳过、写历史、变更实例状态等；
 * 调用 {@link NodeOutcome} 告诉引擎下一步往哪儿走。
 */
public interface NodeHandler {
    /** 该 handler 处理哪种节点类型（与 process JSON 里的 type 字段值一致） */
    boolean supports(String type);

    /**
     * @param root          流程树根（用于历史）
     * @param node          当前正在处理的节点 JSON
     * @param pi            当前实例
     * @param ctx           运行时上下文（starterId / formData / selfSelected / fromNodeId）
     * @return 处理结果（next node / 终止 / 异常）
     */
    NodeOutcome handle(JsonNode root, JsonNode node, ProcessInstance pi, NodeContext ctx);
}
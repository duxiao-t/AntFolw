package com.antflow.engine.handler;

import com.antflow.engine.BizException;
import com.antflow.engine.condition.ConditionEvaluator;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PARALLEL 节点（并行网关）：同时落地始终执行或条件命中的分支，每个分支独立推进；
 * 分支内遇到 APPROVAL 建任务后暂停等待，所有分支都完成后汇聚到 children。
 *
 * <p>v1 约束（发布校验保证）：分支内为单链 APPROVAL/CC/EMPTY，
 * 不允许嵌套 CONDITIONS/PARALLEL；汇聚后继 children 必须非空。
 */
@Component
@Order(12)
@RequiredArgsConstructor
public class ParallelHandler implements NodeHandler {

    private final List<NodeHandler> nodeHandlers;
    private final ConditionEvaluator conditionEvaluator;

    @Override public boolean supports(String type) { return "PARALLEL".equals(type); }

    @Override
    public NodeOutcome handle(JsonNode root, JsonNode node, ProcessInstance pi, NodeContext ctx) {
        JsonNode branchs = node.path("branchs");
        if (!branchs.isArray() || branchs.size() < 2) {
            throw new BizException("BAD_FLOW", "并行网关至少需要 2 个分支");
        }
        List<Long> allTaskIds = new ArrayList<>();
        boolean allCompleted = true;
        int activeBranchCount = 0;
        for (JsonNode branch : branchs) {
            if (!shouldExecute(branch, ctx.formData())) {
                continue;
            }
            activeBranchCount++;
            JsonNode inner = ProcessTreeNav.childrenOf(branch);
            if (inner == null) {
                // 空分支视为已完成（发布校验会拒绝，这里防御性跳过）
                continue;
            }
            NodeContext branchCtx = new NodeContext(ctx.starterId(), ctx.formData(),
                ctx.selfSelected(), node.path("id").asText(null),
                node.path("id").asText(null), branch.path("id").asText(null));
            BranchResult result = landBranch(root, pi, branchCtx, inner);
            allTaskIds.addAll(result.taskIds());
            if (!result.completed()) {
                allCompleted = false;
            }
        }
        if (activeBranchCount == 0) {
            throw new BizException("BAD_FLOW", "并行网关没有可执行分支");
        }
        if (allCompleted) {
            return NodeOutcome.next(ProcessTreeNav.childrenOf(node));
        }
        return NodeOutcome.halt(allTaskIds);
    }

    private boolean shouldExecute(JsonNode branch, JsonNode formData) {
        JsonNode props = branch.path("props");
        String mode = props.path("conditionMode").asText("ALWAYS");
        return switch (mode) {
            case "ALWAYS" -> true;
            case "WHEN_MATCHED" -> conditionEvaluator.matches(props, formData);
            default -> throw new BizException("BAD_FLOW", "并行分支执行方式无效: " + mode);
        };
    }

    /** 单条分支的顺序落地：沿分支单链推进，遇 HALT 收集任务暂停，遇末端/END 标记完成。 */
    private BranchResult landBranch(JsonNode root, ProcessInstance pi,
                                    NodeContext ctx, JsonNode startNode) {
        JsonNode node = startNode;
        List<Long> taskIds = new ArrayList<>();
        while (true) {
            if (node == null || node.isNull() || !node.has("id")) {
                return BranchResult.completed(taskIds);
            }
            String type = node.path("type").asText();
            if (!"APPROVAL".equals(type) && !"CC".equals(type)) {
                throw new BizException("BAD_FLOW", "并行分支内只允许审批和抄送节点: " + type);
            }
            NodeHandler handler = pickHandler(type);
            if (handler == null) {
                throw new BizException("BAD_NODE_TYPE", "未识别节点类型: " + type);
            }
            NodeOutcome outcome = handler.handle(root, node, pi, ctx);
            switch (outcome.type()) {
                case NEXT -> {
                    ctx = new NodeContext(ctx.starterId(), ctx.formData(), ctx.selfSelected(),
                        node.path("id").asText(null), ctx.parallelId(), ctx.branchId());
                    node = outcome.node();
                }
                case JUMP -> {
                    ctx = new NodeContext(ctx.starterId(), ctx.formData(), ctx.selfSelected(),
                        node.path("id").asText(null), ctx.parallelId(), ctx.branchId());
                    node = outcome.node();
                }
                case END -> {
                    return BranchResult.completed(taskIds);
                }
                case HALT -> {
                    if (outcome instanceof NodeOutcome.Halt h) {
                        taskIds.addAll(h.newTaskIds());
                    }
                    return BranchResult.waiting(taskIds);
                }
                default -> throw new IllegalStateException("unexpected outcome: " + outcome.type());
            }
        }
    }

    private NodeHandler pickHandler(String type) {
        for (NodeHandler h : nodeHandlers) {
            if (h.supports(type)) return h;
        }
        return null;
    }

    private record BranchResult(boolean completed, List<Long> taskIds) {
        static BranchResult completed(List<Long> taskIds) {
            return new BranchResult(true, taskIds);
        }
        static BranchResult waiting(List<Long> taskIds) {
            return new BranchResult(false, taskIds);
        }
    }
}


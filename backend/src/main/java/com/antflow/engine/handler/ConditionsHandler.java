package com.antflow.engine.handler;

import com.antflow.engine.BizException;
import com.antflow.engine.condition.ConditionEvaluator;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** CONDITIONS 节点：按 formData 选第一个匹配的分支；若全不匹配且无 isDefault 分支则抛错。 */
@Component
@Order(15)
@RequiredArgsConstructor
public class ConditionsHandler implements NodeHandler {

    private final ConditionEvaluator evaluator;

    @Override public boolean supports(String type) { return "CONDITIONS".equals(type); }

    @Override
    public NodeOutcome handle(JsonNode root, JsonNode node, ProcessInstance pi, NodeContext ctx) {
        JsonNode chosen = null;
        for (JsonNode b : node.withArray("branchs")) {
            if (evaluator.matches(b.path("props"), ctx.formData())) {
                chosen = b;
                break;
            }
        }
        if (chosen == null) {
            throw new BizException("BAD_FLOW", "无匹配条件分支");
        }
        JsonNode inner = ProcessTreeNav.childrenOf(chosen);
        return NodeOutcome.next(inner != null ? inner : ProcessTreeNav.childrenOf(node));
    }
}
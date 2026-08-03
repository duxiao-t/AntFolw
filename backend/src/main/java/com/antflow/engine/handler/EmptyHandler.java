package com.antflow.engine.handler;

import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** EMPTY 占位节点：透明跳过，沿 children 继续。 */
@Component
@Order(5)
public class EmptyHandler implements NodeHandler {

    @Override public boolean supports(String type) { return "EMPTY".equals(type); }

    @Override
    public NodeOutcome handle(JsonNode root, JsonNode node, ProcessInstance pi, NodeContext ctx) {
        return NodeOutcome.next(ProcessTreeNav.childrenOf(node));
    }
}
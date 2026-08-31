package com.antflow.engine.handler;

import com.antflow.automation.WorkflowJobService;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
@RequiredArgsConstructor
public class TriggerHandler implements NodeHandler {
    private final WorkflowJobService jobs;

    @Override
    public boolean supports(String type) {
        return "TRIGGER".equals(type);
    }

    @Override
    public NodeOutcome handle(JsonNode root, JsonNode node, ProcessInstance instance, NodeContext context) {
        var creation = jobs.queueTrigger(instance, node, context.formData(), context.starterId());
        if (!Boolean.TRUE.equals(creation.job().getBlocking())
            || "SUCCEEDED".equals(creation.job().getStatus())) {
            return NodeOutcome.next(ProcessTreeNav.next(root, node, context.parallelId()));
        }
        instance.setCurrentNodeId(node.path("id").asText());
        return NodeOutcome.halt(java.util.List.of());
    }
}

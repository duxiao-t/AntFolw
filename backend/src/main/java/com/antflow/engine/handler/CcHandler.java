package com.antflow.engine.handler;

import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskHistoryEntity;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** CC 节点：建 CC 任务（不阻塞，沿单链继续） */
@Component
@Order(20)
@RequiredArgsConstructor
public class CcHandler implements NodeHandler {

    private final TaskMapper taskMapper;
    private final TaskHistoryMapper historyMapper;

    @Override public boolean supports(String type) { return "CC".equals(type); }

    @Override
    public NodeOutcome handle(JsonNode root, JsonNode node, ProcessInstance pi, NodeContext ctx) {
        var ccUsers = readIds(node.path("props").path("assignedUser"));
        for (Long u : ccUsers) {
            TaskEntity ct = new TaskEntity();
            ct.setProcInstId(pi.getId());
            ct.setNodeId(node.path("id").asText());
            ct.setAssigneeId(u);
            ct.setStatus("CC");
            ct.setApprovalMode("OR");
            taskMapper.insert(ct);
        }
        var h = new TaskHistoryEntity();
        h.setProcInstId(pi.getId());
        h.setFromNodeId(ctx.fromNodeId());
        h.setToNodeId(node.path("id").asText());
        h.setAction("CC");
        h.setOperatorId(ctx.starterId());
        historyMapper.insert(h);
        return NodeOutcome.next(ProcessTreeNav.childrenOf(node));
    }

    private static List<Long> readIds(JsonNode arr) {
        var out = new ArrayList<Long>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode x : arr) {
            if (x.isNumber()) out.add(x.asLong());
            else if (x.isTextual()) {
                try { out.add(Long.parseLong(x.asText())); } catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }
}
package com.antflow.engine.handler;

import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.engine.resolver.AssigneeSpec;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** APPROVAL 节点：解析审批人、建 PENDING 任务、ARRIVE 历史。建完后 HALT 等待人工。 */
@Component
@Order(10)
@RequiredArgsConstructor
public class ApprovalHandler implements NodeHandler {

    private final AssigneeResolver assigneeResolver;
    private final TaskMapper taskMapper;
    private final TaskHistoryMapper historyMapper;

    @Override public boolean supports(String type) { return "APPROVAL".equals(type); }

    @Override
    public NodeOutcome handle(JsonNode root, JsonNode node, ProcessInstance pi, NodeContext ctx) {
        String nodeId = node.path("id").asText();
        AssigneeSpec spec = parseAssignee(node, ctx);
        List<Long> assignees;
        try {
            assignees = assigneeResolver.resolve(nodeId, spec);
        } catch (NoAssigneeFoundException e) {
            String handler = node.path("props").path("nobody").path("handler").asText("TO_PASS");
            if ("TO_PASS".equals(handler)) {
                historyMapper.insert(historyRow(pi.getId(), ctx.fromNodeId(), nodeId, "AUTO_PASS", ctx.starterId(), null));
                return NodeOutcome.next(ProcessTreeNav.childrenOf(node));
            }
            // TO_REFUSE 或未配置 → 终止实例
            pi.setStatus("REJECTED");
            pi.setFinishedAt(java.time.OffsetDateTime.now());
            historyMapper.insert(historyRow(pi.getId(), ctx.fromNodeId(), nodeId, "REJECT", ctx.starterId(), "no assignee"));
            return NodeOutcome.end();
        }
        String mode = node.path("props").path("mode").asText("OR");
        List<Long> ids = new ArrayList<>();
        for (Long a : assignees) {
            TaskEntity nt = new TaskEntity();
            nt.setProcInstId(pi.getId());
            nt.setNodeId(nodeId);
            nt.setAssigneeId(a);
            nt.setStatus("PENDING");
            nt.setApprovalMode(mode);
            taskMapper.insert(nt);
            ids.add(nt.getId());
        }
        pi.setCurrentNodeId(nodeId);
        historyMapper.insert(historyRow(pi.getId(), ctx.fromNodeId(), nodeId, "ARRIVE", ctx.starterId(), null));
        return NodeOutcome.halt(ids);
    }

    private static AssigneeSpec parseAssignee(JsonNode node, NodeContext ctx) {
        JsonNode props = node.path("props");
        String type = props.path("assignedType").asText();
        switch (type) {
            case "ASSIGN_USER":
                return AssigneeSpec.of("ASSIGN_USER", readIds(props.path("assignedUser")));
            case "ROLE":
                return AssigneeSpec.of("ROLE", readIds(props.path("role")));
            case "LEADER": {
                int level = props.path("leader").path("level").asInt(1);
                return new AssigneeSpec("LEADER", List.of(), level, ctx.starterId(), List.of());
            }
            case "SELF":
                return new AssigneeSpec("SELF", List.of(), 1, ctx.starterId(), List.of());
            case "SELF_SELECT":
                return new AssigneeSpec("SELF_SELECT", List.of(), 1, ctx.starterId(),
                    ctx.selfSelected() == null ? List.of() :
                        ctx.selfSelected().getOrDefault(node.path("id").asText(), List.of()));
            default:
                throw new IllegalArgumentException("未识别审批人类型: " + type);
        }
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

    private static com.antflow.task.TaskHistoryEntity historyRow(
            Long instId, String from, String to, String action, Long op, String comment) {
        var h = new com.antflow.task.TaskHistoryEntity();
        h.setProcInstId(instId);
        h.setFromNodeId(from);
        h.setToNodeId(to);
        h.setAction(action);
        h.setOperatorId(op);
        h.setComment(comment);
        return h;
    }
}

package com.antflow.engine.handler;

import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.engine.WorkflowRuntimeV2;
import com.antflow.engine.resolver.ApprovalAssigneeSpecs;
import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.engine.resolver.AssigneeSpec;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.antflow.task.TaskHistoryMapper;
import com.antflow.task.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired(required = false)
    private WorkflowRuntimeV2 runtimeV2;

    @Override public boolean supports(String type) { return "APPROVAL".equals(type); }

    @Override
    public NodeOutcome handle(JsonNode root, JsonNode node, ProcessInstance pi, NodeContext ctx) {
        String nodeId = node.path("id").asText();
        if (runtimeV2 != null && runtimeV2.shouldSkipResubmittedNode(root, pi, node)) {
            historyMapper.insert(historyRow(pi.getId(), ctx.fromNodeId(), nodeId,
                "AUTO_PASS", ctx.starterId(), "unchanged since previous round"));
            runtimeV2.completeNode(ctx.nodeInstanceId(), "AUTO_PASSED");
            return NodeOutcome.next(ProcessTreeNav.next(root, node, ctx.parallelId()));
        }
        AssigneeSpec spec = null;
        List<Long> assignees;
        try {
            if (runtimeV2 != null && runtimeV2.active(pi)
                && "FIELD_USER".equals(node.path("props").path("assignedType").asText())) {
                assignees = runtimeV2.fieldUsers(node, ctx);
            } else {
                spec = ApprovalAssigneeSpecs.from(node, ctx.starterId(), ctx.selfSelected());
                assignees = assigneeResolver.resolve(nodeId, spec);
            }
        } catch (NoAssigneeFoundException e) {
            if (runtimeV2 != null && runtimeV2.active(pi)) {
                assignees = runtimeV2.fallbackUsers(root, node);
                if (!assignees.isEmpty()) {
                    return landTasks(root, node, pi, ctx, assignees, "FALLBACK");
                }
            }
            if (spec != null && "DIRECT_MANAGER".equals(spec.type())) throw e;
            String handler = node.path("props").path("nobody").path("handler").asText("TO_PASS");
            if ("TO_PASS".equals(handler)) {
                historyMapper.insert(historyRow(pi.getId(), ctx.fromNodeId(), nodeId, "AUTO_PASS", ctx.starterId(), null));
                return NodeOutcome.next(ProcessTreeNav.next(root, node, ctx.parallelId()));
            }
            // TO_REFUSE 或未配置 → 终止实例
            pi.setStatus("REJECTED");
            pi.setFinishedAt(java.time.OffsetDateTime.now());
            historyMapper.insert(historyRow(pi.getId(), ctx.fromNodeId(), nodeId, "REJECT", ctx.starterId(), "no assignee"));
            return NodeOutcome.end();
        }
        if (runtimeV2 != null && runtimeV2.active(pi) && assignees.isEmpty()) {
            assignees = runtimeV2.fallbackUsers(root, node);
        }
        return landTasks(root, node, pi, ctx, assignees, "RULE");
    }

    private NodeOutcome landTasks(JsonNode root, JsonNode node, ProcessInstance pi,
                                  NodeContext ctx, List<Long> assignees, String source) {
        String nodeId = node.path("id").asText();
        if (runtimeV2 != null && runtimeV2.shouldAutoPass(root, pi, assignees)) {
            historyMapper.insert(historyRow(pi.getId(), ctx.fromNodeId(), nodeId,
                "AUTO_PASS", assignees.get(0), "same approver policy"));
            runtimeV2.completeNode(ctx.nodeInstanceId(), "AUTO_PASSED");
            return NodeOutcome.next(ProcessTreeNav.next(root, node, ctx.parallelId()));
        }
        String mode = runtimeV2 != null && runtimeV2.active(pi)
            ? WorkflowRuntimeV2.mode(node)
            : node.path("props").path("mode").asText("OR");
        List<WorkflowRuntimeV2.Assignment> assignments = runtimeV2 != null
            ? runtimeV2.assignments(pi, node, ctx, assignees, source)
            : assignees.stream().map(id -> new WorkflowRuntimeV2.Assignment(id, id, 1)).toList();
        List<Long> ids = new ArrayList<>();
        for (WorkflowRuntimeV2.Assignment assignment : assignments) {
            TaskEntity nt = new TaskEntity();
            nt.setProcInstId(pi.getId());
            nt.setNodeId(nodeId);
            nt.setAssigneeId(assignment.actualUserId());
            nt.setTaskType("APPROVAL");
            nt.setStatus("PENDING");
            nt.setApprovalMode(mode);
            nt.setParallelId(ctx.parallelId());
            nt.setBranchId(ctx.branchId());
            if (runtimeV2 != null) runtimeV2.bindTask(nt, ctx, assignment, pi, node);
            taskMapper.insert(nt);
            ids.add(nt.getId());
            if (runtimeV2 != null && runtimeV2.active(pi)) {
                runtimeV2.outbox(pi.getId(), nt.getAssigneeId(), "TASK_ASSIGNED", nt.getId());
                runtimeV2.scheduleTimeout(nt, node);
            }
        }
        pi.setCurrentNodeId(nodeId);
        historyMapper.insert(historyRow(pi.getId(), ctx.fromNodeId(), nodeId, "ARRIVE", ctx.starterId(), null));
        return NodeOutcome.halt(ids);
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

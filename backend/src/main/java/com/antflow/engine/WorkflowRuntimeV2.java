package com.antflow.engine;

import com.antflow.engine.handler.NodeContext;
import com.antflow.engine.tree.ProcessTreeNav;
import com.antflow.form.runtime.FormData;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.process.ProcessDefinition;
import com.antflow.automation.WorkflowJob;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runtime state that exists only for engine_version=2 instances. */
@Service
@RequiredArgsConstructor
public class WorkflowRuntimeV2 {
    private final JdbcTemplate jdbc;
    private final DefinitionVersionRepository versions;
    private final ObjectMapper json;

    public boolean active(ProcessInstance instance) {
        return instance != null && Objects.equals(instance.getEngineVersion(), 2);
    }

    public String processTree(ProcessInstance instance) {
        if (!active(instance) || instance.getProcessDefinitionVersionId() == null) {
            return instance.getProcessSnapshot();
        }
        String process = jdbc.query("""
            SELECT process::text FROM t_process_definition_version WHERE id = ?
            """, rs -> rs.next() ? rs.getString(1) : null,
            instance.getProcessDefinitionVersionId());
        if (process == null) throw new BizException("VERSION_NOT_FOUND", "process version not found");
        return process;
    }

    public String formSchema(ProcessInstance instance) {
        if (!active(instance) || instance.getCurrentFormRevisionId() == null) return null;
        return jdbc.query("""
            SELECT version.schema::text
            FROM t_form_data_revision revision
            JOIN t_form_definition_version version
              ON version.id = revision.form_definition_version_id
            WHERE revision.id = ?
            """, rs -> rs.next() ? rs.getString(1) : null,
            instance.getCurrentFormRevisionId());
    }

    public StartState prepareStart(FormData data, ProcessDefinition process, long actorId) {
        long revisionId = versions.createRevision(data, "SUBMITTED", "INITIAL_SUBMIT", actorId);
        long processVersionId = versions.processVersionId(process.getId(), process.getVersion());
        return new StartState(processVersionId, revisionId);
    }

    public long enterNode(ProcessInstance instance, JsonNode node, NodeContext context) {
        if (!active(instance)) return 0;
        String nodeId = node.path("id").asText();
        Long existing = jdbc.query("""
            SELECT id FROM t_process_node_instance
            WHERE proc_inst_id = ? AND node_id = ? AND round_no = ? AND status = 'ACTIVE'
            ORDER BY attempt_no DESC LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null,
            instance.getId(), nodeId, instance.getRoundNo());
        if (existing != null) return existing;
        Integer attempt = jdbc.queryForObject("""
            SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM t_process_node_instance
            WHERE proc_inst_id = ? AND node_id = ?
            """, Integer.class, instance.getId(), nodeId);
        Long gatewayId = context.parallelId() == null ? null : jdbc.query("""
            SELECT id FROM t_process_node_instance
            WHERE proc_inst_id = ? AND node_id = ? AND round_no = ? AND status = 'ACTIVE'
            ORDER BY attempt_no DESC LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null,
            instance.getId(), context.parallelId(), instance.getRoundNo());
        Long id = jdbc.query("""
            INSERT INTO t_process_node_instance(
                proc_inst_id, node_id, node_type, round_no, attempt_no,
                gateway_node_instance_id, branch_id, status, policy_snapshot,
                form_revision_id_at_enter)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?::jsonb, ?)
            RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null,
            instance.getId(), nodeId, node.path("type").asText(), instance.getRoundNo(),
            attempt == null ? 1 : attempt, gatewayId, context.branchId(),
            node.path("props").isObject() ? node.path("props").toString() : "{}",
            instance.getCurrentFormRevisionId());
        if (id == null) throw new BizException("NODE_STATE_FAILED", "node instance was not created");
        instance.setCurrentNodeInstanceId(id);
        outbox(instance.getId(), null, "NODE_ENTERED", null);
        return id;
    }

    public void completeNode(long nodeInstanceId, String status) {
        if (nodeInstanceId <= 0) return;
        int updated = jdbc.update("""
            UPDATE t_process_node_instance
            SET status = ?, completed_at = now(), version = version + 1
            WHERE id = ? AND status = 'ACTIVE'
            """, status, nodeInstanceId);
        if (updated > 0) {
            Long instanceId = jdbc.query("""
                SELECT proc_inst_id FROM t_process_node_instance WHERE id = ?
                """, rs -> rs.next() ? rs.getLong(1) : null, nodeInstanceId);
            if (instanceId != null) outbox(instanceId, null, "NODE_COMPLETED", null);
        }
    }

    public List<Assignment> assignments(ProcessInstance instance, JsonNode node,
                                        NodeContext context, List<Long> responsibleUsers,
                                        String source) {
        if (!active(instance) || context.nodeInstanceId() == null) {
            return responsibleUsers.stream().map(id -> new Assignment(id, id, 1)).toList();
        }
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(responsibleUsers));
        if (unique.isEmpty()) throw new NoAssigneeFoundException(node.path("id").asText(), "fallback empty");
        List<Assignment> result = new ArrayList<>();
        int sequence = 0;
        for (Long responsible : unique) {
            long actual = activeAgent(responsible, instance.getFormDataId());
            Assignment assignment = new Assignment(responsible, actual, ++sequence);
            jdbc.update("""
                INSERT INTO t_node_participant(
                    node_instance_id, responsible_user_id, actual_user_id,
                    sequence_no, status, source)
                VALUES (?, ?, ?, ?, 'WAITING', ?)
                ON CONFLICT (node_instance_id, responsible_user_id, sequence_no) DO NOTHING
                """, context.nodeInstanceId(), responsible, actual, sequence,
                actual == responsible ? source : "DELEGATION");
            result.add(assignment);
        }
        String mode = mode(node);
        List<Assignment> activated = "SEQUENTIAL".equals(mode)
            ? List.of(result.get(0)) : result;
        for (Assignment assignment : activated) {
            jdbc.update("""
                UPDATE t_node_participant SET status = 'PENDING'
                WHERE node_instance_id = ? AND responsible_user_id = ? AND sequence_no = ?
                """, context.nodeInstanceId(), assignment.responsibleUserId(), assignment.sequenceNo());
        }
        return activated;
    }

    /** Resolves the task recipients that would be activated without writing runtime state. */
    public List<Assignment> previewAssignments(long formDefId, JsonNode node,
                                               List<Long> responsibleUsers) {
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(responsibleUsers));
        if (unique.isEmpty()) {
            throw new NoAssigneeFoundException(node.path("id").asText(), "fallback empty");
        }
        List<Assignment> result = new ArrayList<>();
        int sequence = 0;
        for (Long responsible : unique) {
            result.add(new Assignment(responsible,
                activeAgentForForm(responsible, formDefId), ++sequence));
        }
        return "SEQUENTIAL".equals(mode(node)) ? List.of(result.get(0)) : result;
    }

    public void bindTask(TaskEntity task, NodeContext context, Assignment assignment,
                         ProcessInstance instance, JsonNode node) {
        if (!active(instance) || context.nodeInstanceId() == null) return;
        task.setNodeInstanceId(context.nodeInstanceId());
        task.setSequenceNo(assignment.sequenceNo());
        task.setActionFormRevisionId(instance.getCurrentFormRevisionId());
        if (assignment.actualUserId() != assignment.responsibleUserId()) {
            task.setDelegatedFrom(assignment.responsibleUserId());
        }
        task.setTimeoutAt(timeoutAt(node));
    }

    public void scheduleTimeout(TaskEntity task, JsonNode node) {
        if (task.getTimeoutAt() == null || task.getNodeInstanceId() == null) return;
        JsonNode policy = node.path("props").path("timeoutPolicy");
        String action = policy.path("action").asText("REMIND");
        jdbc.update("""
            INSERT INTO t_workflow_job(
                proc_inst_id, task_id, node_instance_id, node_id, job_type,
                action_key, scheduled_at, status, attempts, max_attempts,
                payload, blocking)
            VALUES (?, ?, ?, ?, 'TASK_TIMEOUT', ?, ?, 'SCHEDULED', 0, 8, ?::jsonb, false)
            ON CONFLICT (task_id, action_key) WHERE job_type = 'TASK_TIMEOUT' DO NOTHING
            """, task.getProcInstId(), task.getId(), task.getNodeInstanceId(), task.getNodeId(),
            action, task.getTimeoutAt(), policy.isObject() ? policy.toString() : "{}" );
    }

    public List<Long> fieldUsers(JsonNode node, NodeContext context) {
        String field = node.path("props").path("fieldUser").path("fieldId").asText();
        JsonNode value = context.formData().path(field);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (value.isArray()) value.forEach(item -> addLong(ids, item));
        else addLong(ids, value);
        return activeUsers(new ArrayList<>(ids));
    }

    public List<Long> fallbackUsers(JsonNode root, JsonNode node) {
        JsonNode fallback = node.path("props").path("fallbackAssignee");
        if (!fallback.isObject()) fallback = settings(root).path("fallbackAssignee");
        List<Long> result = switch (fallback.path("type").asText()) {
            case "USER" -> activeUsers(readIds(fallback.path("ids")));
            case "ROLE" -> roleUsers(readIds(fallback.path("ids")));
            default -> List.of();
        };
        if (!result.isEmpty()) return result;
        return jdbc.queryForList("""
            SELECT DISTINCT user_row.id
            FROM t_user user_row
            JOIN t_user_role user_role ON user_role.user_id = user_row.id
            JOIN t_role role ON role.id = user_role.role_id
            WHERE role.code = 'admin' AND role.enabled = true AND user_row.status = 'ACTIVE'
            ORDER BY user_row.id
            """, Long.class);
    }

    public boolean shouldAutoPass(JsonNode root, ProcessInstance instance, List<Long> users) {
        if (!active(instance) || users.size() != 1) return false;
        if (shouldAutoPassPreview(root, instance.getStartedBy(), users)) return true;
        JsonNode settings = settings(root);
        long user = users.get(0);
        if (!settings.path("skipConsecutiveSameApprover").asBoolean(false)) return false;
        Long previous = jdbc.query("""
            SELECT participant.responsible_user_id
            FROM t_node_participant participant
            JOIN t_process_node_instance node ON node.id = participant.node_instance_id
            WHERE node.proc_inst_id = ? AND node.round_no = ?
              AND node.status IN ('PASSED', 'AUTO_PASSED')
              AND participant.status = 'APPROVED'
            ORDER BY node.completed_at DESC NULLS LAST, node.id DESC LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null,
            instance.getId(), instance.getRoundNo());
        return Objects.equals(previous, user);
    }

    public boolean shouldAutoPassPreview(JsonNode root, long starterId, List<Long> users) {
        return users.size() == 1
            && settings(root).path("skipStarterAsApprover").asBoolean(false)
            && Objects.equals(starterId, users.get(0));
    }

    public boolean shouldSkipResubmittedNode(JsonNode root, ProcessInstance instance,
                                             JsonNode node) {
        if (!active(instance) || instance.getRoundNo() == null || instance.getRoundNo() < 2
            || !"DIFF_CONTINUE".equals(settings(root).path("resubmitStrategy").asText("FULL"))) {
            return false;
        }
        Integer approvedBefore = jdbc.queryForObject("""
            SELECT COUNT(*) FROM t_process_node_instance node
            WHERE node.proc_inst_id = ? AND node.node_id = ? AND node.round_no < ?
              AND (node.status IN ('PASSED', 'AUTO_PASSED') OR (
                node.status = 'CANCELLED' AND EXISTS (
                  SELECT 1 FROM t_task task
                  WHERE task.node_instance_id = node.id AND task.status = 'APPROVED'
                    AND task.operation_kind = 'INVALIDATED'
                )
              ))
            """, Integer.class, instance.getId(), node.path("id").asText(), instance.getRoundNo());
        if (approvedBefore == null || approvedBefore == 0) return false;
        List<String> revisions = jdbc.queryForList("""
            SELECT data::text FROM t_form_data_revision
            WHERE form_data_id = ? AND status = 'SUBMITTED'
            ORDER BY revision_no DESC LIMIT 2
            """, String.class, instance.getFormDataId());
        if (revisions.size() < 2) return false;
        try {
            JsonNode current = json.readTree(revisions.get(0));
            JsonNode previous = json.readTree(revisions.get(1));
            return changedFieldsDoNotAffect(node, current, previous);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Mirrors DIFF_CONTINUE before the candidate rework revision is persisted. */
    public boolean shouldSkipResubmittedNodePreview(JsonNode root, ProcessInstance instance,
                                                    JsonNode node, JsonNode candidateData) {
        if (!active(instance)
            || !"DIFF_CONTINUE".equals(settings(root).path("resubmitStrategy").asText("FULL"))) {
            return false;
        }
        int nextRound = (instance.getRoundNo() == null ? 1 : instance.getRoundNo()) + 1;
        Integer approvedBefore = jdbc.queryForObject("""
            SELECT COUNT(*) FROM t_process_node_instance node
            WHERE node.proc_inst_id = ? AND node.node_id = ? AND node.round_no < ?
              AND (node.status IN ('PASSED', 'AUTO_PASSED') OR (
                node.status = 'CANCELLED' AND EXISTS (
                  SELECT 1 FROM t_task task
                  WHERE task.node_instance_id = node.id AND task.status = 'APPROVED'
                    AND task.operation_kind = 'INVALIDATED'
                )
              ))
            """, Integer.class, instance.getId(), node.path("id").asText(), nextRound);
        if (approvedBefore == null || approvedBefore == 0) return false;
        List<String> revisions = jdbc.queryForList("""
            SELECT data::text FROM t_form_data_revision
            WHERE form_data_id = ? AND status = 'SUBMITTED'
            ORDER BY revision_no DESC LIMIT 1
            """, String.class, instance.getFormDataId());
        if (revisions.isEmpty()) return false;
        try {
            return changedFieldsDoNotAffect(node, candidateData, json.readTree(revisions.get(0)));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean changedFieldsDoNotAffect(JsonNode node, JsonNode current,
                                                     JsonNode previous) {
        LinkedHashSet<String> changed = new LinkedHashSet<>();
        current.fieldNames().forEachRemaining(changed::add);
        previous.fieldNames().forEachRemaining(changed::add);
        changed.removeIf(field -> Objects.equals(current.get(field), previous.get(field)));
        if (changed.isEmpty()) return true;
        JsonNode perms = node.path("props").path("formPerms");
        if (!perms.isArray() || perms.isEmpty()) return false;
        for (JsonNode perm : perms) {
            if (changed.contains(perm.path("fieldId").asText())) return false;
        }
        return true;
    }

    public Decision approve(TaskEntity task, JsonNode node, long operatorId) {
        if (task.getNodeInstanceId() == null) return Decision.advanceNode();
        lockNode(task.getNodeInstanceId());
        jdbc.update("""
            UPDATE t_node_participant SET status = 'APPROVED'
            WHERE node_instance_id = ? AND actual_user_id = ? AND sequence_no = ?
              AND status = 'PENDING'
            """, task.getNodeInstanceId(), operatorId, task.getSequenceNo());
        Counts counts = counts(task.getNodeInstanceId());
        String mode = mode(node);
        if ("SEQUENTIAL".equals(mode) && counts.waiting() > 0) {
            Long nextTask = activateNext(task);
            return Decision.waitFor(nextTask == null ? List.of() : List.of(nextTask));
        }
        if ("ANY".equals(mode) || "SEQUENTIAL".equals(mode)) {
            cancelPending(task, "decision completed");
            completeNode(task.getNodeInstanceId(), "PASSED");
            return Decision.advanceNode();
        }
        if (counts.pending() > 0 || counts.waiting() > 0) {
            return Decision.waitFor(List.of());
        }
        boolean passed = "RATIO".equals(mode)
            ? counts.approved() >= required(counts.total(), node)
            : counts.rejected() == 0;
        completeNode(task.getNodeInstanceId(), passed ? "PASSED" : "REJECTED");
        return passed ? Decision.advanceNode() : Decision.rejectNode();
    }

    public Decision rejectVote(TaskEntity task, JsonNode node, long operatorId) {
        if (task.getNodeInstanceId() == null) return Decision.rejectNode();
        lockNode(task.getNodeInstanceId());
        jdbc.update("""
            UPDATE t_node_participant SET status = 'REJECTED'
            WHERE node_instance_id = ? AND actual_user_id = ? AND sequence_no = ?
              AND status = 'PENDING'
            """, task.getNodeInstanceId(), operatorId, task.getSequenceNo());
        Counts counts = counts(task.getNodeInstanceId());
        String mode = mode(node);
        if ("ANY".equals(mode) || "SEQUENTIAL".equals(mode)) {
            cancelPending(task, "node rejected");
            completeNode(task.getNodeInstanceId(), "REJECTED");
            return Decision.rejectNode();
        }
        if (counts.pending() > 0 || counts.waiting() > 0) {
            return Decision.waitFor(List.of());
        }
        boolean passed = "RATIO".equals(mode)
            && counts.approved() >= required(counts.total(), node);
        completeNode(task.getNodeInstanceId(), passed ? "PASSED" : "REJECTED");
        return passed ? Decision.advanceNode() : Decision.rejectNode();
    }

    public Decision rejectAdditional(TaskEntity task, long operatorId) {
        if (task.getNodeInstanceId() == null) return Decision.rejectNode();
        lockNode(task.getNodeInstanceId());
        jdbc.update("""
            UPDATE t_node_participant SET status = 'REJECTED'
            WHERE node_instance_id = ? AND actual_user_id = ? AND sequence_no = ?
              AND status IN ('PENDING', 'WAITING')
            """, task.getNodeInstanceId(), operatorId, task.getSequenceNo());
        cancelPending(task, "additional reviewer rejected");
        completeNode(task.getNodeInstanceId(), "REJECTED");
        return Decision.rejectNode();
    }

    public void outbox(long instanceId, Long recipientId, String type, Long taskId) {
        jdbc.update("""
            INSERT INTO t_workflow_outbox(
                aggregate_type, aggregate_id, event_type, recipient_id, payload)
            VALUES ('PROCESS_INSTANCE', ?, ?, ?,
                    jsonb_build_object('instanceId', ?::bigint, 'taskId', ?::bigint))
            """, instanceId, type, recipientId, instanceId, taskId);
    }

    public void recordCc(ProcessInstance instance, Long nodeInstanceId, long recipientId) {
        if (!active(instance)) return;
        jdbc.update("""
            INSERT INTO t_cc_record(proc_inst_id, node_instance_id, recipient_id)
            VALUES (?, ?, ?) ON CONFLICT (node_instance_id, recipient_id) DO NOTHING
            """, instance.getId(), nodeInstanceId, recipientId);
        outbox(instance.getId(), recipientId, "CC_ASSIGNED", null);
    }

    public void registerParallelBranch(ProcessInstance instance, Long gatewayNodeInstanceId,
                                       String branchId) {
        if (!active(instance) || gatewayNodeInstanceId == null) return;
        jdbc.update("""
            INSERT INTO t_parallel_branch_state(gateway_node_instance_id, branch_id)
            VALUES (?, ?) ON CONFLICT DO NOTHING
            """, gatewayNodeInstanceId, branchId);
    }

    public boolean parallelBranchPassed(ProcessInstance instance, JsonNode root,
                                        String parallelId, String branchId) {
        if (!active(instance)) return false;
        Long gatewayId = gatewayNodeInstance(instance.getId(), parallelId, instance.getRoundNo());
        if (gatewayId == null) return false;
        lockNode(gatewayId);
        jdbc.update("""
            UPDATE t_parallel_branch_state
            SET status = 'PASSED', completed_at = now()
            WHERE gateway_node_instance_id = ? AND branch_id = ? AND status = 'ACTIVE'
            """, gatewayId, branchId);
        JsonNode gateway = ProcessTreeNav.findById(root, parallelId);
        if (!"ANY".equals(joinMode(gateway))) {
            if (branchCounts(gatewayId).active() == 0) completeNode(gatewayId, "PASSED");
            return false;
        }
        cancelGatewayWork(instance.getId(), gatewayId, parallelId, branchId,
            "parallel ANY branch completed", false);
        completeNode(gatewayId, "PASSED");
        return true;
    }

    public ParallelDecision parallelBranchRejected(TaskEntity task, ProcessInstance instance,
                                                     JsonNode root) {
        if (!active(instance) || task.getParallelId() == null) return ParallelDecision.REJECT;
        Long gatewayId = gatewayNodeInstance(instance.getId(), task.getParallelId(),
            instance.getRoundNo());
        if (gatewayId == null) return ParallelDecision.REJECT;
        lockNode(gatewayId);
        jdbc.update("""
            UPDATE t_parallel_branch_state
            SET status = 'REJECTED', completed_at = now()
            WHERE gateway_node_instance_id = ? AND branch_id = ? AND status = 'ACTIVE'
            """, gatewayId, task.getBranchId());
        JsonNode gateway = ProcessTreeNav.findById(root, task.getParallelId());
        if (!"ANY".equals(joinMode(gateway))) {
            cancelGatewayWork(instance.getId(), gatewayId, task.getParallelId(),
                task.getBranchId(), "parallel ALL rejected", true);
            completeNode(gatewayId, "REJECTED");
            return ParallelDecision.REJECT;
        }
        BranchCounts counts = branchCounts(gatewayId);
        if (counts.passed() > 0) return ParallelDecision.ADVANCE;
        return counts.active() > 0 ? ParallelDecision.WAIT : ParallelDecision.REJECT;
    }

    public long createRevision(FormData data, String status, String reason, long actorId) {
        return versions.createRevision(data, status, reason, actorId);
    }

    public void timeoutReminder(WorkflowJob job, TaskEntity task) {
        outbox(task.getProcInstId(), task.getAssigneeId(), "TASK_TIMEOUT_REMINDER", task.getId());
    }

    public void timeoutEscalate(WorkflowJob job, TaskEntity task) {
        Long manager = jdbc.query("""
            SELECT manager.id FROM t_user assignee
            JOIN t_user manager ON manager.id = assignee.manager_id AND manager.status = 'ACTIVE'
            WHERE assignee.id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, task.getAssigneeId());
        if (manager == null) {
            List<Long> fallback = fallbackUsers(json.createObjectNode(), json.createObjectNode());
            manager = fallback.isEmpty() ? null : fallback.get(0);
        }
        if (manager == null || Objects.equals(manager, task.getAssigneeId())) {
            timeoutReminder(job, task);
            return;
        }
        int cancelled = jdbc.update("""
            UPDATE t_task SET status = 'CANCELLED', comment = 'timeout escalated'
            WHERE id = ? AND status = 'PENDING'
            """, task.getId());
        if (cancelled == 0) return;
        jdbc.update("""
            UPDATE t_node_participant SET actual_user_id = ?
            WHERE node_instance_id = ? AND actual_user_id = ? AND sequence_no = ?
              AND status = 'PENDING'
            """, manager, task.getNodeInstanceId(), task.getAssigneeId(), task.getSequenceNo());
        Long newTaskId = jdbc.query("""
            INSERT INTO t_task(
                proc_inst_id, node_instance_id, node_id, assignee_id, task_type,
                status, approval_mode, parent_task_id, delegated_from, sequence_no,
                operation_kind, action_form_revision_id, parallel_id, branch_id)
            VALUES (?, ?, ?, ?, 'APPROVAL', 'PENDING', ?, ?, ?, ?,
                    'TIMEOUT_ESCALATION', ?, ?, ?)
            RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null,
            task.getProcInstId(), task.getNodeInstanceId(), task.getNodeId(), manager,
            task.getApprovalMode(), task.getId(), task.getAssigneeId(), task.getSequenceNo(),
            task.getActionFormRevisionId(), task.getParallelId(), task.getBranchId());
        jdbc.update("""
            INSERT INTO t_task_history(proc_inst_id, task_id, from_node_id, to_node_id,
                                       action, comment)
            VALUES (?, ?, ?, ?, 'TIMEOUT_ESCALATE', ?)
            """, task.getProcInstId(), task.getId(), task.getNodeId(), task.getNodeId(),
            "escalated to user " + manager);
        outbox(task.getProcInstId(), task.getAssigneeId(), "TASK_CANCELLED", task.getId());
        outbox(task.getProcInstId(), manager, "TASK_ASSIGNED", newTaskId);
    }

    public void copyRuntimeTask(TaskEntity parent, TaskEntity child) {
        if (parent.getNodeInstanceId() == null) return;
        child.setNodeInstanceId(parent.getNodeInstanceId());
        child.setActionFormRevisionId(parent.getActionFormRevisionId());
        child.setSequenceNo(parent.getSequenceNo());
    }

    public void reassignParticipant(TaskEntity parent, long actualUserId) {
        if (parent.getNodeInstanceId() == null) return;
        jdbc.update("""
            UPDATE t_node_participant SET actual_user_id = ?
            WHERE node_instance_id = ? AND sequence_no = ? AND status = 'PENDING'
            """, actualUserId, parent.getNodeInstanceId(), parent.getSequenceNo());
    }

    public int addParticipant(TaskEntity parent, long userId, boolean before) {
        if (parent.getNodeInstanceId() == null) return 1;
        Integer sequence = jdbc.queryForObject("""
            SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM t_node_participant
            WHERE node_instance_id = ?
            """, Integer.class, parent.getNodeInstanceId());
        int value = sequence == null ? 1 : sequence;
        jdbc.update("""
            INSERT INTO t_node_participant(
                node_instance_id, responsible_user_id, actual_user_id,
                sequence_no, status, source)
            VALUES (?, ?, ?, ?, ?, 'ADD_SIGN')
            """, parent.getNodeInstanceId(), userId, userId, value,
            before ? "PENDING" : "WAITING");
        if (before) {
            jdbc.update("""
                UPDATE t_node_participant SET status = 'WAITING'
                WHERE node_instance_id = ? AND sequence_no = ? AND status = 'PENDING'
                """, parent.getNodeInstanceId(), parent.getSequenceNo());
        }
        return value;
    }

    public boolean activateAfterSign(TaskEntity parent) {
        if (parent.getNodeInstanceId() == null) return false;
        List<TaskRow> children = jdbc.query("""
            UPDATE t_task SET status = 'PENDING'
            WHERE parent_task_id = ? AND operation_kind = 'ADD_AFTER' AND status = 'BLOCKED'
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)), parent.getId());
        if (children.isEmpty()) return false;
        jdbc.update("""
            UPDATE t_node_participant SET status = 'APPROVED'
            WHERE node_instance_id = ? AND sequence_no = ? AND status = 'PENDING'
            """, parent.getNodeInstanceId(), parent.getSequenceNo());
        jdbc.update("""
            UPDATE t_node_participant participant SET status = 'PENDING'
            FROM t_task task
            WHERE task.parent_task_id = ? AND task.operation_kind = 'ADD_AFTER'
              AND participant.node_instance_id = task.node_instance_id
              AND participant.sequence_no = task.sequence_no
              AND participant.status = 'WAITING'
            """, parent.getId());
        children.forEach(child -> outbox(parent.getProcInstId(), child.assigneeId(),
            "TASK_ASSIGNED", child.id()));
        return true;
    }

    public void completeBeforeSign(TaskEntity child) {
        if (child.getNodeInstanceId() == null) return;
        jdbc.update("""
            UPDATE t_node_participant SET status = 'APPROVED'
            WHERE node_instance_id = ? AND sequence_no = ? AND status = 'PENDING'
            """, child.getNodeInstanceId(), child.getSequenceNo());
        List<TaskRow> parents = jdbc.query("""
            UPDATE t_task SET status = 'PENDING'
            WHERE id = ? AND status = 'BLOCKED'
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
            child.getParentTaskId());
        jdbc.update("""
            UPDATE t_node_participant SET status = 'PENDING'
            WHERE node_instance_id = ? AND sequence_no = (
              SELECT sequence_no FROM t_task WHERE id = ?
            ) AND status = 'WAITING'
            """, child.getNodeInstanceId(), child.getParentTaskId());
        parents.forEach(parent -> outbox(child.getProcInstId(), parent.assigneeId(),
            "TASK_ASSIGNED", parent.id()));
    }

    public void recallApproval(TaskEntity task, ProcessInstance instance, long operatorId) {
        if (!active(instance) || task.getNodeInstanceId() == null || task.getParallelId() != null) {
            throw new BizException("BAD_RECALL_STATE", "仅支持追回 V2 串行审批节点");
        }
        lockNode(task.getNodeInstanceId());
        Integer acted = jdbc.queryForObject("""
            SELECT COUNT(*) FROM t_task
            WHERE proc_inst_id = ? AND created_at > ?
              AND status IN ('APPROVED', 'REJECTED', 'RESUBMITTED')
            """, Integer.class, task.getProcInstId(), task.getApprovedAt());
        if (acted != null && acted > 0) {
            throw new BizException("RECALL_TOO_LATE", "下一节点已处理，无法追回");
        }
        List<TaskRow> downstream = jdbc.query("""
            UPDATE t_task SET status = 'CANCELLED', comment = 'upstream approval recalled'
            WHERE proc_inst_id = ? AND created_at > ? AND status = 'PENDING'
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
            task.getProcInstId(), task.getApprovedAt());
        jdbc.update("""
            UPDATE t_process_node_instance SET status = 'CANCELLED', completed_at = now()
            WHERE proc_inst_id = ? AND started_at > (
              SELECT completed_at FROM t_process_node_instance WHERE id = ?
            ) AND status = 'ACTIVE'
            """, task.getProcInstId(), task.getNodeInstanceId());
        jdbc.update("""
            UPDATE t_process_node_instance
            SET status = 'ACTIVE', completed_at = NULL, version = version + 1
            WHERE id = ? AND status IN ('PASSED', 'AUTO_PASSED')
            """, task.getNodeInstanceId());
        jdbc.update("""
            UPDATE t_node_participant SET status = 'PENDING'
            WHERE node_instance_id = ? AND sequence_no = ? AND status = 'APPROVED'
            """, task.getNodeInstanceId(), task.getSequenceNo());
        jdbc.update("""
            UPDATE t_task SET status = 'PENDING', approved_by = NULL, approved_at = NULL,
                              comment = NULL, operation_kind = 'RECALLED_APPROVAL'
            WHERE id = ? AND status = 'APPROVED'
            """, task.getId());
        jdbc.update("""
            UPDATE t_process_instance SET current_node_id = ?, current_node_instance_id = ?,
                                          version = version + 1
            WHERE id = ?
            """, task.getNodeId(), task.getNodeInstanceId(), task.getProcInstId());
        for (TaskRow row : downstream) {
            jdbc.update("""
                INSERT INTO t_task_history(proc_inst_id, task_id, action, comment)
                VALUES (?, ?, 'CANCEL', 'upstream approval recalled')
                """, task.getProcInstId(), row.id());
            outbox(task.getProcInstId(), row.assigneeId(), "TASK_CANCELLED", row.id());
        }
        jdbc.update("""
            INSERT INTO t_task_history(proc_inst_id, task_id, from_node_id, to_node_id,
                                       action, operator_id, comment)
            VALUES (?, ?, ?, ?, 'RECALL_APPROVAL', ?, 'approval recalled')
            """, task.getProcInstId(), task.getId(), task.getNodeId(), task.getNodeId(), operatorId);
        outbox(task.getProcInstId(), task.getAssigneeId(), "TASK_ASSIGNED", task.getId());
    }

    public void adminReassign(TaskEntity task, long targetUserId, long operatorId,
                              String reason) {
        Long active = jdbc.query("SELECT id FROM t_user WHERE id = ? AND status = 'ACTIVE'",
            rs -> rs.next() ? rs.getLong(1) : null, targetUserId);
        if (active == null) throw new BizException("BAD_ASSIGNEE", "target user is not active");
        long previous = task.getAssigneeId();
        jdbc.update("""
            UPDATE t_task SET assignee_id = ?, delegated_from = ?,
                              operation_kind = 'ADMIN_REASSIGN', comment = ?
            WHERE id = ? AND status = 'PENDING'
            """, targetUserId, previous, reason, task.getId());
        if (task.getNodeInstanceId() != null) {
            jdbc.update("""
                UPDATE t_node_participant SET actual_user_id = ?
                WHERE node_instance_id = ? AND actual_user_id = ? AND sequence_no = ?
                  AND status = 'PENDING'
                """, targetUserId, task.getNodeInstanceId(), previous, task.getSequenceNo());
        }
        jdbc.update("""
            INSERT INTO t_task_history(proc_inst_id, task_id, from_node_id, to_node_id,
                                       action, operator_id, comment)
            VALUES (?, ?, ?, ?, 'ADMIN_REASSIGN', ?, ?)
            """, task.getProcInstId(), task.getId(), task.getNodeId(), task.getNodeId(),
            operatorId, reason);
        outbox(task.getProcInstId(), previous, "TASK_CANCELLED", task.getId());
        outbox(task.getProcInstId(), targetUserId, "TASK_ASSIGNED", task.getId());
    }

    public void terminate(ProcessInstance instance, long operatorId, String reason) {
        List<TaskRow> cancelled = jdbc.query("""
            UPDATE t_task SET status = 'CANCELLED', comment = ?
            WHERE proc_inst_id = ? AND status IN ('PENDING', 'BLOCKED')
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
            reason, instance.getId());
        jdbc.update("""
            UPDATE t_process_node_instance SET status = 'CANCELLED', completed_at = now()
            WHERE proc_inst_id = ? AND status = 'ACTIVE'
            """, instance.getId());
        jdbc.update("""
            UPDATE t_node_participant participant SET status = 'CANCELLED'
            FROM t_process_node_instance node
            WHERE participant.node_instance_id = node.id AND node.proc_inst_id = ?
              AND participant.status IN ('WAITING', 'PENDING')
            """, instance.getId());
        jdbc.update("""
            UPDATE t_workflow_job SET status = 'CANCELLED', completed_at = now(),
                                      locked_at = NULL, locked_by = NULL
            WHERE proc_inst_id = ? AND status IN ('SCHEDULED', 'RUNNING')
            """, instance.getId());
        cancelled.forEach(task -> outbox(instance.getId(), task.assigneeId(),
            "TASK_CANCELLED", task.id()));
        jdbc.update("""
            INSERT INTO t_task_history(proc_inst_id, action, operator_id, comment)
            VALUES (?, 'ADMIN_TERMINATE', ?, ?)
            """, instance.getId(), operatorId, reason);
        outbox(instance.getId(), instance.getStartedBy(), "INSTANCE_REJECTED", null);
    }

    public void beginRound(ProcessInstance instance, String reason) {
        if (!active(instance)) return;
        jdbc.update("""
            UPDATE t_process_node_instance
            SET status = 'CANCELLED', completed_at = now(), version = version + 1
            WHERE proc_inst_id = ? AND status = 'ACTIVE'
            """, instance.getId());
        jdbc.update("""
            UPDATE t_parallel_branch_state branch SET status = 'CANCELLED', completed_at = now()
            FROM t_process_node_instance gateway
            WHERE branch.gateway_node_instance_id = gateway.id
              AND gateway.proc_inst_id = ? AND branch.status = 'ACTIVE'
            """, instance.getId());
        instance.setRoundNo((instance.getRoundNo() == null ? 1 : instance.getRoundNo()) + 1);
        instance.setCurrentNodeInstanceId(null);
        outbox(instance.getId(), instance.getStartedBy(), "ROUND_STARTED", null);
    }

    public void invalidateApprovedNodes(ProcessInstance instance, List<String> nodeIds,
                                        String reason) {
        if (!active(instance) || nodeIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(nodeIds.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(instance.getId());
        arguments.add(instance.getRoundNo());
        arguments.addAll(nodeIds);
        List<TaskRow> invalidated = jdbc.query("""
            UPDATE t_task task SET operation_kind = 'INVALIDATED'
            FROM t_process_node_instance node
            WHERE task.node_instance_id = node.id AND task.proc_inst_id = ?
              AND node.round_no = ? AND task.status = 'APPROVED'
              AND task.node_id IN (
            """ + placeholders + ") RETURNING task.id, task.assignee_id",
            (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)), arguments.toArray());
        for (TaskRow task : invalidated) {
            jdbc.update("""
                INSERT INTO t_task_history(proc_inst_id, task_id, action, comment)
                VALUES (?, ?, 'INVALIDATE', ?)
                """, instance.getId(), task.id(), reason);
            outbox(instance.getId(), task.assigneeId(), "APPROVAL_INVALIDATED", task.id());
        }
    }

    private Long activateNext(TaskEntity previous) {
        Participant next = jdbc.query("""
            SELECT responsible_user_id, actual_user_id, sequence_no
            FROM t_node_participant
            WHERE node_instance_id = ? AND status = 'WAITING'
            ORDER BY sequence_no LIMIT 1
            FOR UPDATE
            """, rs -> rs.next() ? participant(rs) : null, previous.getNodeInstanceId());
        if (next == null) return null;
        jdbc.update("""
            UPDATE t_node_participant SET status = 'PENDING'
            WHERE node_instance_id = ? AND sequence_no = ?
            """, previous.getNodeInstanceId(), next.sequenceNo());
        Long id = jdbc.query("""
            INSERT INTO t_task(
                proc_inst_id, node_instance_id, node_id, assignee_id, task_type,
                status, approval_mode, delegated_from, sequence_no,
                action_form_revision_id, parallel_id, branch_id)
            VALUES (?, ?, ?, ?, 'APPROVAL', 'PENDING', 'SEQUENTIAL', ?, ?, ?, ?, ?)
            RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null,
            previous.getProcInstId(), previous.getNodeInstanceId(), previous.getNodeId(),
            next.actualUserId(), next.actualUserId() == next.responsibleUserId()
                ? null : next.responsibleUserId(), next.sequenceNo(),
            previous.getActionFormRevisionId(), previous.getParallelId(), previous.getBranchId());
        if (id != null) {
            outbox(previous.getProcInstId(), next.actualUserId(), "TASK_ASSIGNED", id);
            scheduleSequentialTimeout(previous, id);
        }
        return id;
    }

    private void scheduleSequentialTimeout(TaskEntity previous, long taskId) {
        String props = jdbc.query("""
            SELECT policy_snapshot::text FROM t_process_node_instance WHERE id = ?
            """, rs -> rs.next() ? rs.getString(1) : null, previous.getNodeInstanceId());
        try {
            JsonNode policy = json.readTree(props == null ? "{}" : props).path("timeoutPolicy");
            long minutes = policy.path("afterMinutes").asLong(0);
            if (minutes <= 0) return;
            OffsetDateTime due = OffsetDateTime.now().plusMinutes(minutes);
            jdbc.update("UPDATE t_task SET timeout_at = ? WHERE id = ?", due, taskId);
            jdbc.update("""
                INSERT INTO t_workflow_job(
                    proc_inst_id, task_id, node_instance_id, node_id, job_type,
                    action_key, scheduled_at, status, attempts, max_attempts,
                    payload, blocking)
                VALUES (?, ?, ?, ?, 'TASK_TIMEOUT', ?, ?, 'SCHEDULED', 0, 8, ?::jsonb, false)
                ON CONFLICT (task_id, action_key) WHERE job_type = 'TASK_TIMEOUT' DO NOTHING
                """, previous.getProcInstId(), taskId, previous.getNodeInstanceId(),
                previous.getNodeId(), policy.path("action").asText("REMIND"), due,
                policy.toString());
        } catch (Exception ignored) {
            // Invalid policies are rejected at publish; legacy snapshots simply have no timeout.
        }
    }

    private void cancelPending(TaskEntity acted, String reason) {
        List<TaskRow> cancelled = jdbc.query("""
            UPDATE t_task SET status = 'CANCELLED', comment = COALESCE(comment, ?)
            WHERE node_instance_id = ? AND status = 'PENDING' AND id <> ?
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
            reason, acted.getNodeInstanceId(), acted.getId());
        jdbc.update("""
            UPDATE t_node_participant SET status = 'CANCELLED'
            WHERE node_instance_id = ? AND status IN ('WAITING', 'PENDING')
            """, acted.getNodeInstanceId());
        for (TaskRow task : cancelled) {
            jdbc.update("""
                INSERT INTO t_task_history(proc_inst_id, task_id, from_node_id,
                                           to_node_id, action, comment)
                VALUES (?, ?, ?, ?, 'CANCEL', ?)
                """, acted.getProcInstId(), task.id(), acted.getNodeId(), acted.getNodeId(), reason);
            outbox(acted.getProcInstId(), task.assigneeId(), "TASK_CANCELLED", task.id());
        }
    }

    private Counts counts(long nodeInstanceId) {
        return jdbc.query("""
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE status = 'APPROVED') AS approved,
                   COUNT(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                   COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                   COUNT(*) FILTER (WHERE status = 'WAITING') AS waiting
            FROM t_node_participant WHERE node_instance_id = ?
            """, rs -> rs.next() ? new Counts(rs.getInt("total"), rs.getInt("approved"),
                rs.getInt("rejected"), rs.getInt("pending"), rs.getInt("waiting"))
                : new Counts(0, 0, 0, 0, 0), nodeInstanceId);
    }

    private Long gatewayNodeInstance(long instanceId, String parallelId, int roundNo) {
        return jdbc.query("""
            SELECT id FROM t_process_node_instance
            WHERE proc_inst_id = ? AND node_id = ? AND round_no = ?
              AND status = 'ACTIVE'
            ORDER BY attempt_no DESC LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null, instanceId, parallelId, roundNo);
    }

    private BranchCounts branchCounts(long gatewayId) {
        return jdbc.query("""
            SELECT COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active,
                   COUNT(*) FILTER (WHERE status = 'PASSED') AS passed,
                   COUNT(*) FILTER (WHERE status = 'REJECTED') AS rejected
            FROM t_parallel_branch_state WHERE gateway_node_instance_id = ?
            """, rs -> rs.next()
                ? new BranchCounts(rs.getInt("active"), rs.getInt("passed"), rs.getInt("rejected"))
                : new BranchCounts(0, 0, 0), gatewayId);
    }

    private void cancelGatewayWork(long instanceId, long gatewayId, String parallelId,
                                   String exceptBranchId, String reason,
                                   boolean invalidateApproved) {
        List<TaskRow> cancelled = jdbc.query("""
            UPDATE t_task SET status = 'CANCELLED', comment = COALESCE(comment, ?)
            WHERE proc_inst_id = ? AND parallel_id = ? AND status = 'PENDING'
              AND branch_id IS DISTINCT FROM ?
            RETURNING id, assignee_id
            """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
            reason, instanceId, parallelId, exceptBranchId);
        for (TaskRow task : cancelled) {
            jdbc.update("""
                INSERT INTO t_task_history(proc_inst_id, task_id, action, comment)
                VALUES (?, ?, 'CANCEL', ?)
                """, instanceId, task.id(), reason);
            outbox(instanceId, task.assigneeId(), "TASK_CANCELLED", task.id());
        }
        if (invalidateApproved) {
            List<TaskRow> invalidated = jdbc.query("""
                UPDATE t_task SET operation_kind = 'INVALIDATED'
                WHERE proc_inst_id = ? AND parallel_id = ? AND status = 'APPROVED'
                  AND branch_id IS DISTINCT FROM ?
                RETURNING id, assignee_id
                """, (rs, rowNum) -> new TaskRow(rs.getLong(1), rs.getLong(2)),
                instanceId, parallelId, exceptBranchId);
            for (TaskRow task : invalidated) {
                jdbc.update("""
                    INSERT INTO t_task_history(proc_inst_id, task_id, action, comment)
                    VALUES (?, ?, 'INVALIDATE', ?)
                    """, instanceId, task.id(), reason);
                outbox(instanceId, task.assigneeId(), "APPROVAL_INVALIDATED", task.id());
            }
        }
        jdbc.update("""
            UPDATE t_parallel_branch_state SET status = 'CANCELLED', completed_at = now()
            WHERE gateway_node_instance_id = ? AND status IN ('ACTIVE', 'PASSED')
              AND branch_id IS DISTINCT FROM ?
            """, gatewayId, exceptBranchId);
        jdbc.update("""
            UPDATE t_process_node_instance SET status = 'CANCELLED', completed_at = now()
            WHERE gateway_node_instance_id = ? AND status IN ('ACTIVE', 'PASSED')
              AND branch_id IS DISTINCT FROM ?
            """, gatewayId, exceptBranchId);
        jdbc.update("""
            UPDATE t_node_participant participant SET status = 'CANCELLED'
            FROM t_process_node_instance node
            WHERE participant.node_instance_id = node.id
              AND node.gateway_node_instance_id = ?
              AND node.branch_id IS DISTINCT FROM ?
              AND participant.status IN ('WAITING', 'PENDING', 'APPROVED')
            """, gatewayId, exceptBranchId);
    }

    private void lockNode(long nodeInstanceId) {
        jdbc.queryForObject("SELECT id FROM t_process_node_instance WHERE id = ? FOR UPDATE",
            Long.class, nodeInstanceId);
    }

    private long activeAgent(long responsible, long formDataId) {
        Long agent = jdbc.query("""
            SELECT delegation.agent_id
            FROM t_approval_delegation delegation
            JOIN t_form_data data ON data.id = ?
            JOIN t_user agent ON agent.id = delegation.agent_id AND agent.status = 'ACTIVE'
            WHERE delegation.principal_id = ? AND delegation.status = 'ACTIVE'
              AND now() >= delegation.starts_at AND now() < delegation.ends_at
              AND (delegation.form_def_id IS NULL OR delegation.form_def_id = data.form_def_id)
            ORDER BY delegation.form_def_id NULLS LAST, delegation.id DESC LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null, formDataId, responsible);
        return agent == null ? responsible : agent;
    }

    private long activeAgentForForm(long responsible, long formDefId) {
        Long agent = jdbc.query("""
            SELECT delegation.agent_id
            FROM t_approval_delegation delegation
            JOIN t_user agent ON agent.id = delegation.agent_id AND agent.status = 'ACTIVE'
            WHERE delegation.principal_id = ? AND delegation.status = 'ACTIVE'
              AND now() >= delegation.starts_at AND now() < delegation.ends_at
              AND (delegation.form_def_id IS NULL OR delegation.form_def_id = ?)
            ORDER BY delegation.form_def_id NULLS LAST, delegation.id DESC LIMIT 1
            """, rs -> rs.next() ? rs.getLong(1) : null, responsible, formDefId);
        return agent == null ? responsible : agent;
    }

    private List<Long> activeUsers(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Long> active = jdbc.queryForList(
            "SELECT id FROM t_user WHERE status = 'ACTIVE' AND id IN (" + placeholders + ")",
            Long.class, ids.toArray());
        return ids.stream().filter(active::contains).distinct().toList();
    }

    private List<Long> roleUsers(List<Long> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(roleIds.size(), "?"));
        return jdbc.queryForList("""
            SELECT DISTINCT user_row.id FROM t_user user_row
            JOIN t_user_role user_role ON user_role.user_id = user_row.id
            JOIN t_role role ON role.id = user_role.role_id
            WHERE user_row.status = 'ACTIVE' AND role.enabled = true AND role.id IN (
            """ + placeholders + ") ORDER BY user_row.id", Long.class, roleIds.toArray());
    }

    private static int required(int total, JsonNode node) {
        int ratio = node.path("props").path("ratio").asInt(
            node.path("props").path("passRatio").asInt(100));
        return Math.max(1, (int) Math.ceil(total * Math.min(100, Math.max(1, ratio)) / 100.0));
    }

    public static String mode(JsonNode node) {
        return switch (node.path("props").path("mode").asText("ANY")) {
            case "OR" -> "ANY";
            case "AND" -> "ALL";
            default -> node.path("props").path("mode").asText("ANY");
        };
    }

    private static String joinMode(JsonNode node) {
        if (node == null) return "ALL";
        return switch (node.path("props").path("joinMode").asText("ALL")) {
            case "OR", "ANY" -> "ANY";
            default -> "ALL";
        };
    }

    private static JsonNode settings(JsonNode root) {
        JsonNode nested = root.path("props").path("settings");
        return nested.isObject() ? nested : root.path("props");
    }

    private static List<Long> readIds(JsonNode values) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (values.isArray()) values.forEach(value -> addLong(result, value));
        return new ArrayList<>(result);
    }

    private static void addLong(LinkedHashSet<Long> result, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        if (value.isIntegralNumber()) result.add(value.asLong());
        else if (value.isTextual()) {
            try { result.add(Long.parseLong(value.asText())); }
            catch (NumberFormatException ignored) { }
        }
    }

    private static OffsetDateTime timeoutAt(JsonNode node) {
        long minutes = node.path("props").path("timeoutPolicy").path("afterMinutes").asLong(0);
        return minutes > 0 ? OffsetDateTime.now().plusMinutes(minutes) : null;
    }

    private static Participant participant(ResultSet rs) throws SQLException {
        return new Participant(rs.getLong("responsible_user_id"), rs.getLong("actual_user_id"),
            rs.getInt("sequence_no"));
    }

    public record StartState(long processVersionId, long revisionId) { }
    public record Assignment(long responsibleUserId, long actualUserId, int sequenceNo) { }
    public record Decision(boolean advance, boolean reject, List<Long> newTaskIds) {
        static Decision advanceNode() { return new Decision(true, false, List.of()); }
        static Decision rejectNode() { return new Decision(false, true, List.of()); }
        static Decision waitFor(List<Long> ids) { return new Decision(false, false, ids); }
    }
    public enum ParallelDecision { WAIT, ADVANCE, REJECT }
    private record Participant(long responsibleUserId, long actualUserId, int sequenceNo) { }
    private record Counts(int total, int approved, int rejected, int pending, int waiting) { }
    private record TaskRow(long id, long assigneeId) { }
    private record BranchCounts(int active, int passed, int rejected) { }
}

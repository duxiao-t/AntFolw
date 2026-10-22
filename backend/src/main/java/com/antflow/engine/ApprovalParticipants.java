package com.antflow.engine;

import com.antflow.engine.WorkflowRuntimeV2.Assignment;
import com.antflow.engine.handler.NodeContext;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审批人 / 抄送人解析，以及任务进入时的运行时绑定。
 *
 * <p>为什么单独一簇：这一组规则的变更原因只有一个——"该由谁来办"。委派、角色、字段用户、兜底、
 * 自动通过、DIFF_CONTINUE 续办全都在这里，跟状态机/决策/并行无关；混在门面里时，任何一条人员规则
 * 调整都要在 1200 行文件里翻。
 *
 * <p>四个易错点：
 * <ol>
 *   <li>**预览与落地必须共用同一套判定**：{@link #previewAssignments} 与 {@link #assignments}
 *       只差"写不写参与者表"，两边的人选/委派/SEQUENTIAL 截断逻辑必须一致，否则移动端预览与实际待办对不上。</li>
 *   <li>**委派走 actual_user_id，责任人不变**：{@code assignments} 仍以 responsible 落
 *       {@code responsible_user_id}，参与人表的 {@code source} 只在真发生委派时加 {@code _DELEGATION} 后缀。</li>
 *   <li>**兜底上限是硬失败**：{@code ROLE_ASSIGNEE_TOO_MANY} 不降级、不截断——角色配错时静默少人比报错更难查。</li>
 *   <li>**DIFF_CONTINUE 的 JSON 解析失败必须返回 false**：算不出差异就当"有影响"，走正常审批；
 *       抛异常会让返工重提直接失败（这是有意的取舍，不要改成 fail-fast）。</li>
 * </ol>
 *
 * <p>注意：本类只 import 门面的 **record 类型**（{@code Assignment}）用于返回值，不持有
 * {@code WorkflowRuntimeV2} 实例——避免形成反向依赖。
 */
final class ApprovalParticipants {

    private static final int MAX_FALLBACK_ASSIGNEES = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ApprovalParticipants(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<Assignment> assignments(ProcessInstance instance, JsonNode node,
                                 NodeContext context, List<Long> responsibleUsers,
                                 String source) {
        if (!V2RuntimeSupport.isV2(instance) || context.nodeInstanceId() == null) {
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
                actual == responsible ? source : source + "_DELEGATION");
            result.add(assignment);
        }
        String mode = NodePolicies.mode(node);
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
    List<Assignment> previewAssignments(long formDefId, JsonNode node,
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
        return "SEQUENTIAL".equals(NodePolicies.mode(node)) ? List.of(result.get(0)) : result;
    }

    void bindTask(TaskEntity task, NodeContext context, Assignment assignment,
                  ProcessInstance instance, JsonNode node) {
        if (!V2RuntimeSupport.isV2(instance) || context.nodeInstanceId() == null) return;
        task.setNodeInstanceId(context.nodeInstanceId());
        task.setSequenceNo(assignment.sequenceNo());
        task.setActionFormRevisionId(instance.getCurrentFormRevisionId());
        if (assignment.actualUserId() != assignment.responsibleUserId()) {
            task.setDelegatedFrom(assignment.responsibleUserId());
        }
        task.setTimeoutAt(NodePolicies.timeoutAt(node));
    }

    void scheduleTimeout(TaskEntity task, JsonNode node) {
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

    List<Long> fieldUsers(JsonNode node, NodeContext context) {
        String field = node.path("props").path("fieldUser").path("fieldId").asText();
        JsonNode value = context.formData().path(field);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (value.isArray()) value.forEach(item -> NodePolicies.addLong(ids, item));
        else NodePolicies.addLong(ids, value);
        return activeUsers(new ArrayList<>(ids));
    }

    /** Resolves non-blocking CC recipients; invalid or unavailable values intentionally disappear. */
    List<Long> ccUsers(JsonNode node, NodeContext context) {
        JsonNode props = node.path("props");
        return switch (props.path("assignedType").asText("ASSIGN_USER")) {
            case "ASSIGN_USER" -> activeUsers(NodePolicies.readIds(props.path("assignedUser")));
            case "ROLE" -> roleUsers(NodePolicies.readIds(props.path("role")));
            case "FIELD_USER" -> fieldUsers(node, context);
            default -> List.of();
        };
    }

    List<Long> fallbackUsers(JsonNode root, JsonNode node) {
        JsonNode fallback = node.path("props").path("fallbackAssignee");
        boolean nodePolicy = NodePolicies.hasNodeFallbackPolicy(root);
        if (!fallback.isObject() && !nodePolicy) fallback = NodePolicies.settings(root).path("fallbackAssignee");
        List<Long> result = switch (fallback.path("type").asText()) {
            case "USER" -> activeUsers(NodePolicies.readIds(fallback.path("ids")));
            case "ROLE" -> roleUsers(NodePolicies.readIds(fallback.path("ids")));
            default -> List.of();
        };
        if (result.size() > MAX_FALLBACK_ASSIGNEES) {
            throw new BizException("ROLE_ASSIGNEE_TOO_MANY",
                "兜底角色成员数量超过上限 " + MAX_FALLBACK_ASSIGNEES);
        }
        if (!result.isEmpty() || nodePolicy) return result;
        return jdbc.queryForList("""
            SELECT DISTINCT user_row.id
            FROM t_user user_row
            JOIN t_user_role user_role ON user_role.user_id = user_row.id
            JOIN t_role role ON role.id = user_role.role_id
            WHERE role.code = 'admin' AND role.enabled = true AND user_row.status = 'ACTIVE'
            ORDER BY user_row.id
            """, Long.class);
    }

    BizException fallbackUnavailable(JsonNode node) {
        return new BizException("FALLBACK_UNAVAILABLE", "审批节点 " + node.path("id").asText()
            + " 的兜底对象当前不可用，请修复配置后重试");
    }

    boolean requiresNodeFallbackPolicy(JsonNode root) {
        return NodePolicies.hasNodeFallbackPolicy(root);
    }

    boolean shouldAutoPass(JsonNode root, ProcessInstance instance, List<Long> users) {
        if (!V2RuntimeSupport.isV2(instance) || users.size() != 1) return false;
        if (shouldAutoPassPreview(root, instance.getStartedBy(), users)) return true;
        JsonNode settings = NodePolicies.settings(root);
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

    boolean shouldAutoPassPreview(JsonNode root, long starterId, List<Long> users) {
        return users.size() == 1
            && NodePolicies.settings(root).path("skipStarterAsApprover").asBoolean(false)
            && Objects.equals(starterId, users.get(0));
    }

    boolean shouldSkipResubmittedNode(JsonNode root, ProcessInstance instance,
                                      JsonNode node) {
        if (!V2RuntimeSupport.isV2(instance) || instance.getRoundNo() == null || instance.getRoundNo() < 2
            || !"DIFF_CONTINUE".equals(NodePolicies.settings(root).path("resubmitStrategy").asText("FULL"))) {
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
    boolean shouldSkipResubmittedNodePreview(JsonNode root, ProcessInstance instance,
                                             JsonNode node, JsonNode candidateData) {
        if (!V2RuntimeSupport.isV2(instance)
            || !"DIFF_CONTINUE".equals(NodePolicies.settings(root).path("resubmitStrategy").asText("FULL"))) {
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
}

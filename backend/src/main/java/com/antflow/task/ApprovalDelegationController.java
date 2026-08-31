package com.antflow.task;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Own-user leave delegation settings; new tasks resolve the agent at node activation. */
@RestController
@RequestMapping("/api/approval-delegations")
@RequiredArgsConstructor
public class ApprovalDelegationController {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditService audit;

    @GetMapping
    public List<Delegation> list() {
        long userId = principal();
        return jdbc.query("""
            SELECT delegation.id, delegation.agent_id, agent.display_name AS agent_name,
                   delegation.form_def_id, delegation.starts_at, delegation.ends_at,
                   delegation.status
            FROM t_approval_delegation delegation
            JOIN t_user agent ON agent.id = delegation.agent_id
            WHERE delegation.principal_id = ? ORDER BY delegation.starts_at DESC, delegation.id DESC
            """, (rs, rowNum) -> delegation(rs), userId);
    }

    @PostMapping
    @Transactional
    public Map<String, Long> create(@RequestBody CreateRequest request) {
        long userId = principal();
        if (request == null || request.agentId() == null || request.startsAt() == null
            || request.endsAt() == null || !request.startsAt().isBefore(request.endsAt())
            || userId == request.agentId()) {
            throw new BizException("BAD_DELEGATION", "代理人和有效时间配置无效");
        }
        Long active = jdbc.query("SELECT id FROM t_user WHERE id = ? AND status = 'ACTIVE'",
            rs -> rs.next() ? rs.getLong(1) : null, request.agentId());
        if (active == null) throw new BizException("BAD_DELEGATION", "代理人不存在或已停用");
        Integer overlap = jdbc.queryForObject("""
            SELECT COUNT(*) FROM t_approval_delegation
            WHERE principal_id = ? AND status = 'ACTIVE'
              AND form_def_id IS NOT DISTINCT FROM ?
              AND starts_at < ? AND ends_at > ?
            """, Integer.class, userId, request.formDefinitionId(),
            request.endsAt(), request.startsAt());
        if (overlap != null && overlap > 0) {
            throw new BizException("DELEGATION_OVERLAP", "同一范围内已有重叠的代理设置");
        }
        Integer cycle = jdbc.queryForObject("""
            WITH RECURSIVE chain(user_id) AS (
              SELECT ?::bigint
              UNION
              SELECT delegation.agent_id
              FROM t_approval_delegation delegation
              JOIN chain ON chain.user_id = delegation.principal_id
              WHERE delegation.status = 'ACTIVE'
                AND delegation.starts_at < ? AND delegation.ends_at > ?
            )
            SELECT COUNT(*) FROM chain WHERE user_id = ?
            """, Integer.class, request.agentId(), request.endsAt(), request.startsAt(), userId);
        if (cycle != null && cycle > 0) {
            throw new BizException("DELEGATION_CYCLE", "代理设置会形成循环");
        }
        Long id = jdbc.query("""
            INSERT INTO t_approval_delegation(
                principal_id, agent_id, form_def_id, starts_at, ends_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """, rs -> rs.next() ? rs.getLong(1) : null, userId, request.agentId(),
            request.formDefinitionId(), request.startsAt(), request.endsAt(), userId);
        audit.success("workflow.delegation.create", "APPROVAL_DELEGATION", id,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("status")),
            Map.of("agentId", request.agentId()));
        return Map.of("id", id);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void disable(@PathVariable long id) {
        long userId = principal();
        int updated = jdbc.update("""
            UPDATE t_approval_delegation SET status = 'DISABLED'
            WHERE id = ? AND principal_id = ? AND status = 'ACTIVE'
            """, id, userId);
        if (updated == 0) throw new BizException("NOT_FOUND", "有效代理设置不存在");
        audit.success("workflow.delegation.disable", "APPROVAL_DELEGATION", id,
            AuditService.RiskLevel.HIGH, Map.of("changedFields", List.of("status")), Map.of());
    }

    private long principal() {
        authorization.requirePermission(PermissionCodes.WORKFLOW_TASK_DELEGATE);
        return PrincipalHolder.current().orElseThrow().userId();
    }

    private static Delegation delegation(ResultSet rs) throws SQLException {
        long formId = rs.getLong("form_def_id");
        Long nullableFormId = rs.wasNull() ? null : formId;
        return new Delegation(rs.getLong("id"), rs.getLong("agent_id"),
            rs.getString("agent_name"), nullableFormId,
            rs.getObject("starts_at", OffsetDateTime.class),
            rs.getObject("ends_at", OffsetDateTime.class), rs.getString("status"));
    }

    public record CreateRequest(Long agentId, Long formDefinitionId,
                                OffsetDateTime startsAt, OffsetDateTime endsAt) { }
    public record Delegation(long id, long agentId, String agentName, Long formDefinitionId,
                             OffsetDateTime startsAt, OffsetDateTime endsAt, String status) { }
}

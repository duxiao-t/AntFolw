package com.antflow.authz;

import com.antflow.auth.PrincipalHolder;
import com.antflow.audit.AuditService;
import com.antflow.engine.BizException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FormGrantService {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public FormGrantDto get(long formId) {
        authorizationService.requireFormAction(formId, PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        Integer version = version(formId);
        return new FormGrantDto(version,
            jdbcTemplate.queryForList("""
                SELECT subject_id FROM t_form_resource_grant
                WHERE form_def_id = ? AND subject_type = 'USER' ORDER BY subject_id
                """, Long.class, formId),
            jdbcTemplate.queryForList("""
                SELECT subject_id FROM t_form_resource_grant
                WHERE form_def_id = ? AND subject_type = 'ROLE' ORDER BY subject_id
                """, Long.class, formId));
    }

    public FormGrantCandidates candidates(long formId) {
        authorizationService.requireFormAction(formId, PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        return loadCandidates();
    }

    public FormGrantCandidates candidates() {
        authorizationService.requirePermission(PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        return loadCandidates();
    }

    private FormGrantCandidates loadCandidates() {
        List<GrantUser> users = jdbcTemplate.query("""
            SELECT id, username, display_name, employee_no
            FROM t_user WHERE status = 'ACTIVE' ORDER BY display_name, id
            """, (rs, row) -> new GrantUser(rs.getLong("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("employee_no")));
        List<GrantRole> roles = jdbcTemplate.query("""
            SELECT id, code, name FROM t_role WHERE enabled = true ORDER BY builtin DESC, id
            """, (rs, row) -> new GrantRole(rs.getLong("id"), rs.getString("code"),
                rs.getString("name")));
        return new FormGrantCandidates(users, roles);
    }

    @Transactional
    public FormGrantDto replace(long formId, FormGrantWriteRequest request) {
        authorizationService.requireFormAction(formId, PermissionCodes.FORM_AUTHORIZATION_MANAGE);
        if (request == null || request.version() == null) {
            throw new BizException("FORM_GRANT_VERSION_REQUIRED", "grant version is required");
        }
        validateSubjects(request.userIds(), request.roleIds());
        int updated = jdbcTemplate.update("""
            UPDATE t_form_definition SET authz_version = authz_version + 1
            WHERE id = ? AND authz_version = ?
            """, formId, request.version());
        if (updated != 1) {
            throw new BizException("FORM_GRANT_VERSION_CONFLICT",
                "form administrators were changed by another user");
        }
        jdbcTemplate.update("DELETE FROM t_form_resource_grant WHERE form_def_id = ?", formId);
        Long actorId = PrincipalHolder.current().orElseThrow().userId();
        request.userIds().forEach(userId -> jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'USER', ?, ?)
            """, formId, userId, actorId));
        request.roleIds().forEach(roleId -> jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'ROLE', ?, ?)
            """, formId, roleId, actorId));
        auditService.success("form.authorization.update", "FORM", formId,
            AuditService.RiskLevel.HIGH,
            java.util.Map.of("changedFields", List.of("userIds", "roleIds")),
            java.util.Map.of("userCount", request.userIds().size(),
                "roleCount", request.roleIds().size()));
        return new FormGrantDto(request.version() + 1,
            request.userIds().stream().sorted().toList(),
            request.roleIds().stream().sorted().toList());
    }

    @Transactional
    public void grantCreator(long formId, long creatorId) {
        jdbcTemplate.update("""
            INSERT INTO t_form_resource_grant(form_def_id, subject_type, subject_id, granted_by)
            VALUES (?, 'USER', ?, ?)
            ON CONFLICT (form_def_id, subject_type, subject_id) DO NOTHING
            """, formId, creatorId, creatorId);
    }

    private void validateSubjects(Set<Long> userIds, Set<Long> roleIds) {
        for (Long userId : userIds) {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_user WHERE id = ?",
                Long.class, userId);
            if (count == null || count == 0) {
                throw new BizException("USER_NOT_FOUND", "grant user not found");
            }
        }
        for (Long roleId : roleIds) {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_role WHERE id = ?",
                Long.class, roleId);
            if (count == null || count == 0) {
                throw new BizException("ROLE_NOT_FOUND", "grant role not found");
            }
        }
    }

    private int version(long formId) {
        Integer version = jdbcTemplate.query("""
            SELECT authz_version FROM t_form_definition WHERE id = ? AND deleted = 0
            """, rs -> rs.next() ? rs.getInt(1) : null, formId);
        if (version == null) {
            throw new HiddenResourceException("form not found");
        }
        return version;
    }

    public record FormGrantDto(int version, List<Long> userIds, List<Long> roleIds) { }
    public record FormGrantCandidates(List<GrantUser> users, List<GrantRole> roles) { }
    public record GrantUser(long id, String username, String displayName, String employeeNo) { }
    public record GrantRole(long id, String code, String name) { }
    public record FormGrantWriteRequest(Integer version, Set<Long> userIds, Set<Long> roleIds) {
        public FormGrantWriteRequest {
            userIds = userIds == null ? Set.of() : new LinkedHashSet<>(userIds);
            roleIds = roleIds == null ? Set.of() : new LinkedHashSet<>(roleIds);
        }
    }
}

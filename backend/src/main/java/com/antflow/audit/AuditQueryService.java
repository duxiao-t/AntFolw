package com.antflow.audit;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    public AuditPage search(AuditSearch search) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_AUDIT_READ);
        QueryParts parts = where(search);
        int page = Math.max(1, search.page());
        int size = Math.min(100, Math.max(1, search.size()));
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_audit_event " + parts.sql(),
            Long.class, parts.args().toArray());
        List<Object> args = new ArrayList<>(parts.args());
        args.add(size);
        args.add((page - 1L) * size);
        List<AuditEventDto> records = jdbcTemplate.query("""
            SELECT * FROM t_audit_event
            """ + parts.sql() + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
            (rs, row) -> event(rs), args.toArray());
        return new AuditPage(records, total == null ? 0 : total, page, size);
    }

    public AuditEventDto detail(long id) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_AUDIT_READ);
        AuditEventDto event = jdbcTemplate.query("SELECT * FROM t_audit_event WHERE id = ?",
            rs -> rs.next() ? event(rs) : null, id);
        if (event == null) throw new com.antflow.authz.HiddenResourceException("audit event not found");
        return event;
    }

    public byte[] export(AuditSearch search) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_AUDIT_EXPORT);
        QueryParts parts = where(search);
        List<AuditEventDto> records = jdbcTemplate.query("""
            SELECT * FROM t_audit_event
            """ + parts.sql() + " ORDER BY occurred_at DESC, id DESC LIMIT 10000",
            (rs, row) -> event(rs), parts.args().toArray());
        StringBuilder ndjson = new StringBuilder();
        records.forEach(event -> {
            try {
                ndjson.append(objectMapper.writeValueAsString(event)).append('\n');
            } catch (Exception exception) {
                throw new IllegalStateException("could not serialize audit export", exception);
            }
        });
        return ndjson.toString().getBytes(StandardCharsets.UTF_8);
    }

    private QueryParts where(AuditSearch search) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(conditions, args, "occurred_at >= ?", search.from());
        add(conditions, args, "occurred_at < ?", search.to());
        add(conditions, args, "actor_user_id = ?", search.operatorId());
        add(conditions, args, "action = ?", normalized(search.action()));
        add(conditions, args, "resource_type = ?", normalized(search.resourceType()));
        add(conditions, args, "resource_id = ?", normalized(search.resourceId()));
        add(conditions, args, "result = ?", normalized(search.result()));
        add(conditions, args, "risk_level = ?", normalized(search.riskLevel()));
        add(conditions, args, "client_ip = ?", normalized(search.ip()));
        return new QueryParts(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), args);
    }

    private static void add(List<String> conditions, List<Object> args,
                            String condition, Object value) {
        if (value != null) {
            conditions.add(condition);
            args.add(value);
        }
    }

    private AuditEventDto event(ResultSet rs) throws SQLException {
        return new AuditEventDto(rs.getLong("id"),
            rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("request_id"),
            nullableLong(rs, "actor_user_id"), rs.getString("actor_username"),
            rs.getString("actor_display_name"), rs.getObject("session_id", java.util.UUID.class),
            rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"),
            rs.getString("result"), rs.getString("risk_level"), rs.getString("client_ip"),
            rs.getString("user_agent"), rs.getString("failure_code"),
            jsonMap(rs.getString("field_diff")), jsonMap(rs.getString("metadata")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, LinkedHashMap.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record QueryParts(String sql, List<Object> args) { }
    public record AuditSearch(OffsetDateTime from, OffsetDateTime to, Long operatorId,
                              String action, String resourceType, String resourceId,
                              String result, String riskLevel, String ip, int page, int size) { }
    public record AuditPage(List<AuditEventDto> records, long total, int page, int size) { }
    public record AuditEventDto(long id, OffsetDateTime occurredAt, String requestId,
                                Long actorUserId, String actorUsername, String actorDisplayName,
                                java.util.UUID sessionId, String action, String resourceType,
                                String resourceId, String result, String riskLevel, String clientIp,
                                String userAgent, String failureCode, Map<String, Object> fieldDiff,
                                Map<String, Object> metadata) { }
}

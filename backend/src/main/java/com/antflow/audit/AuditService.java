package com.antflow.audit;

import com.antflow.auth.PrincipalHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class AuditService {
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
        "password", "token", "secret", "authorization", "credential", "webhookkey");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TrustedProxyProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public <T> T execute(Supplier<T> operation, Consumer<T> successAudit) {
        T result = operation.get();
        successAudit.accept(result);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void execute(Runnable operation, Runnable successAudit) {
        operation.run();
        successAudit.run();
    }

    public void success(String action, String resourceType, Object resourceId,
                        RiskLevel riskLevel, Map<String, ?> fieldDiff,
                        Map<String, ?> metadata) {
        record(action, resourceType, resourceId, "SUCCESS", riskLevel, null, fieldDiff, metadata,
            PrincipalHolder.current().orElse(null));
    }

    public void successAs(PrincipalHolder.Principal actor, String action, String resourceType,
                          Object resourceId, RiskLevel riskLevel, Map<String, ?> fieldDiff,
                          Map<String, ?> metadata) {
        record(action, resourceType, resourceId, "SUCCESS", riskLevel, null, fieldDiff, metadata,
            actor);
    }

    public void denied(String action, String resourceType, Object resourceId,
                       String failureCode, Map<String, ?> metadata) {
        record(action, resourceType, resourceId, "DENIED", RiskLevel.HIGH,
            failureCode, Map.of(), metadata, PrincipalHolder.current().orElse(null));
    }

    public void failure(String action, String resourceType, Object resourceId,
                        RiskLevel riskLevel, String failureCode, Map<String, ?> metadata) {
        record(action, resourceType, resourceId, "FAILURE", riskLevel,
            failureCode, Map.of(), metadata, PrincipalHolder.current().orElse(null));
    }

    private void record(String action, String resourceType, Object resourceId, String result,
                        RiskLevel riskLevel, String failureCode, Map<String, ?> fieldDiff,
                        Map<String, ?> metadata, PrincipalHolder.Principal principal) {
        HttpServletRequest request = currentRequest();
        jdbcTemplate.update("""
            INSERT INTO t_audit_event(
                occurred_at, request_id, actor_user_id, actor_username, actor_display_name,
                session_id, action, resource_type, resource_id, result, risk_level,
                client_ip, user_agent, failure_code, field_diff, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """,
            OffsetDateTime.now(ZoneOffset.UTC), requestId(request),
            principal == null ? null : principal.userId(),
            principal == null ? null : principal.username(),
            principal == null ? null : principal.displayName(),
            principal == null ? null : principal.sessionId(),
            action, resourceType, resourceId == null ? null : String.valueOf(resourceId),
            result, riskLevel.name(), clientIp(request),
            truncate(request == null ? null : request.getHeader(HttpHeaders.USER_AGENT), 512),
            failureCode, json(sanitize(fieldDiff)), json(sanitize(metadata)));
    }

    public String requestId(HttpServletRequest request) {
        if (request != null) {
            Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
            if (value != null) return value.toString();
        }
        return UUID.randomUUID().toString();
    }

    public String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String remote = request.getRemoteAddr();
        if (isTrustedProxy(remote)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",", 2)[0].trim();
                if (isIp(first)) return first;
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && isIp(realIp.trim())) return realIp.trim();
        }
        return remote;
    }

    private boolean isTrustedProxy(String address) {
        if (!isIp(address)) return false;
        return properties.getTrustedProxies().stream().anyMatch(rule -> matchesAddress(address, rule));
    }

    private boolean matchesAddress(String address, String rule) {
        try {
            if (!rule.contains("/")) return InetAddress.getByName(address)
                .equals(InetAddress.getByName(rule));
            String[] parts = rule.split("/", 2);
            byte[] value = InetAddress.getByName(address).getAddress();
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int prefix = Integer.parseInt(parts[1]);
            if (value.length != network.length || prefix < 0 || prefix > value.length * 8) return false;
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - (bit % 8));
                if ((value[bit / 8] & mask) != (network[bit / 8] & mask)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isIp(String value) {
        if (value == null || value.isBlank() || !value.matches("[0-9A-Fa-f:.]+")) return false;
        try {
            InetAddress.getByName(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object sanitize(Object value) {
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                String normalized = name.toLowerCase(Locale.ROOT).replace("_", "");
                if (SENSITIVE_KEY_PARTS.stream().noneMatch(normalized::contains)) {
                    result.put(name, sanitizeValue(item));
                }
            });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sanitizeValue).toList();
        }
        return sanitizeValue(value);
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) return sanitize(value);
        if (value instanceof String string) return truncate(string, 1000);
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    public enum RiskLevel { NORMAL, HIGH, CRITICAL }
}

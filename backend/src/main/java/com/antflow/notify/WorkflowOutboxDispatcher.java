package com.antflow.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable in-app notification consumer for workflow outbox events. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowOutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final NotificationPublisher publisher;
    private final String workerId = UUID.randomUUID().toString();

    @Scheduled(fixedDelayString = "${antflow.outbox.poll-interval-ms:1000}")
    public void poll() {
        try {
            for (int i = 0; i < 50; i++) {
                Event event = claim();
                if (event == null) return;
                deliver(event);
            }
        } catch (Exception error) {
            log.warn("Workflow outbox polling failed: {}", error.toString());
        }
    }

    Event claim() {
        return jdbc.query("""
            WITH candidate AS (
              SELECT id FROM t_workflow_outbox
              WHERE ((status = 'PENDING' AND next_attempt_at <= now())
                  OR (status = 'RUNNING' AND locked_at < now() - interval '2 minutes'))
              ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE t_workflow_outbox event
            SET status = 'RUNNING', attempts = attempts + 1,
                locked_at = now(), locked_by = ?
            FROM candidate WHERE event.id = candidate.id
            RETURNING event.id, event.aggregate_id, event.event_type,
                      event.recipient_id, event.payload::text, event.attempts
            """, rs -> rs.next() ? new Event(
                rs.getObject("id", UUID.class), rs.getLong("aggregate_id"),
                rs.getString("event_type"), nullableLong(rs.getObject("recipient_id")),
                rs.getString("payload"), rs.getInt("attempts")) : null, workerId);
    }

    void deliver(Event event) {
        try {
            JsonNode payload = json.readTree(event.payload());
            Long taskId = payload.path("taskId").isNumber()
                ? payload.path("taskId").asLong() : null;
            if (event.recipientId() != null) {
                jdbc.update("""
                    INSERT INTO t_user_notification(event_id, user_id, event_type, title, payload)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (event_id, user_id) DO NOTHING
                    """, event.id(), event.recipientId(), event.type(), title(event.type()),
                    event.payload());
            }
            publisher.publishReliable(new NotificationEvent(this, event.type(), event.instanceId(),
                taskId, event.recipientId(), title(event.type())));
            jdbc.update("""
                UPDATE t_workflow_outbox
                SET status = 'DELIVERED', delivered_at = now(),
                    locked_at = NULL, locked_by = NULL, last_error = NULL
                WHERE id = ? AND status = 'RUNNING' AND locked_by = ?
                """, event.id(), workerId);
        } catch (Exception error) {
            String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
            jdbc.update("""
                UPDATE t_workflow_outbox
                SET status = CASE WHEN attempts >= 10 THEN 'DEAD' ELSE 'PENDING' END,
                    next_attempt_at = now() + make_interval(secs => LEAST(3600, power(2, attempts)::int)),
                    locked_at = NULL, locked_by = NULL, last_error = left(?, 2000)
                WHERE id = ? AND status = 'RUNNING' AND locked_by = ?
                """, message, event.id(), workerId);
            log.warn("Workflow outbox {} delivery failed: {}", event.id(), message);
        }
    }

    private static String title(String type) {
        return switch (type) {
            case "TASK_ASSIGNED" -> "您有新的审批任务";
            case "TASK_RETURNED" -> "申请已退回修改";
            case "TASK_CANCELLED" -> "审批任务已作废";
            case "APPROVAL_INVALIDATED" -> "您的审批已作废";
            case "CC_ASSIGNED" -> "您收到一条抄送";
            case "INSTANCE_APPROVED" -> "流程已审批通过";
            case "INSTANCE_REJECTED" -> "流程已被驳回";
            default -> "流程状态已更新";
        };
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    record Event(UUID id, long instanceId, String type, Long recipientId,
                 String payload, int attempts) { }
}

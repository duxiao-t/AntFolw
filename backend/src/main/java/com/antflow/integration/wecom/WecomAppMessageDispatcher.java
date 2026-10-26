package com.antflow.integration.wecom;

import com.antflow.auth.ExternalAuthProperties;
import com.antflow.engine.BizException;
import com.antflow.notify.NotificationEvent;
import com.antflow.notify.NotificationListener;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 企微应用消息：事件入队、抢占投递、失败退避（原 WecomAppService 的 NotificationListener 部分）。
 *
 * 易错点：
 * 1. 入队用 dedupe_key + ON CONFLICT DO NOTHING，重复事件只落一行；dedupe_key 缺失时按 4 元组拼，规则不能改。
 * 2. 抢占是 FOR UPDATE SKIP LOCKED + locked_by = workerId，投递回写必须带 status = 'RUNNING' AND locked_by = ?，
 *    否则会覆盖别的实例已经接手的行。
 * 3. 失败退避是 2^attempts 秒、上限 3600s，attempts >= 10 才置 DEAD；重试次数在抢占时就 +1，不是失败时才加。
 */
@Component
class WecomAppMessageDispatcher implements NotificationListener {
    private static final Set<String> MESSAGE_EVENTS = Set.of(
        "TASK_ASSIGNED", "TASK_RETURNED", "TASK_CANCELLED", "APPROVAL_INVALIDATED",
        "CC_ASSIGNED", "TASK_TIMEOUT_REMINDER", "INSTANCE_APPROVED", "INSTANCE_REJECTED");
    private final JdbcTemplate jdbc;
    private final WecomClient client;
    private final ExternalAuthProperties authProperties;
    private final WecomAppTokenCache tokens;
    private final String workerId = UUID.randomUUID().toString();

    WecomAppMessageDispatcher(JdbcTemplate jdbc, WecomClient client,
                              ExternalAuthProperties authProperties, WecomAppTokenCache tokens) {
        this.jdbc = jdbc;
        this.client = client;
        this.authProperties = authProperties;
        this.tokens = tokens;
    }

    @Override
    public boolean accepts(NotificationEvent event) {
        return event.getUserId() != null && MESSAGE_EVENTS.contains(event.getType());
    }

    @Override
    public void onEvent(NotificationEvent event) {
        String key = event.getDeliveryKey();
        if (key == null || key.isBlank()) {
            key = String.join(":", event.getType(), String.valueOf(event.getProcInstId()),
                String.valueOf(event.getTaskId()), String.valueOf(event.getUserId()));
        }
        jdbc.update("""
            INSERT INTO t_wecom_message_delivery(
                dedupe_key, event_type, proc_inst_id, task_id, recipient_id, title)
            SELECT ?, ?, ?, ?, ?, ?
            WHERE EXISTS (
                SELECT 1 FROM t_wecom_user_mapping mapping
                JOIN t_wecom_config config ON config.company_id = mapping.company_id
                WHERE mapping.user_id = ? AND config.message_enabled)
            ON CONFLICT (dedupe_key) DO NOTHING
            """, key, event.getType(), event.getProcInstId(), event.getTaskId(),
            event.getUserId(), title(event.getType()), event.getUserId());
    }

    @Scheduled(fixedDelayString = "${antflow.wecom.message-poll-interval-ms:1000}")
    public void pollMessages() {
        try {
            for (int i = 0; i < 25; i++) {
                Delivery delivery = claim();
                if (delivery == null) return;
                deliver(delivery);
            }
        } catch (RuntimeException ignored) {
            // The next scheduled poll retries persisted work.
        }
    }

    Delivery claim() {
        return jdbc.query("""
            WITH candidate AS (
                SELECT id FROM t_wecom_message_delivery
                WHERE (status = 'PENDING' AND next_attempt_at <= now())
                   OR (status = 'RUNNING' AND locked_at < now() - interval '2 minutes')
                ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
            UPDATE t_wecom_message_delivery delivery
            SET status = 'RUNNING', attempts = attempts + 1, locked_at = now(), locked_by = ?
            FROM candidate WHERE delivery.id = candidate.id
            RETURNING delivery.id, delivery.event_type, delivery.proc_inst_id,
                      delivery.task_id, delivery.recipient_id, delivery.title, delivery.attempts
            """, rs -> rs.next() ? new Delivery(rs.getLong(1), rs.getString(2),
                WecomAppSupport.nullableLong(rs.getObject(3)), WecomAppSupport.nullableLong(rs.getObject(4)),
                rs.getLong(5), rs.getString(6), rs.getInt(7)) : null, workerId);
    }

    void deliver(Delivery delivery) {
        try {
            Recipient recipient = recipient(delivery.recipientId());
            if (recipient == null) throw new BizException("WECOM_DELIVERY_UNAVAILABLE", "企业微信应用消息未启用或成员未映射");
            String url = authProperties.publicBaseUri().resolve(deepLink(delivery)).toString();
            client.sendTextCard(tokens.session(recipient.config()), recipient.config().agentId(),
                recipient.wecomUserId(), delivery.title(), delivery.title(), url);
            jdbc.update("""
                UPDATE t_wecom_message_delivery SET status = 'DELIVERED', delivered_at = now(),
                    locked_at = NULL, locked_by = NULL, last_error = NULL
                WHERE id = ? AND status = 'RUNNING' AND locked_by = ?
                """, delivery.id(), workerId);
        } catch (RuntimeException error) {
            jdbc.update("""
                UPDATE t_wecom_message_delivery
                SET status = CASE WHEN attempts >= 10 THEN 'DEAD' ELSE 'PENDING' END,
                    next_attempt_at = now() + make_interval(secs => LEAST(3600, power(2, attempts)::int)),
                    locked_at = NULL, locked_by = NULL, last_error = left(?, 2000)
                WHERE id = ? AND status = 'RUNNING' AND locked_by = ?
                """, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                delivery.id(), workerId);
        }
    }

    private String deepLink(Delivery delivery) {
        if (delivery.taskId() != null && ("TASK_ASSIGNED".equals(delivery.eventType())
            || "TASK_RETURNED".equals(delivery.eventType())
            || "TASK_TIMEOUT_REMINDER".equals(delivery.eventType()))) {
            TaskLink task = jdbc.query("""
                SELECT task.task_type, form.code FROM t_task task
                JOIN t_process_instance instance ON instance.id = task.proc_inst_id
                JOIN t_process_definition process ON process.id = instance.proc_def_id
                JOIN t_form_definition form ON form.id = process.form_def_id
                WHERE task.id = ?
                """, rs -> rs.next() ? new TaskLink(rs.getString(1), rs.getString(2)) : null,
                delivery.taskId());
            if (task != null && "REWORK".equals(task.type())) {
                return "/mobile/forms/" + WecomAppSupport.encode(task.formCode()) + "?reworkTaskId=" + delivery.taskId();
            }
            return "/mobile/tasks/" + delivery.taskId();
        }
        return "/mobile/processes/" + delivery.instanceId();
    }

    /**
     * 取收件人与其要用的应用配置。
     *
     * <p>单公司后不再按公司匹配配置：系统里只有一份配置，直接取它（ORDER BY company_id 只是为了确定取哪一行）。
     * {@code config.company_id} 仍然要选出来——它是密文的 AAD 上下文（见 {@code WecomSecretCipher}）。
     */
    Recipient recipient(long userId) {
        return jdbc.query("""
            SELECT mapping.wecom_user_id, config.company_id, config.corp_id, config.agent_id,
                   config.agent_secret_encrypted, config.oauth_enabled, config.js_sdk_enabled,
                   config.message_enabled
            FROM t_wecom_user_mapping mapping
            CROSS JOIN (SELECT * FROM t_wecom_config ORDER BY company_id LIMIT 1) config
            WHERE mapping.user_id = ? AND config.message_enabled
            """, rs -> rs.next() ? new Recipient(rs.getString(1), WecomAppConfigStore.appConfig(rs)) : null,
            userId);
    }

    private static String title(String type) {
        return switch (type) {
            case "TASK_ASSIGNED" -> "您有新的审批任务";
            case "TASK_RETURNED" -> "申请已退回修改";
            case "TASK_CANCELLED" -> "审批任务已作废";
            case "APPROVAL_INVALIDATED" -> "您的审批已作废";
            case "CC_ASSIGNED" -> "您收到本轮抄送汇总";
            case "TASK_TIMEOUT_REMINDER" -> "审批任务即将超时";
            case "INSTANCE_APPROVED" -> "流程已审批通过";
            case "INSTANCE_REJECTED" -> "流程已被驳回";
            // 与站内信保持同一口径：修正不改流程状态，但要让当事人知道数据变了
            case "DATA_CORRECTED" -> "单据数据已被修正";
            default -> "流程状态已更新";
        };
    }

    record Recipient(String wecomUserId, WecomAppConfig config) { }
    record Delivery(long id, String eventType, Long instanceId, Long taskId,
                    long recipientId, String title, int attempts) { }
    private record TaskLink(String type, String formCode) { }
}

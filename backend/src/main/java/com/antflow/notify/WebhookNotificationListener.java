package com.antflow.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Webhook 通知监听器 — Sprint 3 B14：
 * 实例完成 (APPROVED / REJECTED / WITHDRAWN) 时 POST JSON 到配置好的 URL。
 *
 * <p>URL 通过 application.yml 配置：
 * <pre>{@code
 * antflow:
 *   webhook:
 *     on-complete: https://your-app/api/flow-event
 * }</pre>
 *
 * <p>无 URL 配置则跳过（默认）。
 */
@Component
@Order(100)
@Slf4j
@RequiredArgsConstructor
public class WebhookNotificationListener implements NotificationListener {

    private final ObjectMapper json;

    @Value("${antflow.webhook.on-complete:}")
    private String onCompleteUrl;

    private static final List<String> INSTANCE_FINAL_EVENTS = List.of(
        "INSTANCE_APPROVED", "INSTANCE_REJECTED", "INSTANCE_WITHDRAWN"
    );

    @Override public String name() { return "webhook"; }

    @Override public boolean accepts(NotificationEvent e) {
        return onCompleteUrl != null && !onCompleteUrl.isBlank()
            && INSTANCE_FINAL_EVENTS.contains(e.getType());
    }

    @Override
    public void onEvent(NotificationEvent e) {
        if (!accepts(e)) return;
        try {
            // 投递是 at-least-once（异常后 outbox 会重投），所以必须给出一个**稳定的事件键**让
            // 接收端能幂等：同一个 outbox 行重投时键不变，不同事件一定不同。
            String eventKey = e.getDeliveryKey();
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("type", e.getType());
            payload.put("procInstId", e.getProcInstId());
            payload.put("userId", e.getUserId());
            payload.put("message", e.getMessage());
            payload.put("timestamp", java.time.Instant.now().toString());
            if (eventKey != null && !eventKey.isBlank()) payload.put("eventKey", eventKey);
            String body = json.writeValueAsString(payload);
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(onCompleteUrl))
                .header("Content-Type", "application/json");
            if (eventKey != null && !eventKey.isBlank()) {
                request.header("X-AntFlow-Event-Key", eventKey);
            }
            HttpRequest req = request
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("webhook returned HTTP " + resp.statusCode());
            }
            log.info("[webhook] type={} inst={} -> HTTP {}",
                e.getType(), e.getProcInstId(), resp.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("webhook interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("webhook failed for instance " + e.getProcInstId(), ex);
        }
    }
}

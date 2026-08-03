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

    @Override
    public String name() { return "webhook"; }

    @Override
    public boolean accepts(NotificationEvent e) {
        return onCompleteUrl != null && !onCompleteUrl.isBlank()
            && INSTANCE_FINAL_EVENTS.contains(e.getType());
    }

    @Override
    public void onEvent(NotificationEvent e) {
        try {
            String body = json.writeValueAsString(Map.of(
                "type", e.getType(),
                "procInstId", e.getProcInstId(),
                "userId", e.getUserId(),
                "message", e.getMessage(),
                "timestamp", java.time.Instant.now().toString()
            ));
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(onCompleteUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("[webhook] type={} inst={} -> HTTP {}",
                e.getType(), e.getProcInstId(), resp.statusCode());
        } catch (Exception ex) {
            log.warn("[webhook] failed for inst={}: {}", e.getProcInstId(), ex.toString());
        }
    }
}
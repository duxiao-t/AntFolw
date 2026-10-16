package com.antflow.notify;

import com.antflow.automation.WebhookSecurityPolicy;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
 *
 * <p><b>出站校验与 TRIGGER 的 {@code WebhookClient} 共用</b>（{@link WebhookSecurityPolicy}）：
 * 这个 URL 是**服务端配置**（不是用户可写的），但仍然走同一套 scheme / 白名单 / 地址黑名单，
 * 免得两条出站通道里有一条没有护栏——那正是审计 A7-1 记的"防护不一致"。
 * 启动时另用 {@code validateDefinition}（不做 DNS）先校验一次，配错在启动就喊，而不是等 10 次退避重试变 DEAD。
 */
@Component
@Order(100)
@Slf4j
@RequiredArgsConstructor
public class WebhookNotificationListener implements NotificationListener {

    private final ObjectMapper json;
    private final WebhookSecurityPolicy securityPolicy;

    @Value("${antflow.webhook.on-complete:}")
    private String onCompleteUrl;

    private static final List<String> INSTANCE_FINAL_EVENTS = List.of(
        "INSTANCE_APPROVED", "INSTANCE_REJECTED", "INSTANCE_WITHDRAWN"
    );

    /**
     * 启动即校验配置好的 URL：只做不需要 DNS 的静态检查（scheme / 生产强制 https / 白名单）。
     * 不做 DNS —— webhook 主机的解析抖动不该拖着整个应用起不来；地址级检查留给投递时。
     */
    @PostConstruct
    void validateConfiguredUrl() {
        if (onCompleteUrl == null || onCompleteUrl.isBlank()) return; // 默认关闭是合法状态
        try {
            securityPolicy.validateDefinition(parseTarget());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("antflow.webhook.on-complete 配置不合法：" + ex.getMessage()
                + "（把回调主机加进 antflow.automation.allowed-hosts，或改用合规 URL）", ex);
        }
    }

    /** 与 `WebhookClient.parseUri` 同款：URL 不合法也归到 BAD_WEBHOOK，别漏成 IllegalArgumentException。 */
    private URI parseTarget() {
        try {
            return URI.create(onCompleteUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new BizException("BAD_WEBHOOK", "Webhook URL 无效");
        }
    }

    @Override public String name() { return "webhook"; }

    @Override public boolean accepts(NotificationEvent e) {
        return onCompleteUrl != null && !onCompleteUrl.isBlank()
            && INSTANCE_FINAL_EVENTS.contains(e.getType());
    }

    @Override
    public void onEvent(NotificationEvent e) {
        if (!accepts(e)) return;
        // 出站校验放在 try 之外：目标不合法是**配置/策略**问题，不是"投递失败"。放里面会被下面的兜底
        // 包成 "webhook failed for instance X"，日志里就分不清"地址不被允许"和"对面没响应"了。
        URI target = parseTarget();
        securityPolicy.validate(target);
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
                // JDK 默认就是不跟随重定向，显式写出来是防止将来有人顺手改默认；
                // WebhookClient 同样显式写了这一条。
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
            HttpRequest.Builder request = HttpRequest.newBuilder(target)
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

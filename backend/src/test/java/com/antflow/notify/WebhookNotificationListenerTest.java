package com.antflow.notify;

import com.antflow.automation.AutomationProperties;
import com.antflow.automation.WebhookSecurityPolicy;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 投递是 at-least-once（失败后 outbox 会重投），所以 webhook 必须带一个**稳定事件键**，
 * 否则接收端没有任何依据去重，"重投"就等于"再发一次"。
 *
 * <p>另一半是**出站校验**：这个 URL 是服务端配置的，但审计 A7-1 记的是它当时**一个护栏都没有**
 * （TRIGGER 那条有 `WebhookSecurityPolicy`）。下面三条用例就是那段护栏的守护。
 *
 * <p>测试用本机 127.0.0.1 的 HttpServer，属环回地址，所以每条跑得通网络的用例都要在
 * `AutomationProperties` 里显式放行——与 `automation/WebhookClientTest` 同样的写法。
 */
class WebhookNotificationListenerTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsAStableEventKeySoTheReceiverCanDeduplicate() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        String url = startHook(header, body, null);

        WebhookNotificationListener listener = listener(url, List.of("127.0.0.1"), true, false);

        listener.onEvent(new NotificationEvent(this, "INSTANCE_APPROVED", 501L, null, 8L,
            "流程已审批通过", "INSTANCE_APPROVED:501:6f1e0d2c-0000-4000-8000-000000000001"));

        assertThat(header.get()).isEqualTo("INSTANCE_APPROVED:501:6f1e0d2c-0000-4000-8000-000000000001");
        assertThat(body.get())
            .contains("\"eventKey\":\"INSTANCE_APPROVED:501:6f1e0d2c-0000-4000-8000-000000000001\"");
    }

    /** 主机不在白名单：拒绝，且**一个字节都没发出去**。去掉 onEvent 里的 validate 这行必红。 */
    @Test
    void rejectsHostOutsideAllowListAndNeverSends() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String url = startHook(null, null, hits);

        WebhookNotificationListener listener = listener(url, List.of("allowed.example"), true, false);

        assertThatThrownBy(() -> listener.onEvent(finalEvent()))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("允许列表");
        assertThat(hits.get()).isZero();
    }

    /** 环回地址：白名单里有它也照样拒——私网/环回要 `allow-private-addresses` 显式开。 */
    @Test
    void rejectsLoopbackUnlessPrivateAddressesAreAllowed() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String url = startHook(null, null, hits);

        WebhookNotificationListener listener = listener(url, List.of("127.0.0.1"), false, false);

        assertThatThrownBy(() -> listener.onEvent(finalEvent()))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不允许访问");
        assertThat(hits.get()).isZero();
    }

    /**
     * 启动即校验：URL 配错在启动就喊（而不是走完 10 次退避重试变 DEAD）；
     * **不配**是默认关闭的合法状态，不抛。
     */
    @Test
    void startupValidationRejectsMisconfiguredUrlAndToleratesBlank() throws Exception {
        assertThatCode(() -> listener(null, List.of("127.0.0.1"), true, false).validateConfiguredUrl())
            .doesNotThrowAnyException();
        assertThatCode(() -> listener("  ", List.of("127.0.0.1"), true, false).validateConfiguredUrl())
            .doesNotThrowAnyException();

        WebhookNotificationListener misconfigured =
            listener("http://evil.example/hook", List.of("allowed.example"), false, false);
        assertThatThrownBy(misconfigured::validateConfiguredUrl)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("antflow.webhook.on-complete")
            .hasMessageContaining("allowed-hosts");

        // 连 URL 都解析不了也要给出可读的启动错误，而不是裸的 IllegalArgumentException。
        WebhookNotificationListener garbage =
            listener(":: 不是一个 URL", List.of("allowed.example"), false, false);
        assertThatThrownBy(garbage::validateConfiguredUrl)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("antflow.webhook.on-complete");
    }

    private NotificationEvent finalEvent() {
        return new NotificationEvent(this, "INSTANCE_APPROVED", 501L, null, 8L,
            "流程已审批通过", "INSTANCE_APPROVED:501:6f1e0d2c-0000-4000-8000-000000000001");
    }

    /** 本机起一个 /hook，返回它的 URL；`hits` 非空时记录被调用次数。 */
    private String startHook(AtomicReference<String> header, AtomicReference<String> body,
                             AtomicInteger hits) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            if (hits != null) hits.incrementAndGet();
            if (header != null) header.set(exchange.getRequestHeaders().getFirst("X-AntFlow-Event-Key"));
            if (body != null) {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private static WebhookNotificationListener listener(String url, List<String> allowedHosts,
                                                        boolean allowPrivate, boolean production) {
        AutomationProperties properties = new AutomationProperties();
        properties.setAllowedHosts(allowedHosts);
        properties.setAllowPrivateAddresses(allowPrivate);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(production);
        WebhookNotificationListener listener = new WebhookNotificationListener(
            new ObjectMapper(), new WebhookSecurityPolicy(properties, environment));
        ReflectionTestUtils.setField(listener, "onCompleteUrl", url);
        return listener;
    }
}

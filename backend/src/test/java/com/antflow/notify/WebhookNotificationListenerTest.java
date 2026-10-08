package com.antflow.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投递是 at-least-once（失败后 outbox 会重投），所以 webhook 必须带一个**稳定事件键**，
 * 否则接收端没有任何依据去重，"重投"就等于"再发一次"。
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
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-AntFlow-Event-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        WebhookNotificationListener listener = new WebhookNotificationListener(new ObjectMapper());
        ReflectionTestUtils.setField(listener, "onCompleteUrl",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");

        listener.onEvent(new NotificationEvent(this, "INSTANCE_APPROVED", 501L, null, 8L,
            "流程已审批通过", "INSTANCE_APPROVED:501:6f1e0d2c-0000-4000-8000-000000000001"));

        assertThat(header.get()).isEqualTo("INSTANCE_APPROVED:501:6f1e0d2c-0000-4000-8000-000000000001");
        assertThat(body.get())
            .contains("\"eventKey\":\"INSTANCE_APPROVED:501:6f1e0d2c-0000-4000-8000-000000000001\"");
    }
}

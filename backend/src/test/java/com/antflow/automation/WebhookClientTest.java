package com.antflow.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsSupportedMethodsWithDeliveryIdParametersAndHmac() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> delivery = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            query.set(exchange.getRequestURI().getRawQuery());
            delivery.set(exchange.getRequestHeaders().getFirst("X-AntFlow-Delivery-Id"));
            signature.set(exchange.getRequestHeaders().getFirst("X-AntFlow-Signature"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        AutomationProperties properties = new AutomationProperties();
        properties.setAllowedHosts(List.of("127.0.0.1"));
        properties.setAllowPrivateAddresses(true);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        ObjectMapper json = new ObjectMapper();
        WebhookClient client = new WebhookClient(
            properties, new WebhookSecurityPolicy(properties, environment), json);
        WorkflowJob job = new WorkflowJob();
        job.setDeliveryId(UUID.randomUUID());

        for (String requestMethod : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            ObjectNode payload = json.createObjectNode();
            payload.put("method", requestMethod);
            payload.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            payload.put("contentType", "application/json");
            payload.put("secret", "test-signing-secret");
            payload.putArray("headers").addObject().put("key", "X-Custom").put("value", "yes");
            payload.putObject("parameters").put("amount", 12).put("name", "Alice");

            assertThat(client.send(job, payload).successful()).isTrue();
            assertThat(method.get()).isEqualTo(requestMethod);
            assertThat(delivery.get()).isEqualTo(job.getDeliveryId().toString());
            assertThat(signature.get()).startsWith("sha256=").hasSize(71);
            if ("GET".equals(requestMethod) || "DELETE".equals(requestMethod)) {
                assertThat(body.get()).isEmpty();
                assertThat(query.get()).contains("amount=12", "name=Alice");
            } else {
                assertThat(body.get()).contains("\"amount\":12", "\"name\":\"Alice\"");
            }
        }
    }
}

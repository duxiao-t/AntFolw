package com.antflow.integration.wecom;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WecomClientTest {
    private HttpServer server;
    private final AtomicInteger tokens = new AtomicInteger();
    private final AtomicInteger departments = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> respond(exchange,
            "{\"errcode\":0,\"access_token\":\"token" + tokens.incrementAndGet() + "\"}"));
        server.createContext("/cgi-bin/department/list", exchange -> {
            if (departments.getAndIncrement() == 0) {
                respond(exchange, "{\"errcode\":42001}");
            } else {
                respond(exchange, "{\"errcode\":0,\"department\":["
                    + "{\"id\":2,\"parentid\":1,\"name\":\"研发\",\"order\":20},"
                    + "{\"id\":1,\"parentid\":0,\"name\":\"Acme\",\"order\":10,"
                    + "\"department_leader\":[\"boss\"]}]}");
            }
        });
        server.createContext("/cgi-bin/user/list_id", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, body.contains("cursor-2")
                ? "{\"errcode\":0,\"dept_user\":[{\"userid\":\"u2\",\"department\":2}]}"
                : "{\"errcode\":0,\"dept_user\":[{\"userid\":\"u1\",\"department\":1}],"
                    + "\"next_cursor\":\"cursor-2\"}");
        });
        server.createContext("/cgi-bin/user/get", exchange -> respond(exchange,
            "{\"errcode\":0,\"userid\":\"u1\",\"name\":\"林晓\","
                + "\"department\":[1,2],\"main_department\":2,\"mobile\":\"13800000000\","
                + "\"email\":\"lin@example.com\",\"gender\":\"2\",\"status\":1,"
                + "\"direct_leader\":[\"boss\"]}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void refreshesExpiredTokenAndParsesDepartmentsPaginationAndUser() {
        WecomProperties properties = new WecomProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        WecomClient client = new WecomClient(properties, new ObjectMapper(), HttpClient.newHttpClient());

        WecomClient.Session session = client.connect("corp", "secret");
        assertThat(client.departments(session)).extracting(WecomClient.WecomDepartment::id)
            .containsExactly(2L, 1L);
        assertThat(tokens).hasValue(2);
        assertThat(client.userIds(session)).extracting(WecomClient.WecomUserRef::userId)
            .containsExactly("u1", "u2");
        WecomClient.WecomUser user = client.user(session,
            new WecomClient.WecomUserRef("u1", java.util.List.of(1L)));
        assertThat(user.mainDepartment()).isEqualTo(2);
        assertThat(user.directLeaders()).containsExactly("boss");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

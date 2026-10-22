package com.antflow.auth;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * OIDC 上游 HTTP 调用（原 OidcService#sendJson）。
 * 失败一律映射为 OIDC_UPSTREAM_FAILED 且**不回显响应体**——token 端点的响应体含 access_token，
 * 不能出现在异常消息里。
 */
@Component
class OidcHttpTransport {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    OidcHttpTransport(ObjectMapper json) {
        this.json = json;
    }

    JsonNode sendJson(HttpRequest request, String label) {
        try {
            HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("OIDC_UPSTREAM_FAILED", label + " 请求失败");
            }
            return json.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException("OIDC_UPSTREAM_FAILED", label + " 请求已中断");
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new BizException("OIDC_UPSTREAM_FAILED", label + " 无法访问");
        }
    }
}

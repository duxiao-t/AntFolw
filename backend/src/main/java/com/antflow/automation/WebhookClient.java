package com.antflow.automation;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class WebhookClient {
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
        "host", "content-length", "content-type", "authorization",
        "x-antflow-delivery-id", "x-antflow-signature"
    );

    private final AutomationProperties properties;
    private final WebhookSecurityPolicy securityPolicy;
    private final ObjectMapper json;

    public DeliveryResult send(WorkflowJob job, JsonNode payload) {
        String method = payload.path("method").asText("POST").toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new BizException("BAD_WEBHOOK", "Webhook 请求方法无效");
        }
        URI uri = parseUri(payload.path("url").asText());
        securityPolicy.validate(uri);
        JsonNode parameters = payload.path("parameters");
        boolean queryMethod = "GET".equals(method) || "DELETE".equals(method);
        if (queryMethod) uri = appendQuery(uri, parameters);
        String contentType = payload.path("contentType").asText("application/json");
        String body = queryMethod ? "" : encodeBody(contentType, parameters);
        String secret = payload.path("secret").asText();
        if (secret.length() < 8) {
            throw new BizException("BAD_WEBHOOK", "Webhook HMAC 密钥至少需要 8 个字符");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(properties.getRequestTimeout())
            .header("Content-Type", contentType)
            .header("X-AntFlow-Delivery-Id", job.getDeliveryId().toString())
            .header("X-AntFlow-Signature", signature(job.getDeliveryId().toString(), body, secret));
        for (JsonNode header : payload.path("headers")) {
            String key = header.path("key").asText().trim();
            String value = header.path("value").asText();
            if (key.isBlank() || FORBIDDEN_HEADERS.contains(key.toLowerCase(Locale.ROOT))) continue;
            builder.header(key, value);
        }
        builder.method(method, body.isEmpty()
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
            HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            return new DeliveryResult(response.statusCode(), response.statusCode() >= 200
                && response.statusCode() < 300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebhookDeliveryException("Webhook delivery interrupted", e);
        } catch (Exception e) {
            throw new WebhookDeliveryException("Webhook delivery failed: "
                + e.getClass().getSimpleName(), e);
        }
    }

    private URI parseUri(String value) {
        try {
            return URI.create(value);
        } catch (Exception e) {
            throw new BizException("BAD_WEBHOOK", "Webhook URL 无效");
        }
    }

    private URI appendQuery(URI uri, JsonNode parameters) {
        StringBuilder query = new StringBuilder(uri.getRawQuery() == null ? "" : uri.getRawQuery());
        parameters.fields().forEachRemaining(entry -> {
            if (!query.isEmpty()) query.append('&');
            query.append(urlEncode(entry.getKey())).append('=').append(urlEncode(asText(entry.getValue())));
        });
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
                query.isEmpty() ? null : query.toString(), null);
        } catch (Exception e) {
            throw new BizException("BAD_WEBHOOK", "Webhook 查询参数无效");
        }
    }

    private String encodeBody(String contentType, JsonNode parameters) {
        if ("application/json".equals(contentType)) {
            try {
                return json.writeValueAsString(parameters);
            } catch (JsonProcessingException e) {
                throw new BizException("BAD_WEBHOOK", "Webhook JSON 参数无效");
            }
        }
        if ("application/x-www-form-urlencoded".equals(contentType)) {
            StringBuilder result = new StringBuilder();
            parameters.fields().forEachRemaining(entry -> {
                if (!result.isEmpty()) result.append('&');
                result.append(urlEncode(entry.getKey())).append('=').append(urlEncode(asText(entry.getValue())));
            });
            return result.toString();
        }
        throw new BizException("BAD_WEBHOOK", "Webhook 内容类型无效");
    }

    private static String asText(JsonNode value) {
        return value == null || value.isNull() ? "" : value.isValueNode() ? value.asText() : value.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String signature(String deliveryId, String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((deliveryId + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new BizException("BAD_WEBHOOK", "Webhook 签名失败");
        }
    }

    public record DeliveryResult(int statusCode, boolean successful) {}

    public static class WebhookDeliveryException extends RuntimeException {
        public WebhookDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

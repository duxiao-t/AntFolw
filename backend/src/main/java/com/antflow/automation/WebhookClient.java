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
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WebhookClient {
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
        "host", "content-length", "content-type", "authorization",
        "x-antflow-delivery-id", "x-antflow-signature"
    );
    /** 响应体只留前 4KB：足够看清 errcode/errmsg，又不至于被超大响应拖垮。 */
    private static final int MAX_RESPONSE_BYTES = 4096;

    private final AutomationProperties properties;
    private final WebhookSecurityPolicy securityPolicy;
    private final ObjectMapper json;

    public DeliveryResult send(WorkflowJob job, JsonNode payload) {
        return deliver(payload, job.getDeliveryId());
    }

    /**
     * 真正投递一次（`send` 与「测试发送」共用）。
     *
     * <p>响应体会被**读取并截断**（{@link #MAX_RESPONSE_BYTES}）后随结果返回——企业微信/钉钉等
     * 错误时仍是 HTTP 200，真正的失败原因在响应体里（errcode/errmsg），丢掉它等于把故障变成静默成功。
     */
    public DeliveryResult deliver(JsonNode payload, UUID deliveryId) {
        String method = payload.path("method").asText("POST").toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new BizException("BAD_WEBHOOK", "Webhook 请求方法无效");
        }
        URI uri = parseUri(payload.path("url").asText());
        JsonNode parameters = payload.path("parameters");
        boolean queryMethod = "GET".equals(method) || "DELETE".equals(method);
        if (queryMethod) uri = appendQuery(uri, parameters);
        String contentType = payload.path("contentType").asText("application/json");
        String body;
        if (!queryMethod && payload.hasNonNull("body")) {
            // 模板模式：请求体在入队时已渲染好（含嵌套结构），直接使用，HMAC 对最终字节签名。
            body = payload.path("body").asText();
        } else {
            body = queryMethod ? "" : encodeBody(contentType, parameters);
        }
        String secret = payload.path("secret").asText();
        if (secret.length() < 8) {
            throw new BizException("BAD_WEBHOOK", "Webhook HMAC 密钥至少需要 8 个字符");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(properties.getRequestTimeout())
            .header("Content-Type", contentType)
            .header("X-AntFlow-Delivery-Id", deliveryId.toString())
            .header("X-AntFlow-Signature", signature(deliveryId.toString(), body, secret));
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
            // SSRF 校验放在**紧邻连接**处，把「校验解析」与「连接解析」的窗口压到最小。
            // ponytail: java.net.http.HttpClient 无法注入自定义 DNS 解析器，故只能在连接前即时校验，
            // 无法把校验过的 IP 真正钉进连接（彻底修复需换成支持自定义 Resolver 的客户端）；
            // 配合下方 followRedirects(NEVER)，已堵住“重定向到内网”这条主线，残余 DNS 重绑定窗口见 F2 记录。
            securityPolicy.validate(uri);
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
            // 有界读取：只取前 MAX_RESPONSE_BYTES，超大响应绝不整体进内存（F1）。
            HttpResponse<java.io.InputStream> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String responseBody = readBounded(response.body(), MAX_RESPONSE_BYTES);
            return new DeliveryResult(status, responseBody, status >= 200 && status < 300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebhookDeliveryException("Webhook delivery interrupted", e);
        } catch (Exception e) {
            throw new WebhookDeliveryException("Webhook delivery failed: "
                + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 仅用于展示的"业务层可能失败"提示：HTTP 2xx，但响应 JSON 里的 {@code errcode}/{@code code}/
     * {@code StatusCode} 是数字且非 0（企业微信/钉钉/飞书都这么表达失败）。**不改判作业状态**，
     * 只让用户看到"HTTP 成功了但对方说没成功"。
     */
    public static String applicationWarning(JsonNode responseBodyJson) {
        if (responseBodyJson == null || !responseBodyJson.isObject()) return null;
        for (String field : new String[]{"errcode", "code", "StatusCode"}) {
            JsonNode value = responseBodyJson.get(field);
            if (value != null && value.isNumber() && value.asInt() != 0) {
                String msg = responseBodyJson.path("errmsg").asText(
                    responseBodyJson.path("message").asText(""));
                return "响应 " + field + "=" + value.asInt()
                    + (msg.isBlank() ? "" : "（" + msg + "）") + "，业务层可能未成功";
            }
        }
        return null;
    }

    /**
     * 只读取响应体的前 {@code max} 字节（按字节截断），其余流即时关闭丢弃——
     * 保证超大响应不会整体进入内存（F1）。截断时补一个“…”标记。
     */
    private static String readBounded(java.io.InputStream stream, int max) {
        if (stream == null) return "";
        try (stream) {
            byte[] head = stream.readNBytes(max);
            boolean truncated = stream.read() != -1;
            String value = new String(head, StandardCharsets.UTF_8);
            return truncated ? value + "…" : value;
        } catch (java.io.IOException e) {
            return "";
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

    public record DeliveryResult(int statusCode, String body, boolean successful) {}

    public static class WebhookDeliveryException extends RuntimeException {
        public WebhookDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

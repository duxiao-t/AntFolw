package com.antflow.integration.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class WecomClient {
    private static final Set<Integer> EXPIRED_TOKEN_CODES = Set.of(40001, 40014, 42001);
    private final WecomProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    WecomClient(WecomProperties properties, ObjectMapper json, HttpClient http) {
        this.properties = properties;
        this.json = json;
        this.http = http;
    }

    Session connect(String corpId, String secret) {
        Session session = new Session(corpId, secret);
        session.accessToken = token(session);
        return session;
    }

    List<WecomDepartment> departments(Session session) {
        JsonNode response = send("GET", "/cgi-bin/department/list", null, session);
        List<WecomDepartment> result = new ArrayList<>();
        for (JsonNode item : response.path("department")) {
            List<String> leaders = strings(item.path("department_leader"));
            result.add(new WecomDepartment(item.path("id").asLong(),
                item.path("parentid").asLong(), item.path("order").asInt(),
                item.path("name").asText(), leaders));
        }
        if (result.isEmpty()) throw new WecomApiException("企业微信未返回部门树");
        return result;
    }

    List<WecomUser> users(Session session, List<Long> departmentIds) {
        Map<String, WecomUser> users = new LinkedHashMap<>();
        for (Long departmentId : departmentIds) {
            String nextOpenId = "";
            int pages = 0;
            do {
                if (++pages > 1000) throw new WecomApiException("企业微信成员分页数量异常");
                String path = "/cgi-bin/user/list?department_id=" + departmentId + "&fetch_child=0";
                if (!nextOpenId.isBlank()) path += "&next_openid=" + encode(nextOpenId);
                JsonNode response = send("GET", path, null, session);
                for (JsonNode item : response.path("userlist")) {
                    String userId = item.path("userid").asText();
                    if (userId.isBlank()) continue;
                    List<Long> departments = longs(item.path("department"));
                    WecomUser previous = users.get(userId);
                    if (previous == null) {
                        users.put(userId, new WecomUser(userId,
                            item.path("name").asText(userId), departments,
                            item.path("main_department").asLong(0),
                            item.path("mobile").asText(), item.path("email").asText(),
                            item.path("position").asText(), item.path("gender").asText(),
                            item.path("status").asInt(1), List.of()));
                    } else {
                        Set<Long> merged = new LinkedHashSet<>(previous.departmentIds());
                        merged.addAll(departments);
                        users.put(userId, new WecomUser(userId, previous.name(),
                            List.copyOf(merged), previous.mainDepartment(), previous.phone(),
                            previous.email(), previous.position(), previous.gender(),
                            previous.status(), List.of()));
                    }
                }
                nextOpenId = response.path("next_openid").asText("");
            } while (!nextOpenId.isBlank());
        }
        return List.copyOf(users.values());
    }

    WecomUser user(Session session, WecomUserRef reference) {
        JsonNode item = send("GET", "/cgi-bin/user/get?userid=" + encode(reference.userId()),
            null, session);
        List<Long> departments = longs(item.path("department"));
        if (departments.isEmpty()) departments = reference.departmentIds();
        return new WecomUser(reference.userId(), item.path("name").asText(reference.userId()),
            departments, item.path("main_department").asLong(0), item.path("mobile").asText(),
            item.path("email").asText(), item.path("position").asText(),
            item.path("gender").asText(), item.path("status").asInt(1),
            strings(item.path("direct_leader")));
    }

    String oauthUserId(Session session, String code) {
        JsonNode response = send("GET", "/cgi-bin/auth/getuserinfo?code=" + encode(code), null, session);
        String userId = response.path("UserId").asText();
        if (userId.isBlank()) throw new WecomApiException("企业微信未返回内部成员身份");
        return userId;
    }

    String jsapiTicket(Session session) {
        String ticket = send("GET", "/cgi-bin/get_jsapi_ticket", null, session)
            .path("ticket").asText();
        if (ticket.isBlank()) throw new WecomApiException("企业微信未返回 JS-SDK ticket");
        return ticket;
    }

    Media temporaryMedia(Session session, String mediaId, String fallbackType) {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl()
                    + "/cgi-bin/media/get?access_token=" + encode(session.accessToken)
                    + "&media_id=" + encode(mediaId)))
                .timeout(properties.getRequestTimeout()).GET().build();
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                String contentType = response.headers().firstValue("Content-Type")
                    .orElse(fallbackType == null ? "application/octet-stream" : fallbackType)
                    .split(";", 2)[0].trim();
                if (response.statusCode() >= 200 && response.statusCode() < 300
                    && !contentType.toLowerCase().contains("json")) {
                    String disposition = response.headers().firstValue("Content-Disposition").orElse("");
                    return new Media(fileName(disposition, contentType), contentType, response.body());
                }
                JsonNode error = json.readTree(response.body());
                int code = error.path("errcode").asInt(0);
                if (EXPIRED_TOKEN_CODES.contains(code) && attempt == 0) {
                    session.accessToken = token(session);
                    continue;
                }
                throw new WecomApiException("企业微信临时素材下载失败（" + code + "）");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new WecomApiException("企业微信请求已中断");
            } catch (IOException exception) {
                if (attempt == 0) continue;
                throw new WecomApiException("无法下载企业微信临时素材");
            }
        }
        throw new WecomApiException("企业微信临时素材下载失败");
    }

    void sendTextCard(Session session, int agentId, String userId, String title,
                      String description, String url) {
        var textcard = json.createObjectNode()
            .put("title", title).put("description", description)
            .put("url", url).put("btntxt", "查看详情");
        var body = json.createObjectNode()
            .put("touser", userId).put("msgtype", "textcard").put("agentid", agentId);
        body.set("textcard", textcard);
        body.put("enable_duplicate_check", 1).put("duplicate_check_interval", 1800);
        send("POST", "/cgi-bin/message/send", body, session);
    }

    private String token(Session session) {
        JsonNode response = send("GET", "/cgi-bin/gettoken?corpid=" + encode(session.corpId)
            + "&corpsecret=" + encode(session.secret), null, null);
        String token = response.path("access_token").asText();
        if (token.isBlank()) throw new WecomApiException("企业微信未返回访问令牌");
        return token;
    }

    private JsonNode send(String method, String path, JsonNode body, Session session) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String separator = path.contains("?") ? "&" : "?";
            String authenticatedPath = session == null ? path
                : path + separator + "access_token=" + encode(session.accessToken);
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                    URI.create(properties.getBaseUrl() + authenticatedPath))
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/json");
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString(),
                        StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            try {
                HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 500 && attempt == 0) continue;
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new WecomApiException("企业微信服务暂时不可用（HTTP "
                        + response.statusCode() + "）");
                }
                JsonNode parsed;
                try {
                    parsed = json.readTree(response.body());
                } catch (Exception exception) {
                    throw new WecomApiException("企业微信返回了无法解析的响应");
                }
                int errorCode = parsed.path("errcode").asInt(0);
                if (errorCode == 0) return parsed;
                if (session != null && EXPIRED_TOKEN_CODES.contains(errorCode) && attempt == 0) {
                    session.accessToken = token(session);
                    continue;
                }
                throw new WecomApiException("企业微信接口返回错误（" + errorCode + "）");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new WecomApiException("企业微信请求已中断");
            } catch (IOException exception) {
                if (attempt == 0) continue;
                throw new WecomApiException("无法连接企业微信，请检查网络后重试");
            }
        }
        throw new WecomApiException("企业微信请求失败");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String fileName(String disposition, String contentType) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("filename=\\\"?([^\\\";]+)")
            .matcher(disposition == null ? "" : disposition);
        if (matcher.find()) return matcher.group(1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (contentType.startsWith("audio/")) return "wecom-audio." + contentType.substring(6).replace("x-", "");
        if (contentType.startsWith("image/")) return "wecom-image." + contentType.substring(6);
        return "wecom-media.bin";
    }

    private static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> {
            if (!value.asText().isBlank()) result.add(value.asText());
        });
        else if (node.isTextual() && !node.asText().isBlank()) result.add(node.asText());
        return result;
    }

    private static List<Long> longs(JsonNode node) {
        List<Long> result = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> result.add(value.asLong()));
        else if (node.isNumber()) result.add(node.asLong());
        return result;
    }

    static final class Session {
        private final String corpId;
        private final String secret;
        private String accessToken;

        private Session(String corpId, String secret) {
            this.corpId = corpId;
            this.secret = secret;
        }
    }

    record WecomDepartment(long id, long parentId, int order, String name,
                           List<String> leaderUserIds) { }
    record WecomUserRef(String userId, List<Long> departmentIds) { }
    record WecomUser(String userId, String name, List<Long> departmentIds,
                     long mainDepartment, String phone, String email, String position,
                     String gender, int status, List<String> directLeaders) { }
    record Media(String fileName, String contentType, byte[] content) { }

    static class WecomApiException extends RuntimeException {
        WecomApiException(String message) {
            super(message);
        }
    }
}

package com.antflow.automation;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * TRIGGER 请求体模板（{@code bodyTemplate}）：把 {@code ${字段id}} 渲染成字段值。
 *
 * <p>占位符语义按**它所在的位置**决定，贴合直觉也覆盖真实报文：
 * <ul>
 *   <li>在 JSON 字符串里（{@code "content":"审批 ${title} 通过"}）→ 按文本插入并做 JSON 转义，
 *       可以拼接固定文案与多个字段；</li>
 *   <li>不在字符串里（{@code "amount": ${total}}）→ 按 JSON 值插入（字符串自动加引号）。</li>
 * </ul>
 * 这样企业微信/钉钉那种 {@code {"msgtype":"text","text":{"content":"..."}}} 能直接拼出来，
 * 不必为每种报文写适配器。
 */
public final class WebhookBodyTemplate {

    private WebhookBodyTemplate() {
    }

    /** 渲染最终请求体；字段缺失时字符串上下文插入空串、值上下文插入 null。 */
    public static String render(String template, JsonNode formData, ObjectMapper json) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        boolean inString = false;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '\\' && inString && i + 1 < template.length()) {
                out.append(c).append(template.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                i++;
                continue;
            }
            if (c == '$' && i + 1 < template.length() && template.charAt(i + 1) == '{') {
                int end = template.indexOf('}', i + 2);
                if (end < 0) {
                    throw new BizException("BAD_WEBHOOK", "Webhook 请求体模板占位符缺少 }");
                }
                String ref = template.substring(i + 2, end).trim();
                JsonNode value = formData.path(ref);
                out.append(inString ? escapedText(value, json) : jsonValue(value, json));
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 模板引用的字段 id（去重保序），供发布校验。 */
    public static List<String> references(String template) {
        List<String> refs = new ArrayList<>();
        int i = 0;
        while ((i = template.indexOf("${", i)) >= 0) {
            int end = template.indexOf('}', i + 2);
            if (end < 0) break;
            String ref = template.substring(i + 2, end).trim();
            if (!ref.isEmpty() && !refs.contains(ref)) refs.add(ref);
            i = end + 1;
        }
        return refs;
    }

    /**
     * 结构校验用的探针：把占位符按上下文替换为合法 JSON 片段（字符串内→{@code x}，字符串外→{@code "x"}），
     * 这样模板本身是不是合法 JSON 就能用标准解析器判定。
     */
    public static String probe(String template) {
        StringBuilder out = new StringBuilder(template.length());
        boolean inString = false;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '\\' && inString && i + 1 < template.length()) {
                out.append(c).append(template.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                i++;
                continue;
            }
            if (c == '$' && i + 1 < template.length() && template.charAt(i + 1) == '{') {
                int end = template.indexOf('}', i + 2);
                if (end < 0) throw new BizException("BAD_FLOW", "Webhook 请求体模板占位符缺少 }");
                out.append(inString ? "x" : "\"x\"");
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String jsonValue(JsonNode value, ObjectMapper json) {
        if (value == null || value.isMissingNode()) return "null";
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException("BAD_WEBHOOK", "Webhook 请求体模板渲染失败");
        }
    }

    private static String escapedText(JsonNode value, ObjectMapper json) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        String text = value.isValueNode() ? value.asText() : value.toString();
        try {
            String quoted = json.writeValueAsString(text);
            return quoted.substring(1, quoted.length() - 1);
        } catch (JsonProcessingException e) {
            throw new BizException("BAD_WEBHOOK", "Webhook 请求体模板渲染失败");
        }
    }
}

package com.antflow.form.schema;

import com.antflow.engine.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 表单 schema/settings 的 JSON 边界：入参归一成树、语义比较、序列化。
 *
 * <p>为什么单独一个类：这四处原来都散在 {@code FormDefinitionService} 里，彼此隔了上千行，
 * 但改一处就要同时改另外三处——它们守的是同一条规则："JSON 列的排版不属于业务语义"。
 *
 * <p>两个坑（都踩过）：
 * <ol>
 *   <li>{@link #sameJson} 必须比**解析后的树**而不是字符串：schema/settings 存在 jsonb 列，
 *       读回来带 Postgres 自己的排版（{@code "id": "a"} 有空格的版本），直接比字符串会让
 *       "原样保存"永远被判为有变化，已发布表单被静默降级成草稿。</li>
 *   <li>{@link #readTree} 对空串返回空对象而不是抛错：调用方把 "没传" 与 "传了空" 当成同一个意思，
 *       抛错会把"未配置"变成 500。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class FormSchemaJson {

    private final ObjectMapper json;

    public JsonNode readTree(Object value) {
        if (value == null) return json.createObjectNode();
        if (value instanceof JsonNode node) return node;
        if (value instanceof String string) {
            if (string.isBlank()) return json.createObjectNode();
            try {
                return json.readTree(string);
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new BizException("BAD_JSON", error.getMessage());
            }
        }
        return json.valueToTree(value);
    }

    public Map<?, ?> valueMap(Object data) {
        if (data instanceof Map<?, ?> map) return map;
        if (data instanceof com.fasterxml.jackson.databind.JsonNode node && node.isObject()) {
            return json.convertValue(node, Map.class);
        }
        return Map.of();
    }

    /**
     * 语义比较两段 JSON。schema/settings 存在 jsonb 列里，读回来会带上 Postgres 自己的排版
     * （如 {@code "id": "a"} 的空格），所以不能直接比字符串——那会让「原样保存」永远被判为有变化。
     */
    public boolean sameJson(String left, String right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        try {
            return Objects.equals(json.readTree(left), json.readTree(right));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;   // 旧值坏了就当作已变化，仍走降级
        }
    }

    public String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }
}

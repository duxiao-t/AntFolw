package com.antflow.form.schema;

import com.antflow.engine.BizException;
import com.antflow.form.FieldTypeCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * schema 文本 → 节点树，并守住"什么算合法的节点"这条底线。
 *
 * <p>为什么独立：{@code parseSchema} 同时承担三件事——JSON 解析（错是 {@code BAD_SCHEMA_JSON}）、
 * 结构校验（错是 {@code BAD_SCHEMA}）、把根节点摊成 List。三种调用方（发布校验、提交校验、
 * 首填投影）都只关心"拿到一棵可信的树"，不关心另外两件事怎么实现。
 *
 * <p>**错误码不能合并**：前端按 code 分支提示"JSON 写坏了"还是"结构不对"，
 * 统一成 BAD_SCHEMA 会让设计器的报错指向错误的位置。
 *
 * <p>字段类型清单只认 {@link FieldTypeCatalog#ids()}：这里以前手写过一份 Set，
 * 与前端、移动端各自手写的清单静默漂移过。
 */
@Component
@RequiredArgsConstructor
public class FormSchemaParser {

    private static final Set<String> FIELD_TYPES = FieldTypeCatalog.ids();
    private static final Set<String> CONTAINER_TYPES =
        FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER);

    private final ObjectMapper json;
    /** 矩阵的行列轴语义与结构校验放在 FormMatrixRules；节点校验只负责"到点调用"。 */
    private final FormMatrixRules matrixRules;

    public List<com.fasterxml.jackson.databind.JsonNode> parseSchema(String s) {
        if (s == null) {
            throw new BizException("BAD_SCHEMA", "schema must be a non-empty array");
        }
        try {
            var arr = json.readTree(s);
            if (!arr.isArray() || arr.size() == 0) {
                throw new BizException("BAD_SCHEMA", "schema must be a non-empty array");
            }
            var nodes = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            arr.forEach(n -> {
                validateNode(n);
                nodes.add(n);
            });
            return nodes;
        } catch (BizException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_SCHEMA_JSON", e.getMessage());
        }
    }

    public void validateNode(com.fasterxml.jackson.databind.JsonNode node) {
        String id = node.path("id").asText("");
        String type = node.path("type").asText("");
        if (id.isBlank()) {
            throw new BizException("BAD_SCHEMA", "node.id is required");
        }
        if (type.isBlank() || !FIELD_TYPES.contains(type)) {
            throw new BizException("BAD_SCHEMA", "unsupported field type: " + type);
        }
        var rules = node.path("rules");
        if (!rules.isMissingNode() && !rules.isObject()) {
            throw new BizException("BAD_SCHEMA", "node.rules must be an object");
        }
        if ("matrix_fill".equals(type)) {
            matrixRules.validateMatrixSchema(node);
        }
        var children = node.path("children");
        if (!children.isMissingNode()) {
            if (!children.isArray()) {
                throw new BizException("BAD_SCHEMA", "node.children must be an array");
            }
            children.forEach(this::validateNode);
        }
    }

    /** 返回可配置字段权限的叶子字段 id（排除分栏/明细表/说明等展示型节点，含嵌套子字段）。 */
    public Set<String> leafFieldIds(String schema) {
        return leafFieldTypes(schema).keySet();
    }

    /** 返回叶子字段 id → 字段类型映射，供发布校验判断可编辑类型。 */
    public Map<String, String> leafFieldTypes(String schema) {
        var root = parseSchema(schema);
        var types = new java.util.LinkedHashMap<String, String>();
        for (var node : root) {
            collectLeafFieldTypes(node, types);
        }
        return java.util.Collections.unmodifiableMap(types);
    }

    private void collectLeafFieldTypes(com.fasterxml.jackson.databind.JsonNode node,
                                       Map<String, String> types) {
        String type = node.path("type").asText("");
        if (CONTAINER_TYPES.contains(type) || "table_list".equals(type)) {
            var containerChildren = node.path("children");
            if (containerChildren.isArray()) {
                containerChildren.forEach(child -> collectLeafFieldTypes(child, types));
            }
            return;
        }
        if ("description".equals(type)) {
            return;
        }
        types.put(node.path("id").asText(), type);
    }
}

package com.antflow.form.schema;

import com.antflow.form.FieldTypeCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 面向发起人的投影：按 ROOT 字段权限删掉 HIDDEN 的部分，再返回。
 *
 * <p>为什么独立：投影是**纯读**——只决定"下发给客户端什么"，不校验、不收值、不碰库。
 * 它和提交校验最容易混：两者的"可见"必须一致（共用 {@link FormVisibility}），
 * 但投影还要处理"容器/明细表里字段全被隐藏后，容器本身也不该下发"这类结构问题。
 *
 * <p>三条容易改错的规则：
 * <ol>
 *   <li>**容器不单独判可见**：{@code span_layout} 这类容器自身没有 id 级权限，
 *       它的去留取决于子节点是否全被删掉。</li>
 *   <li>**明细表整列隐藏时删整列**：{@code removeHiddenData} 先判 {@code hasStarterVisibleField}，
 *       否则会留下一个"行还在、值全空"的空表。</li>
 *   <li>**不修改入参**：{@code projectStarterData} 先 {@code deepCopy}，调用方还要拿原值去落库。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class FormStarterProjector {

    private static final Set<String> CONTAINER_TYPES =
        FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER);

    private final FormSchemaJson schemaJson;
    private final FormSchemaParser schemaParser;
    private final FormVisibility visibility;
    private final ObjectMapper json;

    public Map<String, String> starterFieldModes(Object process) {
        JsonNode root = schemaJson.readTree(process);
        var result = new java.util.LinkedHashMap<String, String>();
        JsonNode perms = root.path("props").path("formPerms");
        if (perms.isArray()) {
            for (var entry : perms) {
                String id = entry.path("fieldId").asText("").trim();
                String mode = entry.path("mode").asText("");
                if (!id.isBlank() && Set.of("EDITABLE", "READONLY", "HIDDEN").contains(mode)) {
                    result.put(id, mode);
                }
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public Map<String, Object> filterVisibleSubmission(String schema, Object data) {
        var values = schemaJson.valueMap(data);
        var root = schemaParser.parseSchema(schema);
        Set<String> visibleIds = visibility.visibleNodeIds(root, values);
        var output = new java.util.LinkedHashMap<String, Object>();
        for (var node : root) {
            collectVisibleValues(node, values, output, visibleIds);
        }
        return output;
    }

    /** Removes ROOT-hidden fields from starter-facing schema responses. */
    public JsonNode projectStarterSchema(Object schema, Object process) {
        JsonNode source = schemaJson.readTree(schema);
        if (!source.isArray()) return json.createArrayNode();
        Map<String, String> modes = starterFieldModes(process);
        ArrayNode output = json.createArrayNode();
        source.forEach(node -> {
            JsonNode projected = projectSchemaNode(node, modes);
            if (projected != null) output.add(projected);
        });
        return output;
    }

    /** Removes ROOT-hidden fields from starter-facing form data responses. */
    public JsonNode projectStarterData(Object data, Object schema, Object process) {
        JsonNode source = schemaJson.readTree(data);
        if (!source.isObject()) return json.createObjectNode();
        JsonNode schemaTree = schemaJson.readTree(schema);
        Map<String, String> modes = starterFieldModes(process);
        ObjectNode output = ((ObjectNode) source).deepCopy();
        if (schemaTree.isArray()) {
            schemaTree.forEach(node -> removeHiddenData(node, output, modes));
        }
        return output;
    }

    public boolean hasStarterVisibleField(JsonNode nodes, Map<String, String> modes) {
        for (var node : nodes) {
            String type = node.path("type").asText("");
            if (CONTAINER_TYPES.contains(type) || "table_list".equals(type)) {
                if (hasStarterVisibleField(node.path("children"), modes)) return true;
            } else if (!"description".equals(type)
                && !"HIDDEN".equals(modes.get(node.path("id").asText()))) {
                return true;
            }
        }
        return false;
    }

    private JsonNode projectSchemaNode(JsonNode node, Map<String, String> modes) {
        String type = node.path("type").asText("");
        String id = node.path("id").asText("");
        if (!CONTAINER_TYPES.contains(type) && "HIDDEN".equals(modes.get(id))) return null;
        ObjectNode copy = node.deepCopy();
        JsonNode children = node.path("children");
        if (children.isArray()) {
            ArrayNode projectedChildren = json.createArrayNode();
            children.forEach(child -> {
                JsonNode projected = projectSchemaNode(child, modes);
                if (projected != null) projectedChildren.add(projected);
            });
            copy.set("children", projectedChildren);
            if ((CONTAINER_TYPES.contains(type) || "table_list".equals(type))
                && projectedChildren.isEmpty()) return null;
        }
        return copy;
    }

    private void removeHiddenData(JsonNode node, ObjectNode data,
                                  Map<String, String> modes) {
        String type = node.path("type").asText("");
        if (CONTAINER_TYPES.contains(type)) {
            node.path("children").forEach(child -> removeHiddenData(child, data, modes));
            return;
        }
        String id = node.path("id").asText("");
        if ("table_list".equals(type)) {
            if (!hasStarterVisibleField(node.path("children"), modes)) {
                data.remove(id);
                return;
            }
            JsonNode rows = data.path(id);
            if (rows.isArray()) {
                rows.forEach(row -> {
                    if (row instanceof ObjectNode rowObject) {
                        node.path("children").forEach(child ->
                            removeHiddenData(child, rowObject, modes));
                    }
                });
            }
            return;
        }
        if ("HIDDEN".equals(modes.get(id))) data.remove(id);
    }

    public void collectVisibleValues(com.fasterxml.jackson.databind.JsonNode node,
                                      Map<?, ?> values, Map<String, Object> output,
                                      Set<String> visibleIds) {
        if (!visibleIds.contains(node.path("id").asText())) return;
        String type = node.path("type").asText("");
        if (CONTAINER_TYPES.contains(type)) {
            node.path("children").forEach(child -> collectVisibleValues(child, values, output, visibleIds));
            return;
        }
        String id = node.path("id").asText();
        if ("table_list".equals(type) && values.get(id) instanceof List<?> rows) {
            var visibleRows = new java.util.ArrayList<Map<String, Object>>();
            for (Object rawRow : rows) {
                if (!(rawRow instanceof Map<?, ?> row)) continue;
                Set<String> rowVisibleIds = visibility.visibleNodeIds(node.path("children"), row);
                var visibleRow = new java.util.LinkedHashMap<String, Object>();
                node.path("children").forEach(child ->
                    collectVisibleValues(child, row, visibleRow, rowVisibleIds));
                visibleRows.add(visibleRow);
            }
            output.put(id, visibleRows);
            return;
        }
        if (!"description".equals(type) && values.containsKey(id)) {
            output.put(id, values.get(id));
        }
    }
}

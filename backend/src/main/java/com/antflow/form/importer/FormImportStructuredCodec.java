package com.antflow.form.importer;

import com.antflow.form.FieldTypeCatalog;
import com.antflow.form.importer.FormImportValueCodec.Prefetch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 结构化族：table_list / checklist / matrix_fill。单元格里放的是 JSON。
 *
 * <p>三处容易改错：
 * <ol>
 *   <li>明细行的子字段要走**同一套**解码规则（人员 id、多选数组、嵌套容器），所以递归解码
 *       由门面以 {@link ChildDecoder} 注入——本类不持有门面实例，避免反向依赖。</li>
 *   <li>JSON 里的子值必须摊成与 Excel 单元格一致的文本形态：直接 {@code toString()} 会得到
 *       {@code [1,2]}，后面的分隔符解析认不出来。</li>
 *   <li>检查项的 {@code images} 恒为空数组而不是省略——省略会让下游拿 null 去 count。</li>
 * </ol>
 */
@Component
public class FormImportStructuredCodec {

    private final ObjectMapper json;

    public FormImportStructuredCodec(ObjectMapper json) {
        this.json = json;
    }

    /** 子字段递归解码：由门面注入（本类只认这个函数式接口）。 */
    @FunctionalInterface
    public interface ChildDecoder {
        Object decode(JsonNode child, String text, Prefetch prefetch);
    }

    List<Map<String, Object>> tableList(JsonNode field, String label, String text,
                                        Prefetch prefetch, ChildDecoder decoder) {
        JsonNode array = requireJson(label, text, true);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                throw FormImportSupport.invalid(label, "每一行都要写成 { ... }，现在有一行不是");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            field.path("children").forEach(child ->
                decodeChild(child, item, row, prefetch, decoder));
            rows.add(row);
        }
        return rows;
    }

    /** 明细行列的子字段也要平铺容器（span_layout），并跳过纯展示字段。 */
    private void decodeChild(JsonNode child, JsonNode item, Map<String, Object> row,
                             Prefetch prefetch, ChildDecoder decoder) {
        String type = child.path("type").asText("");
        if (FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER).contains(type)) {
            child.path("children").forEach(node -> decodeChild(node, item, row, prefetch, decoder));
            return;
        }
        if ("description".equals(type)) return;
        String id = child.path("id").asText("");
        if (id.isBlank()) return;
        JsonNode raw = item.path(id);
        if (raw.isMissingNode() || raw.isNull()) return;
        String text = childText(raw, type);
        if (text.isBlank()) return;
        Object value = decoder.decode(child, text, prefetch);
        if (value != null) row.put(id, value);
    }

    /**
     * 明细行 JSON 里的子值 → 单元格文本。
     *
     * <p>JSON 里人员可能是数字 id、多选可能是数组：直接 {@code toString()} 会得到 {@code [1,2]}，
     * 后面的分隔符解析根本认不出来。这里把它们摊成与 Excel 单元格一致的文本形态。
     */
    private static String childText(JsonNode raw, String type) {
        boolean picker = "user_picker".equals(type) || "dept_picker".equals(type);
        if (raw.isTextual()) return raw.asText();
        if (raw.isNumber()) return picker ? "id:" + raw.asLong() : raw.asText();
        if (raw.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : raw) {
                if (item.isTextual()) parts.add(item.asText());
                else if (item.isNumber()) parts.add(picker ? "id:" + item.asLong() : item.asText());
                else parts.add(item.toString());
            }
            return String.join("、", parts);
        }
        return raw.toString();
    }

    List<Map<String, Object>> checklist(JsonNode field, String label, String text) {
        JsonNode array = requireJson(label, text, true);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                throw FormImportSupport.invalid(label, "每个检查项都要写成 { ... }，现在有一个不是");
            }
            String id = item.path("id").asText(item.path("itemId").asText(""));
            if (id.isBlank()) {
                throw FormImportSupport.invalid(label, "缺少检查项编号（id），请按模板「填写说明」填写");
            }
            String status = item.path("status").asText(item.path("result").asText(""));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("status", resultId(field, label, status));
            // 导入不可能带图片：恒为空数组，而不是"省略"——省略会让下游拿 null 去 count。
            entry.put("images", List.of());
            String description = item.path("description").asText(item.path("remark").asText(""));
            if (!description.isBlank()) entry.put("description", description);
            entries.add(entry);
        }
        return entries;
    }

    /** 检查项结果既接受结果 id 也接受结果 label（历史台账里两种都常见）。 */
    private String resultId(JsonNode field, String label, String status) {
        String value = status == null ? "" : status.trim();
        if (value.isEmpty()) return "";
        JsonNode results = field.path("props").path("results");
        if (!results.isArray() || results.isEmpty()) return value;
        for (JsonNode result : results) {
            if (result.path("id").asText("").equals(value)) return value;
        }
        for (JsonNode result : results) {
            if (result.path("label").asText("").equals(value)) return result.path("id").asText(value);
        }
        // 面向用户：告诉他"能填什么"，而不是"你填的不在配置里"。
        throw FormImportSupport.invalid(label, "「结果」只能填 "
            + FormImportSupport.labels(results) + "（现在填的是「" + value + "」）");
    }

    Map<String, Object> matrix(JsonNode field, String label, String text) {
        JsonNode node = requireJson(label, text, false);
        if (!node.isObject()) throw FormImportSupport.invalid(label, "矩阵填写的值必须是 JSON 对象");
        String cellType = field.path("props").path("cellType").asText("textarea");
        Map<String, Object> value = new LinkedHashMap<>();
        JsonNode cells = node.path("cells");
        if (cells.isObject()) {
            Map<String, Object> decodedCells = new LinkedHashMap<>();
            cells.fields().forEachRemaining(rowEntry -> {
                if (!rowEntry.getValue().isObject()) {
                    throw FormImportSupport.invalid(label, "矩阵的每一行必须是对象");
                }
                Map<String, Object> rowCells = new LinkedHashMap<>();
                rowEntry.getValue().fields().forEachRemaining(cellEntry ->
                    decodeMatrixCell(label, cellType, cellEntry.getKey(), cellEntry.getValue(), rowCells));
                decodedCells.put(rowEntry.getKey(), rowCells);
            });
            value.put("cells", decodedCells);
        }
        if (node.path("customRows").isArray()) value.put("customRows", json.convertValue(node.path("customRows"), List.class));
        if (node.path("customColumns").isArray()) value.put("customColumns", json.convertValue(node.path("customColumns"), List.class));
        return value;
    }

    private void decodeMatrixCell(String label, String cellType, String column,
                                  JsonNode raw, Map<String, Object> rowCells) {
        if (raw == null || raw.isNull()) return;
        String text = raw.isTextual() ? raw.asText() : raw.toString();
        if (text.isBlank()) return;
        if ("number".equals(cellType)) {
            // 走统一的 number()：带千分位/非数字要报 IMPORT_VALUE_INVALID，而不是让
            // NumberFormatException 漏出去变成 500。
            rowCells.put(column, FormImportScalarCodec.number(label, text));
        } else {
            rowCells.put(column, text);
        }
    }

    private JsonNode requireJson(String label, String text, boolean array) {
        JsonNode node = FormImportSupport.readJson(json, text);
        if (node == null || node.isNull()) {
            throw FormImportSupport.invalid(label,
                "内容不完整（不是合法的 JSON）：请检查中括号、引号是否成对");
        }
        if (array && !node.isArray()) {
            throw FormImportSupport.invalid(label, "需要写成 [ ... ] 这样的列表格式");
        }
        if (!array && !node.isObject()) {
            throw FormImportSupport.invalid(label, "必须是 JSON 对象：{ ... }");
        }
        return node;
    }
}

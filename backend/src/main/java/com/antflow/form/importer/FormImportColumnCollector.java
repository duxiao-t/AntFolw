package com.antflow.form.importer;

import com.antflow.engine.BizException;
import com.antflow.form.FieldTypeCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * schema → "一行一个可填字段"的列清单。规则见 {@link FormImportTemplateService} 类注释。
 *
 * <p>单独一类的理由：决定"哪些字段出列、列叫什么、占第几列"的规则每天都会因为产品口径而变
 * （容器平铺、媒体剔除、重名加序号），而怎么把这些列画进 xlsx 几乎不变；两者混在一起，
 * 改规则的人要先读懂 POI。
 *
 * <p>两条不要"顺手优化"的地方：
 * <ol>
 *   <li>**不要按 fieldId 去重**：同一份 schema 里 id 重复是 schema 校验该拦的事，
 *       在这里悄悄丢掉一列会让模板少一列、导入端又对不上。</li>
 *   <li>**重名 label 加序号**（{@code 备注(2)}）：表头允许重复，但人要靠它认列；
 *       列的身份由第二行的 fieldId 决定，改显示名不影响导入。</li>
 * </ol>
 */
@Component
public class FormImportColumnCollector {

    /** 静态选项超过这个数就不做下拉校验：Excel 的下拉列表在几百项时已经没法用。 */
    public static final int MAX_DICT_OPTIONS = 20;

    private static final Set<String> MEDIA_TYPES = FieldTypeCatalog.ALL.stream()
        .filter(FieldTypeCatalog.FieldType::media)
        .map(FieldTypeCatalog.FieldType::id)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * 模板里的一列。
     *
     * @param dictionary 仅 select/radio/switch 的静态选项标签（用于下拉校验），其余为空
     */
    public record Column(String fieldId, String label, String type, int order,
                         boolean required, List<String> dictionary) { }

    public List<Column> columns(JsonNode schema) {
        List<Column> raw = new ArrayList<>();
        collect(schema, raw);
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<Column> result = new ArrayList<>(raw.size());
        for (Column column : raw) {
            int count = seen.merge(column.label(), 1, Integer::sum);
            String label = count == 1 ? column.label() : column.label() + "(" + count + ")";
            // 表头单元格也受解析器的 100 字符限制：先截断，否则字段名一长模板就永远导不进来。
            if (label.length() > 100) label = label.substring(0, 100);
            result.add(new Column(column.fieldId(), label, column.type(), result.size(),
                column.required(), column.dictionary()));
        }
        return result;
    }

    private void collect(JsonNode nodes, List<Column> out) {
        if (nodes == null || !nodes.isArray()) return;
        for (JsonNode node : nodes) {
            String type = node.path("type").asText("");
            String fieldId = node.path("id").asText("");
            if (fieldId.isBlank()) continue;
            String label = node.path("label").asText("");
            if (label.isBlank()) label = fieldId;
            if (FieldTypeCatalog.idsOfKind(FieldTypeCatalog.Kind.CONTAINER).contains(type)) {
                // 容器与父级共用扁平值域：子字段平铺，容器本身没有值。
                collect(node.path("children"), out);
                continue;
            }
            if ("description".equals(type)) continue;
            if (MEDIA_TYPES.contains(type)) {
                if (required(node)) {
                    throw new BizException("IMPORT_FIELD_UNSUPPORTED",
                        "「" + label + "」是必填的媒体字段，Excel 无法携带文件，请改用表单填写");
                }
                continue;
            }
            out.add(new Column(fieldId, label, type, out.size(), required(node), dictionary(node)));
        }
    }

    private static boolean required(JsonNode node) {
        return node.path("rules").path("required")
            .asBoolean(node.path("props").path("required").asBoolean(false));
    }

    /** 静态选项标签；外部选项源在下载模板时**不查库**（模板是纯渲染，不许有 IO 依赖）。 */
    private static List<String> dictionary(JsonNode node) {
        String type = node.path("type").asText("");
        if ("switch".equals(type)) {
            String checked = node.path("props").path("checkedText").asText("是");
            String unchecked = node.path("props").path("uncheckedText").asText("否");
            return List.of(checked, unchecked);
        }
        if (!Set.of("select", "radio").contains(type)) return List.of();
        JsonNode options = node.path("props").path("options");
        if (!options.isArray() || options.isEmpty() || options.size() > MAX_DICT_OPTIONS) return List.of();
        List<String> labels = new ArrayList<>();
        options.forEach(option -> {
            String label = option.path("label").asText("");
            if (!label.isBlank()) labels.add(label);
        });
        return List.copyOf(labels);
    }

    /** 供外部（导入解析器）判断某类型是否根本不进模板。 */
    public static boolean isMedia(String type) {
        return MEDIA_TYPES.contains(type);
    }
}
